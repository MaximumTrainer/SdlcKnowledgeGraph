package com.repodatagraph.domain.ontology

/**
 * The declared contract for what may exist in the graph.
 *
 * Everything that needs to know the shape of the model reads it from here rather than restating it:
 * the store builds Cypher from these labels, the API validates writes against these properties, and
 * the published schema and frontend types are generated from them. The registry is validated on
 * construction, so an ontology that cannot be traversed sensibly fails at startup rather than
 * producing empty query results later.
 */
class OntologyRegistry(
    val version: String,
    nodeTypes: List<NodeTypeDef>,
    edgeTypes: List<EdgeTypeDef>,
) {
    private val nodesByName: Map<String, NodeTypeDef>
    private val edgesByName: Map<String, EdgeTypeDef>

    init {
        require(SEMVER.matches(version)) {
            throw InvalidOntologyException("ontology version '$version' is not semver")
        }
        nodesByName = nodeTypes.associateByUnique { it.name }
        edgesByName = edgeTypes.associateByUnique { it.name }
        nodeTypes.forEach(::validateNodeType)
        edgeTypes.forEach(::validateEdgeType)
    }

    fun nodeType(name: String): NodeTypeDef? = nodesByName[name]

    fun edgeType(name: String): EdgeTypeDef? = edgesByName[name]

    fun allNodeTypes(): List<NodeTypeDef> = nodesByName.values.toList()

    fun allEdgeTypes(): List<EdgeTypeDef> = edgesByName.values.toList()

    fun isKnownNodeType(name: String): Boolean = nodesByName.containsKey(name)

    /** The inverse traversal name for [edgeName], or null when the edge is unknown. */
    fun inverseOf(edgeName: String): String? = edgesByName[edgeName]?.inverse

    /** Reports every problem with a type at once, rather than only the first one found. */
    private fun validateNodeType(nodeType: NodeTypeDef) {
        val problems = mutableListOf<String>()

        if (nodeType.identity.isEmpty()) {
            problems += "declares no identity, so its nodes cannot be addressed"
        }
        nodeType.properties
            .groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys
            .forEach { problems += "declares property '$it' more than once" }
        nodeType.identity
            .filter { nodeType.property(it) == null }
            .forEach { problems += "identity references '$it', which it does not declare" }

        reject("node type '${nodeType.name}'", problems)
    }

    private fun validateEdgeType(edgeType: EdgeTypeDef) {
        val problems = mutableListOf<String>()

        if (edgeType.inverse.isBlank()) {
            problems += "declares no inverse, so it cannot be traversed backwards"
        }
        if (edgeType.from.isEmpty() || edgeType.to.isEmpty()) {
            problems += "must declare both from and to"
        }
        (edgeType.from + edgeType.to)
            .filterNot { nodesByName.containsKey(it) }
            .forEach { problems += "references undeclared node type '$it'" }

        reject("edge type '${edgeType.name}'", problems)
    }

    private fun reject(
        subject: String,
        problems: List<String>,
    ) {
        if (problems.isNotEmpty()) {
            throw InvalidOntologyException(problems.joinToString(separator = "; ") { "$subject $it" })
        }
    }

    private fun <T> List<T>.associateByUnique(key: (T) -> String): Map<String, T> {
        val result = LinkedHashMap<String, T>(size)
        forEach { item ->
            val name = key(item)
            if (result.put(name, item) != null) {
                throw InvalidOntologyException("ontology declares '$name' more than once")
            }
        }
        return result
    }

    private companion object {
        val SEMVER = Regex("""^\d+\.\d+\.\d+$""")
    }
}
