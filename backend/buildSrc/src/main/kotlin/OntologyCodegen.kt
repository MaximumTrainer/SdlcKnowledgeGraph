/**
 * Renders the ontology into the forms other layers need, so a node type is declared once.
 *
 * Output is deterministic: types in registry order, properties in declaration order, LF endings and
 * no timestamps. That is what lets the drift check compare generated output against committed files
 * byte for byte and treat a difference as an error rather than noise.
 */
object OntologyCodegen {
    private const val HEADER = "GENERATED FROM ontology/v1 - DO NOT EDIT"
    private const val PRINT_WIDTH = 100
    private val COMPACT = com.fasterxml.jackson.databind.ObjectMapper()

    fun graphqlSdl(ontology: GenOntology): String =
        buildString {
            appendLine("# $HEADER")
            appendLine("# Types are generated from the ontology registry. Queries and mutations live in schema.graphqls.")
            appendLine()
            appendLine("\"\"\"Where a fact came from, attached to every node and edge.\"\"\"")
            appendLine("type Provenance {")
            ontology.provenance.forEach { property ->
                property.description?.let { appendLine("  \"\"\"${docstring(it)}\"\"\"") }
                appendLine("  ${property.name}: ${graphqlType(property)}")
            }
            appendLine("}")
            appendLine()
            appendLine("\"\"\"Anything addressable in the graph.\"\"\"")
            appendLine("interface GraphNode {")
            appendLine("  id: ID!")
            appendLine("  key: String!")
            appendLine("  provenance: Provenance!")
            appendLine("}")
            appendLine()

            ontology.nodeTypes.forEach { nodeType ->
                // An enum is a type of its own, declared ahead of the node type that uses it. The field
                // itself stays a String, so a value stored before the enum existed still reads (#81).
                nodeType.declaredBeyondStructural().filter { it.isEnum() }.forEach { property ->
                    appendLine("\"\"\"The values ${nodeType.name}.${property.name} may take.\"\"\"")
                    appendLine("enum ${enumName(nodeType, property)} {")
                    property.enum.orEmpty().forEach { value ->
                        val name = graphqlEnumValue(value)
                        if (name != value) appendLine("  \"\"\"Stored as $value.\"\"\"")
                        appendLine("  $name")
                    }
                    appendLine("}")
                    appendLine()
                }
                nodeType.description?.let { appendLine("\"\"\"$it\"\"\"") }
                appendLine("type ${nodeType.name}Node implements GraphNode {")
                appendLine("  id: ID!")
                appendLine("  key: String!")
                nodeType.declaredBeyondStructural().forEach { property ->
                    val enumNote = if (property.isEnum()) " One of ${enumName(nodeType, property)}." else ""
                    property.description?.let {
                        val text = if (enumNote.isEmpty()) it else it.trimEnd('.') + "." + enumNote
                        appendLine("  \"\"\"${docstring(text)}\"\"\"")
                    }
                    val deprecation =
                        property.deprecated?.let { " @deprecated(reason: \"${deprecationReason(it)}\")" }.orEmpty()
                    appendLine("  ${property.name}: ${graphqlType(property)}$deprecation")
                }
                appendLine("  provenance: Provenance!")
                appendLine("}")
                appendLine()
            }

            appendLine("enum EdgeType {")
            ontology.edgeTypes.forEach { appendLine("  ${it.name}") }
            appendLine("}")
            appendLine()
            appendLine("type Edge {")
            appendLine("  type: EdgeType!")
            appendLine("  inverse: String!")
            appendLine("  from: GraphNode!")
            appendLine("  to: GraphNode!")
            appendLine("  provenance: Provenance!")
            appendLine("}")
        }

