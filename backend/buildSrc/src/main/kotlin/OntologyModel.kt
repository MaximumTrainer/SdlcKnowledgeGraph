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
    /** The shape a string value takes (#81), one of [FormatRules.KNOWN]; null for none. */
    val format: String? = null,
    /** The format applies only where these sibling properties hold these values, e.g. provider aws. */
    val formatWhen: Map<String, String> = emptyMap(),
    /** Values the property accepts, the first shown wherever one example is. */
    val examples: List<Any?> = emptyList(),
    /** Set when the property is kept for old writers but should no longer be read or written. */
    val deprecated: GenDeprecation? = null,
)

/** Since which ontology version a property is deprecated, and the property or edge type that replaces it. */
data class GenDeprecation(
    val since: String,
    val replacedBy: String?,
)

data class GenNodeType(
    val name: String,
    val description: String?,
    val identity: List<String>,
    val properties: List<GenProperty>,
    val meta: Boolean = false,
    /** The property a node of this type is labelled with where it is drawn (#9); null for its key. */
    val displayProperty: String? = null,
    /** Properties that together find a node beside its key, unique where all are present (#88). */
    val alias: List<String> = emptyList(),
    /** Whole example nodes, each a set of properties the API would accept (#81). */
    val examples: List<Map<String, Any?>> = emptyList(),
    /** Questions a reader answers with this type, so an agent knows when to reach for it. */
    val questions: List<String> = emptyList(),
    /** Properties two nodes of this type must not disagree on to be merged (#98). */
    val mergeScope: List<String> = emptyList(),
)

data class GenEdgeType(
    val name: String,
    val description: String?,
    val from: List<String>,
    val to: List<String>,
    val inverse: String,
    val properties: List<GenProperty>,
    /** `propagates` when a change travels along this edge (#21), else `none`. */
    val impact: String = "none",
    /** `forward` or `inverse`: which way a change travels along a propagating edge. */
    val downstream: String = "forward",
    /** `owner`, `inherits` or `none`: what the edge says about ownership. */
    val ownership: String = "none",
    /** Example edge property sets (#81). */
    val examples: List<Map<String, Any?>> = emptyList(),
    val questions: List<String> = emptyList(),
)

/** A canonical environment name and the spellings folded into it (environments.yaml, #98). */
data class GenEnvironment(
    val name: String,
    val description: String?,
    val aliases: List<String>,
)

/** A source system a fact's provenance may name (sources.yaml, #117). */
data class GenSource(
    val name: String,
    val description: String?,
)

data class GenOntology(
    val version: String,
    val nodeTypes: List<GenNodeType>,
    val edgeTypes: List<GenEdgeType>,
    /** The provenance envelope every node and edge carries (provenance.yaml, #114). */
    val provenance: List<GenProperty> = emptyList(),
    /** The source systems a write may name (sources.yaml, #117), in declaration order. */
    val sources: List<GenSource> = emptyList(),
    /** The environment alias table (environments.yaml, #98), in declaration order. */
    val environments: List<GenEnvironment> = emptyList(),
)

object OntologyReader {
    private val yaml = YAMLMapper()

