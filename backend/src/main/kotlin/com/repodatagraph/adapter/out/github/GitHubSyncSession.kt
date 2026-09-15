package com.repodatagraph.adapter.out.github

import com.repodatagraph.adapter.out.github.manifest.DeclaredDependency
import com.repodatagraph.adapter.out.github.manifest.DependencyScope
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import java.time.Instant

/** Some repositories could not be read. The run keeps what it read and records this. */
class PartialSyncException(
    val failures: List<String>,
) : RuntimeException("could not read ${failures.size} repositories: ${failures.take(FEW).joinToString("; ")}") {
    private companion object {
        const val FEW = 5
    }
}

/**
 * One run of the GitHub connector, and the state a run needs while it is going.
 *
 * Separate from the connector because a run is stateful and a connector is not. Which repository
 * publishes which package is only known once every repository has been read, so the run carries an
 * index as it goes and settles the dependencies that needed it at the end.
 */
class GitHubSyncSession(
    private val client: GitHubClient,
    private val reader: RepositoryReader,
    private val repositoryMapper: GitHubRepositoryMapper,
    private val contentsMapper: RepositoryContentsMapper,
    private val properties: GitHubProperties,
    private val since: Instant?,
    private val watermark: Instant,
) {
    /** Package name to the repository that publishes it, built up as repositories are read. */
    private val publishedBy = mutableMapOf<String, NodeKey>()
    private val pending = mutableListOf<PendingInternalDependency>()
    private val failures = mutableListOf<String>()

    /**
     * One delta per repository, then one more for the dependencies that were waiting on the whole
     * picture.
     *
     * A delta per repository rather than per page, so that a repository nobody can read costs that
     * repository rather than the hundred either side of it. The run is still marked partial: the
     * failure is raised after the last delta, so everything readable is written first and the reason
     * is on the run record.
     */
    fun deltas(): Sequence<GraphDelta> =
        sequence {
            properties.orgs.filter { it.isNotBlank() }.forEach { org ->
                client.repositories(org).forEach { repo ->
                    repositoryDelta(org, repo)?.let { yield(it) }
                }
            }
            yield(internalDependencies())
            if (failures.isNotEmpty()) throw PartialSyncException(failures.toList())
        }

    private fun repositoryDelta(
        org: String,
        repo: GitHubRepo,
    ): GraphDelta? {
        // An archive is never "unchanged": it is the one change that does not move pushed_at.
        if (!repo.archived && unchangedSince(repo)) return null

        val read = reader.read(org, repo)
        read.publishes.forEach { publishedBy[it.lowercase()] = repositoryMapper.keyOf(repo) }
        pending += read.pending
        read.failure?.let { failures += it }

        return GraphDelta(watermark = watermark) + read.delta
    }

    /**
     * The dependencies that looked internal, settled now that every repository has been read.
     *
     * A package name matching this organisation's prefix but published by no repository here is
     * recorded as an ordinary library after all. It is more likely to be a package we publish from
     * somewhere this connector cannot see than a dependency that does not exist, and dropping it
     * would lose the dependency entirely.
     */
    private fun internalDependencies(): GraphDelta {
        val resolved = pending.mapNotNull { dependency -> publishedBy[dependency.packageName.lowercase()]?.let { dependency to it } }
        val unresolved = pending - resolved.map { it.first }.toSet()

        return GraphDelta(
            nodes =
                unresolved.map { contentsMapper.libraryNode(it.asDeclared(), it.observedAt) },
            edges =
                resolved.map { (dependency, target) -> repositoryEdge(dependency, target) } +
                    unresolved.map { contentsMapper.libraryEdge(it.from, it.manifest, it.asDeclared(), it.observedAt) },
            watermark = watermark,
        )
    }

    /**
     * A guess, and recorded as one.
     *
     * This edge rests on a package name matching a naming convention, not on anything GitHub said.
     * Marking it inferred is what lets a reviewer tell it from a dependency read out of a manifest -
     * and what stops a convention changing from quietly rewriting history as fact.
     */
    private fun repositoryEdge(
        dependency: PendingInternalDependency,
        target: NodeKey,
    ) = EdgeUpsert(
        type = "DEPENDS_ON",
        from = dependency.from,
        to = target,
        props =
            buildMap {
                put("kind", "library")
                put("manifest", dependency.manifest)
                put("scope", dependency.scope)
                dependency.version?.let { put("version", it) }
            },
        observedAt = dependency.observedAt,
        confidence = INFERRED_CONFIDENCE,
        inferred = true,
    )

    private fun PendingInternalDependency.asDeclared() =
        DeclaredDependency(
            ecosystem = ecosystem,
            name = packageName,
            version = version,
            scope = if (scope == "dev") DependencyScope.DEV else DependencyScope.RUNTIME,
        )

    /**
     * A repository GitHub says has not been touched since the watermark.
     *
     * No `pushed_at` at all counts as changed: GitHub cannot say when it last moved, and reading a
     * repository unnecessarily costs a few requests, while skipping one wrongly loses it until
     * somebody notices it is missing.
     */
    private fun unchangedSince(repo: GitHubRepo): Boolean = since != null && repo.pushedAt != null && !repo.pushedAt.isAfter(since)

    private companion object {
        const val DEFAULT_BRANCH = "main"

        /**
         * High enough to act on, low enough to tell from a fact. A naming convention is a good guess
         * and not a statement, and the difference has to survive into the graph.
         */
        const val INFERRED_CONFIDENCE = 0.9
    }
}
