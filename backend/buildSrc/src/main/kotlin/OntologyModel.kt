import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import java.io.File

/**
 * A build-time reading of the ontology YAML.
 *
 * This deliberately duplicates a little of the runtime loader rather than depending on the
 * application: buildSrc is compiled before the project it builds, so it cannot import from it. The
 * drift check is what keeps the two readings honest, because a disagreement shows up as generated
 * output that no longer matches the committed files.
 */
data class GenProperty(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String?,
    /** Declared allowed values, published so a form can offer them rather than guess. */
    val enum: List<String>? = null,
)

data class GenNodeType(
    val name: String,
    val description: String?,
    val identity: List<String>,
    val properties: List<GenProperty>,
)

data class GenEdgeType(
    val name: String,
    val description: String?,
    val from: List<String>,
    val to: List<String>,
    val inverse: String,
    val properties: List<GenProperty>,
)

data class GenOntology(
    val version: String,
    val nodeTypes: List<GenNodeType>,
    val edgeTypes: List<GenEdgeType>,
)

object OntologyReader {
    private val yaml = YAMLMapper()

    fun read(baseDir: File): GenOntology {
        val version =
            yaml
                .readTree(baseDir.resolve("version.yaml"))
                .path("version")
                .asText()
                .also { require(it.isNotBlank()) { "version.yaml declares no version" } }

        val nodes =
            yaml
                .readTree(baseDir.resolve("nodes.yaml"))
                .path("nodes")
                .fields()
                .asSequence()
                .map { (name, definition) ->
                    GenNodeType(
                        name = name,
                        description = definition.text("description"),
                        identity = definition.path("identity").map { it.asText() },
                        properties = definition.readProperties(),
                    )
                }.toList()

        val edges =
            yaml
                .readTree(baseDir.resolve("edges.yaml"))
                .path("edges")
                .fields()
                .asSequence()
                .map { (name, definition) ->
                    GenEdgeType(
                        name = name,
                        description = definition.text("description"),
                        from = definition.path("from").map { it.asText() },
                        to = definition.path("to").map { it.asText() },
                        inverse = definition.path("inverse").asText(""),
                        properties = definition.readProperties(),
                    )
                }.toList()

        return GenOntology(version, nodes, edges)
    }

    private fun JsonNode.readProperties(): List<GenProperty> =
        path("properties").map { property ->
            GenProperty(
                name = property.path("name").asText(),
                type = property.path("type").asText("string"),
                required = property.path("required").asBoolean(false),
                description = property.text("description"),
                enum = property.path("enum").takeIf { it.isArray }?.map { it.asText() },
            )
        }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { !it.isMissingNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
}
