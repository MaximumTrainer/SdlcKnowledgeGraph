/**
 * Renders the ontology into the forms other layers need, so a node type is declared once.
 *
 * Output is deterministic: types in registry order, properties in declaration order, LF endings and
 * no timestamps. That is what lets the drift check compare generated output against committed files
 * byte for byte and treat a difference as an error rather than noise.
 */
object OntologyCodegen {
    private const val HEADER = "GENERATED FROM ontology/v1 - DO NOT EDIT"

    fun graphqlSdl(ontology: GenOntology): String =
        buildString {
            appendLine("# $HEADER")
            appendLine("# Types are generated from the ontology registry. Queries and mutations live in schema.graphqls.")
            appendLine()
            appendLine("\"\"\"Where a fact came from, attached to every node and edge.\"\"\"")
            appendLine("type Provenance {")
            ontology.provenance.forEach { appendLine("  ${it.name}: ${graphqlType(it)}") }
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
                nodeType.description?.let { appendLine("\"\"\"$it\"\"\"") }
                appendLine("type ${nodeType.name}Node implements GraphNode {")
                appendLine("  id: ID!")
                appendLine("  key: String!")
                nodeType.declaredBeyondStructural().forEach { property ->
                    appendLine("  ${property.name}: ${graphqlType(property)}")
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
                nodeType.description?.let { appendLine("/** $it */") }
                appendLine("export interface ${nodeType.name} {")
                appendLine("  id: string")
                nodeType.declaredBeyondStructural().forEach { property ->
                    val optional = if (property.required) "" else "?"
                    appendLine("  ${property.name}$optional: ${typescriptType(property)}")
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
                appendLine("""      "meta": ${nodeType.meta}""")
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
                appendLine("""      "properties": [""")
                appendProperties(edgeType.properties, indent = "        ")
                appendLine("      ]")
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
            appendLine(
                """$indent{ "name": "${property.name}", "type": "${property.type}", """ +
                    """"required": ${property.required}, "description": ${jsonString(property.description)}$enum }$comma""",
            )
        }
    }

    /**
     * Every generated type already carries a structural `id`, so a registry property of the same
     * name would emit it twice and make the type uncompilable.
     */
    private fun GenNodeType.declaredBeyondStructural(): List<GenProperty> = properties.filterNot { it.name in STRUCTURAL }

    private val STRUCTURAL = setOf("id")

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
