package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.PartialReadException
import com.repodatagraph.domain.port.out.connector.PublishedPackageIndex
import com.repodatagraph.domain.port.out.connector.plus
import org.springframework.stereotype.Component
import java.time.Instant

/** Some repositories could not be read. The run keeps what it read, records this, and counts each. */
class PartialSyncException(
    failures: List<String>,
) : PartialReadException(failures, "could not read ${failures.size} repositories: ${failures.take(FEW).joinToString("; ")}") {
    private companion object {
        const val FEW = 5
    }
}

/**
 * One run of the GitHub connector, and the state a run needs while it is going.
 *
 * Separate from the connector because a run is stateful and a connector is not. Whether a dependency
 * is a library or another repository here is only known once every repository has said what it
 * publishes (#86, FR-4), so the run carries an index as it goes and settles every dependency at the
 * end, against what it read and what the graph already knew.
 */
class GitHubSyncSession(
    private val client: GitHubClient,
    private val reader: RepositoryReader,
    private val repositoryMapper: GitHubRepositoryMapper,
    private val resolver: DependencyResolver,
    private val publishedPackages: PublishedPackageIndex,
    private val properties: GitHubProperties,
    private val since: Instant?,
    private val watermark: Instant,
) {
    /** Package name and the repository that publishes it, as this run reads them. */
    private val published = mutableListOf<Pair<String, NodeKey>>()
    private val read = mutableSetOf<NodeKey>()
    private val dependencies = mutableListOf<PendingDependency>()
    private val failures = mutableListOf<String>()

    /**
     * One delta per repository, then one per repository whose dependencies were waiting on the whole
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
            yieldAll(dependencyDeltas())
            if (failures.isNotEmpty()) throw PartialSyncException(failures.toList())
        }

    private fun repositoryDelta(
        org: String,
        repo: GitHubRepo,
    ): GraphDelta? {
        // An archive is never "unchanged": it is the one change that does not move pushed_at.
        if (!repo.archived && unchangedSince(repo)) return null

        val repository = reader.read(org, repo)
        val key = repository.key ?: repositoryMapper.keyOf(repo)
        read += key
        // A fork carries its upstream's manifest, so what it "publishes" is its upstream's (#86, FR-7).
        if (!repository.fork || properties.manifests.resolveToForks) {
            repository.publishes.forEach { published += it to key }
        }
        dependencies += repository.dependencies
        repository.failure?.let { failures += it }

        return GraphDelta(watermark = watermark) + repository.delta
    }

    /**
     * Every dependency read, settled now that every repository has been read: to the repository that
     * publishes it, by what this run read and otherwise by what the graph remembers, or else to the
     * library it names. Still a delta per repository, so one that cannot be written costs only its own
     * dependencies.
     */
    private fun dependencyDeltas(): Sequence<GraphDelta> {
        // Still one page, so a run that found nothing changed still says where it got to.
        if (dependencies.isEmpty()) return sequenceOf(GraphDelta(watermark = watermark))
        val publishers =
            PackagePublishers.combine(
                run = PackagePublishers.of(published),
                graph = PackagePublishers.fromGraph(publishedPackages.publishedPackages(properties.manifests.resolveToForks)),
                readInRun = read,
            )
        return dependencies
            .groupBy { it.from }
            .values
            .asSequence()
            .map { declared -> resolver.resolve(declared, publishers).delta.copy(watermark = watermark) }
    }

    /**
     * A repository GitHub says has not been touched since the watermark.
     *
     * No `pushed_at` at all counts as changed: GitHub cannot say when it last moved, and reading a
     * repository unnecessarily costs a few requests, while skipping one wrongly loses it until
     * somebody notices it is missing.
     */
    private fun unchangedSince(repo: GitHubRepo): Boolean = since != null && repo.pushedAt != null && !repo.pushedAt.isAfter(since)
}

/** Opens a [GitHubSyncSession] with the collaborators every run needs. */
@Component
class GitHubSyncSessions(
    private val client: GitHubClient,
    private val reader: RepositoryReader,
    private val repositoryMapper: GitHubRepositoryMapper,
    private val resolver: DependencyResolver,
    private val publishedPackages: PublishedPackageIndex,
    private val properties: GitHubProperties,
) {
    fun open(
        since: Instant?,
        watermark: Instant,
    ) = GitHubSyncSession(client, reader, repositoryMapper, resolver, publishedPackages, properties, since, watermark)
}
