package com.repodatagraph.adapter.out.github

import com.repodatagraph.adapter.out.github.manifest.UnreadableManifestException
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.plus
import com.repodatagraph.observability.LogEvents
import org.springframework.stereotype.Component

/** Everything one repository has to say, and the things a caller has to decide about. */
data class RepositoryRead(
    /** The repository, its owners, pipelines and IaC evidence: what needs nothing else to be written. */
    val delta: GraphDelta,
    /** The key the repository is stored under. */
    val key: NodeKey? = null,
    /** Names this repository publishes, for resolving other repositories' dependencies onto it. */
    val publishes: List<String> = emptyList(),
    /** Every dependency its manifests declare, resolved once the whole picture is known. */
    val dependencies: List<PendingDependency> = emptyList(),
    /** Whether GitHub says it is a fork, which keeps what it publishes out of resolution (#86, FR-7). */
    val fork: Boolean = false,
    /** Set when a file was there but could not be read. The repository is still described. */
    val failure: String? = null,
)

/**
 * Reads one repository: what GitHub says about it, who owns it, which pipelines it defines, and what
 * is inside it.
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
    private val pipelines: WorkflowPipelines,
    private val properties: GitHubProperties,
) {
    /**
     * An archived repository is read no further: ownership, pipelines and dependencies of something
     * retired are not worth requests against the rate limit, and the tombstone closes its edges along
     * with it.
     */
    fun read(
        org: String,
        repo: GitHubRepo,
    ): RepositoryRead {
        val key = repositoryMapper.keyOf(repo)
        val forkOf = forkOf(org, repo)
        if (repo.archived) {
            return RepositoryRead(
                delta = repositoryMapper.map(repo, Codeowners.NONE, forkOf = forkOf),
                key = key,
                fork = repo.fork,
            )
        }

        val codeowners = client.codeowners(org, repo.name) ?: Codeowners.NONE
        val tree = tree(org, repo)
        val workflows =
            pipelines.map(
                key,
                repo.fullName,
                if (properties.pipelines.enabled) tree.map { it.path } else emptyList(),
                repo.pushedAt,
            )

        return runCatching { contentsMapper.map(key, interestingFiles(org, repo, tree), repo.pushedAt, repo.fullName) }
            .fold(
                onSuccess = { contents ->
                    RepositoryRead(
                        delta = repositoryMapper.map(repo, codeowners, contents.publishes, forkOf) + workflows + contents.delta,
                        key = key,
                        publishes = contents.publishes,
                        dependencies = contents.dependencies,
                        fork = repo.fork,
                    )
                },
                onFailure = { failure ->
                    val unreadable = failure as? UnreadableManifestException ?: throw failure
                    LogEvents.githubFileUnreadable(unreadable.path, repo.fullName)
                    // The repository itself is still recorded. One with a malformed manifest is a
                    // repository we know about whose dependencies we do not, which is more useful
                    // for the graph to say than the repository being absent altogether.
                    RepositoryRead(
                        delta = repositoryMapper.map(repo, codeowners, forkOf = forkOf) + workflows,
                        key = key,
                        fork = repo.fork,
                        failure = "${repo.fullName}: ${unreadable.message}",
                    )
                },
            )
    }

    /**
     * What a fork was forked from. The org listing says a repository is a fork and not of what, so a
     * fork costs one more request, to read it back; a repository read back already says.
     */
    private fun forkOf(
        org: String,
        repo: GitHubRepo,
    ): NodeKey? {
        if (!repo.fork) return null
        val parent = repo.parent ?: client.repository(org, repo.name)?.parent
        return parent?.htmlUrl?.let(repositoryMapper::keyOfUrl)
    }

    /**
     * The branch's file listing, once, when anything reads it. One request for the whole branch,
     * shared by the pipelines, which need only the paths, and the manifests and IaC files, which are
     * then fetched one by one.
     */
    private fun tree(
        org: String,
        repo: GitHubRepo,
    ): List<GitHubTreeEntry> {
        val needed = properties.manifests.enabled || properties.iac.enabled || properties.pipelines.enabled
        return if (needed) client.tree(org, repo.name, repo.defaultBranch ?: DEFAULT_BRANCH) else emptyList()
    }

    /** The files worth fetching: only those something can actually read, and not too large. */
    private fun interestingFiles(
        org: String,
        repo: GitHubRepo,
        tree: List<GitHubTreeEntry>,
    ): Map<String, String> =
        tree
            .filter { contentsMapper.isInteresting(it.path) }
            .filter { entry ->
                // A manifest is kilobytes. A file of megabytes with a manifest's name is something
                // else, and reading it costs the run more than it is worth.
                val small = entry.size <= properties.manifests.maxFileBytes
                if (!small) LogEvents.githubFileSkipped(entry.path, repo.fullName, entry.size.toInt())
                small
            }.mapNotNull { entry -> client.file(org, repo.name, entry.path)?.let { entry.path to it } }
            .toMap()

    private companion object {
        const val DEFAULT_BRANCH = "main"
    }
}
