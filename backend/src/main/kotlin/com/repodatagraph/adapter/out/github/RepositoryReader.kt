package com.repodatagraph.adapter.out.github

import com.repodatagraph.adapter.out.github.manifest.UnreadableManifestException
import com.repodatagraph.domain.port.out.connector.GraphDelta
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Everything one repository has to say, and the two things a caller has to decide about. */
data class RepositoryRead(
    val delta: GraphDelta,
    /** Names this repository publishes, for resolving other repositories' dependencies onto it. */
    val publishes: List<String> = emptyList(),
    /** Dependencies that look internal and need the whole picture to settle. */
    val pending: List<PendingInternalDependency> = emptyList(),
    /** Set when a file was there but could not be read. The repository is still described. */
    val failure: String? = null,
)

/**
 * Reads one repository: what GitHub says about it, who owns it, and what is inside it.
 *
 * Shared by the scheduled sync and by webhooks, because a repository does not become a different
 * thing depending on what prompted the read. Two paths would drift - one would learn about a new
 * manifest format or a new IaC extension and the other would not - and the drift would show up as a
 * graph whose contents depend on whether a webhook or a schedule got there first.
 */
@Component
class RepositoryReader(
    private val client: GitHubClient,
    private val repositoryMapper: GitHubRepositoryMapper,
    private val contentsMapper: RepositoryContentsMapper,
    private val properties: GitHubProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * An archived repository is read no further: ownership and dependencies of something retired are
     * not worth requests against the rate limit, and the tombstone closes its edges along with it.
     */
    fun read(
        org: String,
        repo: GitHubRepo,
    ): RepositoryRead {
        if (repo.archived) return RepositoryRead(delta = repositoryMapper.map(repo, Codeowners.NONE))

        val codeowners = client.codeowners(org, repo.name) ?: Codeowners.NONE
        val key = repositoryMapper.keyOf(repo)

        return runCatching { contentsMapper.map(key, interestingFiles(org, repo), repo.pushedAt) }
            .fold(
                onSuccess = { contents ->
                    RepositoryRead(
                        delta = repositoryMapper.map(repo, codeowners, contents.publishes) + contents.delta,
                        publishes = contents.publishes,
                        pending = contents.pending,
                    )
                },
                onFailure = { failure ->
                    val unreadable = failure as? UnreadableManifestException ?: throw failure
                    log.warn("could not read {} in {}", unreadable.path, repo.fullName)
                    // The repository itself is still recorded. One with a malformed manifest is a
                    // repository we know about whose dependencies we do not, which is more useful
                    // for the graph to say than the repository being absent altogether.
                    RepositoryRead(
                        delta = repositoryMapper.map(repo, codeowners),
                        failure = "${repo.fullName}: ${unreadable.message}",
                    )
                },
            )
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
        return client
            .tree(org, repo.name, repo.defaultBranch ?: DEFAULT_BRANCH)
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

    private companion object {
        const val DEFAULT_BRANCH = "main"
    }
}

/** Two deltas as one. Kept here so both callers of [RepositoryReader] combine them the same way. */
internal operator fun GraphDelta.plus(other: GraphDelta) =
    GraphDelta(
        nodes = nodes + other.nodes,
        edges = edges + other.edges,
        tombstones = tombstones + other.tombstones,
        watermark = watermark ?: other.watermark,
    )