    // A key outside these is a typo or a feature that does not exist; either way it must fail the
    // build rather than be dropped, or the registry says something nothing reads (#81).
    private val NODE_KEYS =
        setOf("description", "identity", "alias", "mergeScope", "displayProperty", "meta", "properties", "examples", "questions")
    private val EDGE_KEYS =
        setOf(
            "description",
            "from",
            "to",
            "inverse",
            "impact",
            "downstream",
            "ownership",
            "properties",
            "examples",
            "questions",
        )
    private val PROPERTY_KEYS =
        setOf("name", "type", "required", "description", "enum", "format", "formatWhen", "examples", "deprecated")
    private val DEPRECATED_KEYS = setOf("since", "replacedBy")
    private val ENVIRONMENT_KEYS = setOf("name", "description", "aliases")

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
                    definition.requireOnly(NODE_KEYS, "nodes.$name")
                    GenNodeType(
                        name = name,
                        description = definition.text("description"),
                        identity = definition.path("identity").map { it.asText() },
                        properties = definition.readProperties("nodes.$name"),
                        meta = definition.path("meta").asBoolean(false),
                        displayProperty = definition.text("displayProperty"),
                        alias = definition.path("alias").map { it.asText() },
                        examples = definition.readExamples(),
                        questions = definition.path("questions").map { it.asText() },
                        mergeScope = definition.path("mergeScope").map { it.asText() },
                    )
                }.toList()

        val edges =
            yaml
                .readTree(baseDir.resolve("edges.yaml"))
                .path("edges")
                .fields()
                .asSequence()
                .map { (name, definition) ->
                    definition.requireOnly(EDGE_KEYS, "edges.$name")
                    GenEdgeType(
                        name = name,
                        description = definition.text("description"),
                        from = definition.path("from").map { it.asText() },
                        to = definition.path("to").map { it.asText() },
                        inverse = definition.path("inverse").asText(""),
                        properties = definition.readProperties("edges.$name"),
                        impact = definition.text("impact") ?: "none",
                        downstream = definition.text("downstream") ?: "forward",
                        ownership = definition.text("ownership") ?: "none",
                        examples = definition.readExamples(),
                        questions = definition.path("questions").map { it.asText() },
                    )
                }.toList()

        val provenanceFile = baseDir.resolve("provenance.yaml")
        val provenance =
            if (provenanceFile.exists()) yaml.readTree(provenanceFile).path("provenance").readProperties("provenance") else emptyList()

        val sourcesFile = baseDir.resolve("sources.yaml")
        val sources =
            if (sourcesFile.exists()) {
                yaml.readTree(sourcesFile).path("sources").map { GenSource(it.path("name").asText(), it.text("description")) }
            } else {
                emptyList()
            }

        val environmentsFile = baseDir.resolve("environments.yaml")
        val environments =
            if (environmentsFile.exists()) {
                val list = yaml.readTree(environmentsFile).path("environments")
                require(list.isArray) { "environments.yaml declares no 'environments' list" }
                list.map {
                    it.requireOnly(ENVIRONMENT_KEYS, "environments.${it.path("name").asText()}")
                    GenEnvironment(it.path("name").asText(), it.text("description"), it.path("aliases").map { alias -> alias.asText() })
                }
            } else {
                emptyList()
            }

        return GenOntology(version, nodes, edges, provenance, sources, environments)
    }

    private fun JsonNode.readProperties(owner: String): List<GenProperty> =
        path("properties").map { property ->
            val path = "$owner.properties.${property.path("name").asText()}"
            property.requireOnly(PROPERTY_KEYS, path)
            val deprecated =
                property.path("deprecated").takeIf { it.isObject }?.let {
                    it.requireOnly(DEPRECATED_KEYS, "$path.deprecated")
                    GenDeprecation(since = it.path("since").asText(), replacedBy = it.text("replacedBy"))
                }
            GenProperty(
                name = property.path("name").asText(),
                type = property.path("type").asText("string"),
                required = property.path("required").asBoolean(false),
                description = property.text("description"),
                enum = property.path("enum").takeIf { it.isArray }?.map { it.asText() },
                format = property.text("format"),
                formatWhen =
                    property
                        .path("formatWhen")
                        .properties()
                        .associate { (key, value) -> key to value.asText() },
                examples = property.path("examples").map { plain(it) },
                deprecated = deprecated,
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun JsonNode.readExamples(): List<Map<String, Any?>> = path("examples").map { plain(it) as Map<String, Any?> }

    /** A YAML value as the plain Kotlin value Jackson would bind it to, keeping key order. */
    private fun plain(node: JsonNode): Any? =
        when {
            node.isNull -> null
            node.isTextual -> node.asText()
            node.isBoolean -> node.asBoolean()
            node.isIntegralNumber -> node.asLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            node.isNumber -> node.asDouble()
            node.isArray -> node.map { plain(it) }
            node.isObject -> linkedMapOf<String, Any?>().apply { node.properties().forEach { (k, v) -> put(k, plain(v)) } }
            else -> node.asText()
        }

    private fun JsonNode.requireOnly(
        allowed: Set<String>,
        path: String,
    ) {
        val unknown = fieldNames().asSequence().filter { it !in allowed }.toList()
        require(unknown.isEmpty()) { "$path: unknown key ${unknown.joinToString()}; the registry reads only ${allowed.joinToString()}" }
    }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { !it.isMissingNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
}
