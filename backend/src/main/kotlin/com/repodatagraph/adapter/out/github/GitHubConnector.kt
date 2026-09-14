package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.DiscoveryResult
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.HealthStatus
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.domain.port.out.connector.SyncRequest
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * The first connector on the SPI, and the reference the others copy.
 *
 * Every run lists every repository, including an incremental one. Listing is one request per hundred
 * repositories; reading CODEOWNERS is one request per repository, and that is what the watermark
 * saves. Filtering the listing instead would be cheaper still and wrong: an archived repository's
 * `pushed_at` never moves again, so a connector that only looked at what changed would never see a
 * repository being retired, and the graph would keep asserting it for ever.
 */
@Component
class GitHubConnector(
    private val properties: GitHubProperties,
    private val client: GitHubClient,
    private val mapper: GitHubRepositoryMapper,
    private val clock: Clock,
) : SourceConnector {
    override fun descriptor() =
        ConnectorDescriptor(
            name = NAME,
            sourceSystem = NAME,
            nodeTypes = setOf("Repository", "Team"),
            edgeTypes = setOf("OWNED_BY"),
            // No WEBHOOK yet: the SPI refuses a capability that is only declared, and repository and
            // team events are their own piece of work (#23c).
            capabilities = setOf(Capability.FULL, Capability.INCREMENTAL, Capability.DISCOVERY),
        )

    override fun healthCheck(): HealthStatus =
        when {
            !properties.isConfigured() ->
                HealthStatus.down("connectors.github needs an org and a token before it can read anything")
            client.isReachable() -> HealthStatus.up("${properties.baseUrl} as org ${properties.org}")
            else -> HealthStatus.down("${properties.baseUrl} did not answer")
        }

    override fun discover(): DiscoveryResult =
        DiscoveryResult(
            scopes = listOf(properties.org),
            detail = mapOf("baseUrl" to properties.baseUrl, "configured" to properties.isConfigured()),
        )

    /**
     * One delta per page of repositories, so a run that fails halfway keeps what it had already read.
     *
     * The watermark is when the run started reading, not the latest `pushed_at` seen. A push that
     * lands mid-run is then read by the next run rather than skipped: its `pushed_at` is after the
     * watermark either way, and the alternative loses it silently.
     */
    override fun sync(request: SyncRequest): Sequence<GraphDelta> {
        require(properties.isConfigured()) {
            "connectors.github needs an org and a token before it can read anything"
        }
        val startedAt = Instant.now(clock)
        return client
            .repositories()
            .chunked(PAGE_SIZE)
            .map { page -> page.mapNotNull { repo -> delta(repo, request.since) }.merge(startedAt) }
    }

    /** Null for a repository this run has nothing to say about. */
    private fun delta(
        repo: GitHubRepo,
        since: Instant?,
    ): GraphDelta? {
        // An archive is never "unchanged": it is the one change that does not move pushed_at.
        if (!repo.archived && unchangedSince(repo, since)) return null
        // Not read for an archived repository: ownership of something retired is not worth a request
        // against the rate limit, and the tombstone closes the edges along with the node.
        val codeowners = if (repo.archived) Codeowners.NONE else client.codeowners(repo.name) ?: Codeowners.NONE
        return mapper.map(repo, codeowners)
    }

    /**
     * A repository GitHub says has not been touched since the watermark.
     *
     * No `pushed_at` at all counts as changed: GitHub cannot say when it last moved, and reading a
     * repository unnecessarily costs one request, while skipping one wrongly loses it until somebody
     * notices it is missing.
     */
    private fun unchangedSince(
        repo: GitHubRepo,
        since: Instant?,
    ): Boolean = since != null && repo.pushedAt != null && !repo.pushedAt.isAfter(since)

    private fun List<GraphDelta>.merge(watermark: Instant) =
        GraphDelta(
            nodes = flatMap { it.nodes },
            edges = flatMap { it.edges },
            tombstones = flatMap { it.tombstones },
            watermark = watermark,
        )

    private companion object {
        const val NAME = "github"

        /** One delta per page GitHub returns, so what is written matches what was read. */
        const val PAGE_SIZE = 100
    }
}
