package com.repodatagraph.adapter.`in`.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef

/**
 * The ontology as prompt text (#81): what `GET /api/v1/ontology?format=markdown` serves.
 *
 * An agent is handed this to know what it may ask, so it has two constraints the JSON does not. It
 * must be the same bytes on every call, so a cached prompt stays cached: registry order throughout,
 * no timestamps. And it must stay under 12,000 characters, so it leaves room for the question. To fit,
 * it carries what a query needs and leaves the rest to the JSON: meta types, deprecated properties and
 * property descriptions are left out, each property is one line of its type, allowed values, format
 * and first example, and each relationship is listed once, under Relationships, rather than again
 * beside each type it connects. docs/ONTOLOGY.md records the rule; the bound is tested, not raised.
 */
object OntologyMarkdown {
    private val json = ObjectMapper()
    private const val ENVIRONMENT = "Environment"

    fun render(registry: OntologyRegistry): String {
        val nodeTypes = registry.allNodeTypes().filterNot { it.meta }
        val shown = nodeTypes.map { it.name }.toSet()
        val edgeTypes =
            registry
                .allEdgeTypes()
                .map { it.copy(from = it.from.filter(shown::contains), to = it.to.filter(shown::contains)) }
                .filter { it.from.isNotEmpty() && it.to.isNotEmpty() }

        return buildString {
            append("# SDLC knowledge graph ontology v${registry.version}\n\n")
            append(INTRODUCTION).append("\n")
            nodeTypes.forEach { appendNodeType(it, registry) }
            if (edgeTypes.isNotEmpty()) {
                append("\n## Relationships\n")
                edgeTypes.forEach { appendEdgeType(it) }
            }
            if (registry.templates.isNotEmpty()) {
                // One line each: an agent names a template, and the JSON spells out its steps (#96).
                append("\n## Context pack templates\n")
                append("POST /api/v1/context-pack {startId, template, budget} walks one from a node of a type it starts from.\n")
                registry.templates.forEach { append("- ${it.name} (from ${it.start.joinToString()}): ${it.description}\n") }
            }
        }
    }

    private fun StringBuilder.appendNodeType(
        type: NodeTypeDef,
        registry: OntologyRegistry,
    ) {
        append("\n## ${type.name}\n")
        append(listOfNotNull(type.description, "Key: ${type.identity.joinToString()}.").joinToString(" ")).append("\n")
        if (type.questions.isNotEmpty()) append("Answers: ${type.questions.joinToString(" ")}\n")
        // One line for the whole alias table (#98), so an agent writes `prod` knowing it is production.
        if (type.name == ENVIRONMENT && registry.environments.any { it.aliases.isNotEmpty() }) {
            val known = registry.environments.filter { it.aliases.isNotEmpty() }
            append("Also known as: ${known.joinToString("; ") { "${it.name} (${it.aliases.joinToString()})" }}.\n")
        }
        type.properties.filter { it.deprecated == null }.forEach { append("- ${line(it)}\n") }
    }

    private fun StringBuilder.appendEdgeType(type: EdgeTypeDef) {
        val ends = "${type.from.joinToString("|")} -> ${type.to.joinToString("|")}, read back as ${type.inverse}"
        append("- ${type.name} ($ends)")
        type.description?.let { append(": ").append(it) }
        append("\n")
        type.properties.filter { it.deprecated == null }.forEach { append("  - ${line(it)}\n") }
    }

    /** `name: type!` then any allowed values, `[format]` and first example, each only where declared. */
    private fun line(property: PropertyDef): String {
        val parts = mutableListOf("${property.name}: ${property.type.wireName}${if (property.required) "!" else ""}")
        property.enum?.let { parts += it.joinToString("|") }
        property.format?.let { format ->
            val condition = property.formatWhen.entries.joinToString(", ") { (key, value) -> "$key=$value" }
            parts += if (condition.isEmpty()) "[${format.wireName}]" else "[${format.wireName} when $condition]"
        }
        if (property.examples.isNotEmpty()) parts += "e.g. ${example(property.examples.first())}"
        return parts.joinToString(" ")
    }

    private fun example(value: Any?): String = value as? String ?: json.writeValueAsString(value)

    private const val INTRODUCTION =
        "Node types with their properties, then the relationships between them. `!` marks a required property, " +
            "`a|b` lists the only values allowed, `[format]` names a value's shape and `e.g.` shows one. " +
            "Meta types and deprecated properties are left out: GET /api/v1/ontology has everything."
}
