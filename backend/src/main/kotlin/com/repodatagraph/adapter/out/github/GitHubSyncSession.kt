package com.repodatagraph.adapter.out.github

import com.repodatagraph.adapter.out.github.manifest.DeclaredDependency
import com.repodatagraph.adapter.out.github.manifest.DependencyScope
import com.repodatagraph.adapter.out.github.manifest.UnreadableManifestException
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import org.slf4j.LoggerFactory
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
    private val repositoryMapper: GitHubRepositoryMapper,
    private val contentsMapper: RepositoryContentsMapper,
    private val properties: GitHubProperties,
    private val since: Instant?,
    private val watermark: Instant,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        return GraphDelta(watermark = watermark) + read(org, repo)
    }

    /**
     * Everything one repository has to say.
     *
     * An archived repository is read no further: ownership and dependencies of something retired are
     * not worth requests against the rate limit, and the tombstone closes its edges along with it.
     */
    private fun read(
        org: String,
        repo: GitHubRepo,
    ): GraphDelta {
        if (repo.archived) return repositoryMapper.map(repo, Codeowners.NONE)

        val codeowners = client.codeowners(org, repo.name) ?: Codeowners.NONE
        val key = repositoryMapper.keyOf(repo)
        val contents = contentsOf(org, repo, key)

        contents.publishes.forEach { publishedBy[it.lowercase()] = key }
        pending += contents.pending

        return repositoryMapper.map(repo, codeowners, contents.publishes) + contents.delta
    }

    /**
     * What the files said, or nothing at all if one of them could not be read.
     *
     * The repository itself is still recorded either way. A repository with a malformed manifest is
     * a repository we know about whose dependencies we do not - which is a more useful thing for the
     * graph to say than the repository being absent altogether.
     */
    private fun contentsOf(
        org: String,
        repo: GitHubRepo,
        key: NodeKey,
    ): RepositoryContents =
        try {
            contentsMapper.map(key, interestingFiles(org, repo), repo.pushedAt)
        } catch (unreadable: UnreadableManifestException) {
            failures += "${repo.fullName}: ${unreadable.message}"
            log.warn("could not read {} in {}", unreadable.path, repo.fullName)
            RepositoryContents()
        }

    /**
     * The files worth fetching, listed once and then fetched one by one.
     *
     * The listing is a single request for the whole branch; fetching is a request per file, which is
     * why only files something can actually read are fetched at all.
     */
    private fun interestingFiles(
        org: String,
        repo: GitHubRepo,
    ): Map<String, String> {
        if (!properties.manifests.enabled && !properties.iac.enabled) return emptyMap()
        val branch = repo.defaultBranch ?: DEFAULT_BRANCH
        return client
            .tree(org, repo.name, branch)
            .filter { contentsMapper.isInteresting(it.path) }
            .filter { entry ->
                // A manifest is kilobytes. A file of megabytes with a manifest's name is something
                // else, and reading it costs the run more than it is worth.
                val small = entry.size <= properties.manifests.maxFileBytes
                if (!small) log.info("skipping {} in {}: {} bytes", entry.path, repo.fullName, entry.size)
                small
            }.mapNotNull { entry -> client.file(org, repo.name, entry.path)?.let { entry.path to it } }
            .toMap()
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

    private operator fun GraphDelta.plus(other: GraphDelta) =
        GraphDelta(
            nodes = nodes + other.nodes,
            edges = edges + other.edges,
            tombstones = tombstones + other.tombstones,
            watermark = watermark ?: other.watermark,
        )

    private companion object {
        const val DEFAULT_BRANCH = "main"

        /**
         * High enough to act on, low enough to tell from a fact. A naming convention is a good guess
         * and not a statement, and the difference has to survive into the graph.
         */
        const val INFERRED_CONFIDENCE = 0.9
    }
}