    fun typescript(ontology: GenOntology): String =
        buildString {
            appendLine("// $HEADER")
            appendLine("// Run ./gradlew generateOntology after changing the registry.")
            appendLine()
            appendLine("export const ONTOLOGY_VERSION = '${ontology.version}'")
            appendLine()
            appendLine("export interface Provenance {")
            ontology.provenance.forEach { property ->
                val nullable = if (property.required) "" else " | null"
                appendLine("  ${property.name}: ${typescriptType(property)}$nullable")
            }
            appendLine("}")
            appendLine()

            ontology.nodeTypes.forEach { nodeType ->
                nodeType.declaredBeyondStructural().filter { it.isEnum() }.forEach { property ->
                    appendLine(typescriptUnion(enumName(nodeType, property), property.enum.orEmpty()))
                    appendLine()
                }
                nodeType.description?.let { appendLine("/** $it */") }
                appendLine("export interface ${nodeType.name} {")
                appendLine("  id: string")
                nodeType.declaredBeyondStructural().forEach { property ->
                    val optional = if (property.required) "" else "?"
                    val type = if (property.isEnum()) enumName(nodeType, property) else typescriptType(property)
                    appendJsDoc(property)
                    appendLine("  ${property.name}$optional: $type")
                }
                appendLine("}")
                appendLine()
            }

            appendLine("export type NodeType =")
            ontology.nodeTypes.forEach { appendLine("  | '${it.name}'") }
            appendLine()
            appendLine("export const NODE_TYPES: readonly NodeType[] = [")
            appendLine(ontology.nodeTypes.joinToString(separator = ",\n") { "  '${it.name}'" })
            appendLine("]")
            appendLine()
            appendLine("export type EdgeTypeName =")
            ontology.edgeTypes.forEach { appendLine("  | '${it.name}'") }
            appendLine()
            appendLine("export const EDGE_TYPES: readonly EdgeTypeName[] = [")
            appendLine(ontology.edgeTypes.joinToString(separator = ",\n") { "  '${it.name}'" })
            appendLine("]")
        }

    /** The exact payload `GET /api/v1/ontology` returns, usable as a test fixture. */
    fun json(ontology: GenOntology): String =
        buildString {
            appendLine("{")
            appendLine("""  "version": "${ontology.version}",""")
            appendLine("""  "nodeTypes": [""")
            ontology.nodeTypes.forEachIndexed { index, nodeType ->
                appendLine("    {")
                appendLine("""      "name": "${nodeType.name}",""")
                appendLine("""      "description": ${jsonString(nodeType.description)},""")
                appendLine("""      "identity": [${nodeType.identity.joinToString { "\"$it\"" }}],""")
                appendLine("""      "properties": [""")
                appendProperties(nodeType.properties, indent = "        ")
                appendLine("      ],")
                appendLine("""      "meta": ${nodeType.meta},""")
                appendLine("""      "displayProperty": ${jsonString(nodeType.displayProperty)},""")
                appendLine("""      "alias": [${nodeType.alias.joinToString { "\"$it\"" }}],""")
                appendLine("""      "mergeScope": [${nodeType.mergeScope.joinToString { "\"$it\"" }}],""")
                appendLine("""      "questions": [${nodeType.questions.joinToString { jsonString(it) }}],""")
                appendLine("""      "examples": [${nodeType.examples.joinToString { compact(it) }}]""")
                appendLine("    }${if (index == ontology.nodeTypes.lastIndex) "" else ","}")
            }
            appendLine("  ],")
            appendLine("""  "edgeTypes": [""")
            ontology.edgeTypes.forEachIndexed { index, edgeType ->
                appendLine("    {")
                appendLine("""      "name": "${edgeType.name}",""")
                appendLine("""      "description": ${jsonString(edgeType.description)},""")
                appendLine("""      "from": [${edgeType.from.joinToString { "\"$it\"" }}],""")
                appendLine("""      "to": [${edgeType.to.joinToString { "\"$it\"" }}],""")
                appendLine("""      "inverse": "${edgeType.inverse}",""")
                appendLine("""      "impact": "${edgeType.impact}",""")
                appendLine("""      "downstream": "${edgeType.downstream}",""")
                appendLine("""      "ownership": "${edgeType.ownership}",""")
                appendLine("""      "properties": [""")
                appendProperties(edgeType.properties, indent = "        ")
                appendLine("      ],")
                appendLine("""      "questions": [${edgeType.questions.joinToString { jsonString(it) }}],""")
                appendLine("""      "examples": [${edgeType.examples.joinToString { compact(it) }}]""")
                appendLine("    }${if (index == ontology.edgeTypes.lastIndex) "" else ","}")
            }
            appendLine("  ],")
            appendLine("""  "provenance": {""")
            appendLine("""    "properties": [""")
            appendProperties(ontology.provenance, indent = "      ")
            appendLine("    ]")
            appendLine("  },")
            appendLine("""  "sources": [""")
            ontology.sources.forEachIndexed { index, source ->
                val comma = if (index == ontology.sources.lastIndex) "" else ","
                appendLine("""    { "name": ${jsonString(source.name)}, "description": ${jsonString(source.description)} }$comma""")
            }
            appendLine("  ],")
            appendLine("""  "environments": [""")
            ontology.environments.forEachIndexed { index, environment ->
                val comma = if (index == ontology.environments.lastIndex) "" else ","
                appendLine(
                    """    { "name": ${jsonString(environment.name)}, "description": ${jsonString(environment.description)}, """ +
                        """"aliases": [${environment.aliases.joinToString { jsonString(it) }}] }$comma""",
                )
            }
            appendLine("  ]")
            appendLine("}")
        }

