package com.repodatagraph.domain.ontology

import com.repodatagraph.domain.model.Provenance

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
    /** The provenance envelope every node and edge carries, in declaration order (#114). */
    val provenance: List<PropertyDef> = emptyList(),
    /**
     * The source systems a write may name, in declaration order (#117). A registry that declares none
     * knows `manual` alone, which is what a write naming no source states.
     */
    val sources: List<SourceSystemDef> = listOf(SourceSystemDef(Provenance.MANUAL)),
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
        reject("the source registry", sourceProblems(sources))
    }

    private val sourceNames: Set<String> = sources.mapTo(linkedSetOf()) { it.name }

    /** The names of the source systems a write may name, in declaration order (#117). */
    fun knownSources(): List<String> = sourceNames.toList()

    /** Whether a write may name [name] as its source system: exactly, not by case or prefix. */
    fun isKnownSource(name: String): Boolean = name in sourceNames

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
        nodeType.displayProperty
            ?.takeIf { nodeType.property(it) == null }
            ?.let { problems += "displays '$it', which it does not declare" }
        // An alias finds a node written before it was known too, so it cannot be required, and it
        // stands beside the key rather than inside it (#88).
        nodeType.alias.forEach { name ->
            val property = nodeType.property(name)
            when {
                property == null -> problems += "alias references '$name', which it does not declare"
                name in nodeType.identity -> problems += "alias property '$name' is part of its identity"
                property.required -> problems += "alias property '$name' is required"
            }
        }

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
        // Ownership is inherited against the direction a change propagates, so an edge that does not
        // propagate has no direction to inherit along; and a downstream on it would say nothing.
        if (edgeType.impact == EdgeImpact.NONE && edgeType.ownership == EdgeOwnership.INHERITS) {
            problems += "inherits ownership but does not propagate impact, so it has no direction to inherit along"
        }
        if (edgeType.impact == EdgeImpact.NONE && edgeType.downstream != ImpactAlong.FORWARD) {
            problems += "declares a downstream direction but does not propagate impact"
        }

        reject("edge type '${edgeType.name}'", problems)
    }

    private companion object {
        val SEMVER = Regex("""^\d+\.\d+\.\d+$""")
    }
}

/** Reports every problem with [subject] at once, rather than only the first one found. */
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

/** What can follow `graph:write:` in a scope: no colon, no space, no capitals. */
private val SOURCE_NAME = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

/** Everything wrong with the declared source systems (#117), each as a sentence about the registry. */
private fun sourceProblems(sources: List<SourceSystemDef>): List<String> {
    val problems = mutableListOf<String>()
    sources
        .groupBy { it.name }
        .filterValues { it.size > 1 }
        .keys
        .forEach { problems += "declares source '$it' more than once" }
    sources
        .map { it.name }
        .filterNot { SOURCE_NAME.matches(it) }
        .forEach { problems += "declares source '$it', which is not lower-case words joined by hyphens and so cannot end a scope" }
    if (sources.none { it.name == Provenance.MANUAL }) {
        problems += "does not declare source '${Provenance.MANUAL}', which a write naming no source states"
    }
    return problems
}
