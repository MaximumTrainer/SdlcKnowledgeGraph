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
    private val contentsMapper: RepositoryContentsMapper,
    private val clock: Clock,
) : SourceConnector {
    override fun descriptor() =
        ConnectorDescriptor(
            name = NAME,
            sourceSystem = NAME,
            nodeTypes = setOf("Repository", "Team", "Library", "IacFile"),
            edgeTypes = setOf("OWNED_BY", "DEPENDS_ON", "CONTAINS_IAC"),
            // No WEBHOOK yet: the SPI refuses a capability that is only declared, and repository and
            // team events are their own piece of work (#23c).
            capabilities = setOf(Capability.FULL, Capability.INCREMENTAL, Capability.DISCOVERY),
        )

    override fun healthCheck(): HealthStatus =
        when {
            !properties.isConfigured() -> HealthStatus.down(UNCONFIGURED)
            client.isReachable() -> HealthStatus.up("${properties.baseUrl} as ${properties.orgs.joinToString(", ")}")
            else -> HealthStatus.down("${properties.baseUrl} did not answer")
        }

    override fun discover(): DiscoveryResult =
        DiscoveryResult(
            scopes = properties.orgs,
            detail = mapOf("baseUrl" to properties.baseUrl, "configured" to properties.isConfigured()),
        )

    /**
     * Hands the run to a [GitHubSyncSession], which carries the state a run needs while it is going.
     *
     * The watermark is when the run started reading, not the latest `pushed_at` seen. A push that
     * lands mid-run is then read by the next run rather than skipped: its `pushed_at` is after the
     * watermark either way, and the alternative loses it silently.
     */
    override fun sync(request: SyncRequest): Sequence<GraphDelta> {
        require(properties.isConfigured()) { UNCONFIGURED }
        return GitHubSyncSession(
            client = client,
            repositoryMapper = mapper,
            contentsMapper = contentsMapper,
            properties = properties,
            since = request.since,
            watermark = Instant.now(clock),
        ).deltas()
    }

    private companion object {
        const val NAME = "github"
        const val UNCONFIGURED = "connectors.github needs at least one org and a token before it can read anything"
    }
}