    private fun StringBuilder.appendProperties(
        properties: List<GenProperty>,
        indent: String,
    ) {
        properties.forEachIndexed { index, property ->
            val comma = if (index == properties.lastIndex) "" else ","
            // The enum is omitted rather than emitted as null, so a property without one produces
            // the same JSON it did before this existed.
            val enum =
                property.enum?.joinToString(prefix = """, "enum": [""", postfix = "]") { jsonString(it) }.orEmpty()
            // Likewise format, formatWhen and deprecated appear only where declared; examples always do.
            val format = property.format?.let { """, "format": ${jsonString(it)}""" }.orEmpty()
            val formatWhen = property.formatWhen.takeIf { it.isNotEmpty() }?.let { """, "formatWhen": ${compact(it)}""" }.orEmpty()
            val examples = """, "examples": [${property.examples.joinToString { compact(it) }}]"""
            val deprecated =
                property.deprecated
                    ?.let { """, "deprecated": { "since": ${jsonString(it.since)}, "replacedBy": ${jsonString(it.replacedBy)} }""" }
                    .orEmpty()
            appendLine(
                """$indent{ "name": "${property.name}", "type": "${property.type}", """ +
                    """"required": ${property.required}, "description": ${jsonString(property.description)}""" +
                    """$enum$format$formatWhen$examples$deprecated }$comma""",
            )
        }
    }

    /**
     * Every generated type already carries a structural `id`, so a registry property of the same
     * name would emit it twice and make the type uncompilable.
     */
    private fun GenNodeType.declaredBeyondStructural(): List<GenProperty> = properties.filterNot { it.name in STRUCTURAL }

    private val STRUCTURAL = setOf("id")

    private fun GenProperty.isEnum(): Boolean = type == "string" && !enum.isNullOrEmpty()

    /** `Deployment` + `status` is `DeploymentStatus`: the GraphQL enum and the TypeScript union alike. */
    private fun enumName(
        nodeType: GenNodeType,
        property: GenProperty,
    ): String = nodeType.name + property.name.replaceFirstChar { it.uppercaseChar() }

    /** A GraphQL enum value is a name, so `container-image` is spelt `container_image`. */
    private fun graphqlEnumValue(value: String): String = value.replace(Regex("[^_0-9A-Za-z]"), "_")

    private fun docstring(text: String): String = text.replace("\"\"\"", "\\\"\"\"")

    private fun deprecationReason(deprecation: GenDeprecation): String =
        "since ${deprecation.since}" + deprecation.replacedBy?.let { ", replaced by $it" }.orEmpty()

    /** Broken as Prettier breaks it: one line, else the union on the next line, else a value per line. */
    private fun typescriptUnion(
        name: String,
        values: List<String>,
    ): String {
        val union = values.joinToString(" | ") { "'$it'" }
        val oneLine = "export type $name = $union"
        return when {
            oneLine.length <= PRINT_WIDTH -> oneLine
            // Prettier's next choice: the whole union on the following line, indented.
            union.length + 2 <= PRINT_WIDTH -> "export type $name =\n  $union"
            else -> "export type $name =" + values.joinToString("") { "\n  | '$it'" }
        }
    }

    /** The description, the first example as JSON and any deprecation: what an editor shows on hover. */
    private fun StringBuilder.appendJsDoc(property: GenProperty) {
        val lines =
            listOfNotNull(
                property.description,
                property.examples.takeIf { it.isNotEmpty() }?.let { "@example ${compact(it.first())}" },
                property.deprecated?.let { "@deprecated ${deprecationReason(it)}" },
            )
        if (lines.isEmpty()) return
        appendLine("  /**")
        lines.forEach { appendLine("   * ${it.replace("*/", "*\\/")}") }
        appendLine("   */")
    }

    private fun compact(value: Any?): String = COMPACT.writeValueAsString(value)

    private fun jsonString(value: String?): String =
        if (value == null) "null" else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun graphqlType(property: GenProperty): String {
        val base =
            when (property.type) {
                "int" -> "Int"
                "float" -> "Float"
                "boolean" -> "Boolean"
                "string[]" -> "[String!]"
                else -> "String"
            }
        return if (property.required) "$base!" else base
    }

    private fun typescriptType(property: GenProperty): String =
        when (property.type) {
            "int", "float" -> "number"
            "boolean" -> "boolean"
            "string[]" -> "string[]"
            else -> "string"
        }
}
