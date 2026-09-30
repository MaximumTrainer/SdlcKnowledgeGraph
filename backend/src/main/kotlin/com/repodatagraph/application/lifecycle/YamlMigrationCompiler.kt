package com.repodatagraph.application.lifecycle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.repodatagraph.domain.lifecycle.CompiledStatement
import com.repodatagraph.domain.lifecycle.InvalidMigrationException
import com.repodatagraph.domain.lifecycle.MigrationFile
import com.repodatagraph.domain.lifecycle.MigrationFormat
import com.repodatagraph.domain.ontology.OntologyRegistry

/**
 * Turns a migration file into the Cypher statements it runs (#33, FR7).
 *
 * A YAML migration is a list of operations the registry can check: what it renames or adds must be
 * declared, and what it drops must not be, so a migration cannot move data somewhere the build does
 * not read. Labels, relationship types and property names cannot be Cypher parameters, so each one
 * is checked to be a plain name before it is written into a statement; values are parameters.
 *
 * Every statement is idempotent: it matches only what is still in the old shape, so running it
 * again finds nothing to change.
 */
class YamlMigrationCompiler(
    private val registry: OntologyRegistry,
) {
    private val yaml = YAMLMapper()
    private val names = MigrationNames(registry)

    fun compile(file: MigrationFile): List<CompiledStatement> =
        when (file.format) {
            MigrationFormat.CYPHER -> CypherScript.statements(file.content)
            MigrationFormat.YAML -> {
                val operations = document(file).path("operations")
                if (!operations.isArray) throw InvalidMigrationException("migration ${file.id} has no list of operations")
                operations.flatMap { compileOperation(file, it) }
            }
        }

    /** What the migration says it does, from its `description`, for the status page. */
    fun description(file: MigrationFile): String? =
        when (file.format) {
            MigrationFormat.CYPHER ->
                file.content
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("//") }
                    ?.removePrefix("//")
                    ?.trim()
            MigrationFormat.YAML -> document(file).path("description").takeIf { it.isTextual }?.asText()
        }

    private fun document(file: MigrationFile): JsonNode =
        try {
            yaml.readTree(file.content) ?: yaml.createObjectNode()
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            throw InvalidMigrationException("migration ${file.id} is not valid YAML: ${failure.message}", failure)
        }

    private fun compileOperation(
        file: MigrationFile,
        operation: JsonNode,
    ): List<CompiledStatement> {
        val name = operation.fieldNames().asSequence().singleOrNull()
        val args = name?.let { operation.path(it) }
        return when (name) {
            "renameProperty" -> listOf(renameProperty(file, args!!))
            "addProperty" -> listOf(addProperty(file, args!!))
            "dropProperty" -> listOf(dropProperty(file, args!!))
            "renameType" -> listOf(relabel(file, names.text(file, args!!, "from"), names.declaredType(file, names.text(file, args, "to"))))
            "mergeTypes" -> mergeTypes(file, args!!)
            "renameEdge" -> listOf(renameEdge(file, args!!))
            else -> throw InvalidMigrationException(
                "migration ${file.id} has an operation nobody declared: '${name ?: operation}'; " +
                    "it can use $OPERATIONS",
            )
        }
    }

    private fun renameProperty(
        file: MigrationFile,
        args: JsonNode,
    ): CompiledStatement {
        val type = names.declaredType(file, names.text(file, args, "type"))
        val from = names.text(file, args, "from")
        val to = names.declaredProperty(file, type, names.text(file, args, "to"))
        return CompiledStatement(
            "MATCH (n:$type) WHERE n.$from IS NOT NULL AND n.$to IS NULL SET n.$to = n.$from REMOVE n.$from",
        )
    }

    private fun addProperty(
        file: MigrationFile,
        args: JsonNode,
    ): CompiledStatement {
        val type = names.declaredType(file, names.text(file, args, "type"))
        val property = names.declaredProperty(file, type, names.text(file, args, "property"))
        val default = args.get("default") ?: throw InvalidMigrationException("migration ${file.id}: addProperty needs a default")
        return CompiledStatement(
            "MATCH (n:$type) WHERE n.$property IS NULL SET n.$property = \$default",
            mapOf("default" to yaml.treeToValue(default, Any::class.java)),
        )
    }

    private fun dropProperty(
        file: MigrationFile,
        args: JsonNode,
    ): CompiledStatement {
        val type = names.declaredType(file, names.text(file, args, "type"))
        val property = names.text(file, args, "property")
        if (registry.nodeType(type)!!.properties.any { it.name == property }) {
            throw InvalidMigrationException(
                "migration ${file.id} drops $type.$property, which the registry still declares; " +
                    "remove it from the registry in the same release",
            )
        }
        return CompiledStatement("MATCH (n:$type) WHERE n.$property IS NOT NULL REMOVE n.$property")
    }

    private fun mergeTypes(
        file: MigrationFile,
        args: JsonNode,
    ): List<CompiledStatement> {
        val into = names.declaredType(file, names.text(file, args, "into"))
        val from = args.path("from")
        if (!from.isArray ||
            from.isEmpty
        ) {
            throw InvalidMigrationException("migration ${file.id}: mergeTypes needs a list of types to merge from")
        }
        return from.map { relabel(file, names.safe(file, it.asText()), into) }
    }

    private fun relabel(
        file: MigrationFile,
        from: String,
        to: String,
    ): CompiledStatement {
        names.safe(file, from)
        return CompiledStatement("MATCH (n:$from) SET n:$to REMOVE n:$from SET n.id = '$to:' + n.key")
    }

    private fun renameEdge(
        file: MigrationFile,
        args: JsonNode,
    ): CompiledStatement {
        val from = names.text(file, args, "from")
        val to = names.text(file, args, "to")
        if (registry.edgeType(to) == null) {
            throw InvalidMigrationException("migration ${file.id} renames an edge to $to, which the registry does not declare")
        }
        return CompiledStatement("MATCH (a)-[r:$from]->(b) CREATE (a)-[s:$to]->(b) SET s = properties(r) DELETE r")
    }

    private companion object {
        val OPERATIONS = listOf("renameProperty", "addProperty", "dropProperty", "renameType", "mergeTypes", "renameEdge")
    }
}

