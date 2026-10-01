package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.springframework.stereotype.Component

/** What resolving a set of dependencies produced, and how many were held back for a later run. */
data class Resolution(
    val delta: GraphDelta,
    val deferred: Int = 0,
)

/**
 * Package name to repository (#86, FR-4).
 *
 * Two kinds of claim come out of here and they are not the same kind. A library read from a manifest
 * is reported: the file says it, at full confidence, and the edge names the file so anyone can go and
 * check. A dependency on another repository is inferred: it rests on a package name matching what
 * that repository publishes, not on anything GitHub said, and it is recorded as a guess so that a
 * reviewer can tell it from a fact - and so that a naming change does not quietly rewrite history as
 * truth. Nothing beyond what a manifest states outright is inferred here; that is the link engine's
 * (#28).
 */
@Component
class DependencyResolver(
    private val identityResolver: IdentityResolver,
) {
    /**
     * @param deferUnresolvedInternal hold back, rather than record as a library, a dependency named
     *   like something this organisation publishes that nothing is known to publish. For a webhook,
     *   which has read one repository; a run that has read them all passes false.
     */
    fun resolve(
        dependencies: List<PendingDependency>,
        publishers: PackagePublishers,
        deferUnresolvedInternal: Boolean = false,
    ): Resolution {
        val nodes = mutableListOf<NodeUpsert>()
        val edges = mutableListOf<EdgeUpsert>()
        var deferred = 0

        dependencies.forEach { dependency ->
            val publisher = publishers.publisherOf(dependency.packageName)
            when {
                // A repository depending on a package it publishes itself is a workspace, not an edge.
                publisher == dependency.from -> Unit
                publisher != null -> edges += repositoryEdge(dependency, publisher)
                deferUnresolvedInternal && dependency.internal -> deferred++
                else -> {
                    nodes += libraryNode(dependency)
                    edges += libraryEdge(dependency)
                }
            }
        }
        // One edge per pair, the first manifest to declare it: two manifests naming the same package
        // would otherwise take turns overwriting one edge, and a run over an unchanged estate would
        // report it written every time.
        return Resolution(
            GraphDelta(nodes = nodes.distinctBy { it.props }, edges = edges.distinctBy { Triple(it.type, it.from, it.to) }),
            deferred,
        )
    }

    /**
     * A guess, and recorded as one: high enough to act on, low enough to tell from a fact.
     */
    private fun repositoryEdge(
        dependency: PendingDependency,
        target: NodeKey,
    ) = EdgeUpsert(
        type = DEPENDS_ON,
        from = dependency.from,
        to = target,
        props = props(dependency),
        observedAt = dependency.observedAt,
        confidence = INFERRED_CONFIDENCE,
        inferred = true,
        sourceId = evidence(dependency),
    )

    private fun libraryNode(dependency: PendingDependency) =
        NodeUpsert(
            type = LIBRARY,
            props = libraryProps(dependency),
            observedAt = dependency.observedAt,
            // The package as its ecosystem names it, which is all a manifest says about it.
            sourceId = "${dependency.ecosystem}:${dependency.packageName}",
        )

    private fun libraryEdge(dependency: PendingDependency) =
        EdgeUpsert(
            type = DEPENDS_ON,
            from = dependency.from,
            to = identityResolver.keyFor(LIBRARY, libraryProps(dependency)),
            props = props(dependency),
            observedAt = dependency.observedAt,
            sourceId = evidence(dependency),
        )

    private fun libraryProps(dependency: PendingDependency) = mapOf("ecosystem" to dependency.ecosystem, "name" to dependency.packageName)

    private fun props(dependency: PendingDependency) =
        buildMap {
            put("kind", "library")
            put("manifest", dependency.manifest)
            put("scope", dependency.scope)
            dependency.version?.let { put("version", it) }
        }

    /** The file the dependency was read from, in the repository GitHub named. */
    private fun evidence(dependency: PendingDependency) = "${dependency.source}:${dependency.manifest}"

    private companion object {
        const val LIBRARY = "Library"
        const val DEPENDS_ON = "DEPENDS_ON"
        const val INFERRED_CONFIDENCE = 0.9
    }
}