/**
 * The names a migration's operations use, checked before any is written into a statement: each must
 * be a plain name, since none can be a Cypher parameter, and what an operation creates must be
 * declared by the registry.
 */
internal class MigrationNames(
    private val registry: OntologyRegistry,
) {
    fun text(
        file: MigrationFile,
        args: JsonNode,
        field: String,
    ): String {
        val value = args.path(field)
        if (!value.isTextual) throw InvalidMigrationException("migration ${file.id}: an operation is missing '$field'")
        return safe(file, value.asText())
    }

    fun safe(
        file: MigrationFile,
        name: String,
    ): String {
        if (!SAFE_NAME.matches(name)) {
            throw InvalidMigrationException("migration ${file.id} names '$name', which is not a plain name a statement can hold")
        }
        return name
    }

    fun declaredType(
        file: MigrationFile,
        type: String,
    ): String {
        if (registry.nodeType(type) == null) {
            throw InvalidMigrationException("migration ${file.id} names node type $type, which the registry does not declare")
        }
        return type
    }

    fun declaredProperty(
        file: MigrationFile,
        type: String,
        property: String,
    ): String {
        if (registry.nodeType(type)!!.properties.none { it.name == property }) {
            throw InvalidMigrationException("migration ${file.id} names $type.$property, which the registry does not declare")
        }
        return property
    }

    private companion object {
        val SAFE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    }
}

/** A raw Cypher migration's statements. */
internal object CypherScript {
    /** Statements end with a semicolon at the end of a line; a line starting `//` is a comment. */
    fun statements(content: String): List<CompiledStatement> {
        val statements = mutableListOf<CompiledStatement>()
        val buffer = mutableListOf<String>()

        fun flush() {
            val statement =
                buffer
                    .joinToString("\n")
                    .trim()
                    .removeSuffix(";")
                    .trim()
            if (statement.isNotEmpty()) statements += CompiledStatement(statement)
            buffer.clear()
        }
        content.replace("\r\n", "\n").lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach
            buffer += line.trimEnd()
            if (trimmed.endsWith(";")) flush()
        }
        flush()
        return statements
    }
}
