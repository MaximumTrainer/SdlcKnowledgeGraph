package com.repodatagraph.adapter.out.dogfoodseed

import com.repodatagraph.application.ingest.DeploymentReports
import com.repodatagraph.application.ingest.ParsedSeed
import com.repodatagraph.application.ingest.SeedBatchParser
import com.repodatagraph.application.ingest.SeedIngestService
import com.repodatagraph.config.IngestProperties
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.DiscoveryResult
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.HealthStatus
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.domain.port.out.connector.SyncRequest
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import org.springframework.stereotype.Component

/**
 * `scripts/dogfood-seed.mjs` as a source system (#47, FR10): it reads this repository from GitHub and
 * pushes what it found. It stands in for the GitHub connector (#23) on the dogfood instance, which is
 * read-only and so cannot be written to any other way, and is replaced by it.
 *
 * Believed only with the ingest token. The same batch posted twice is one delivery.
 */
@Component
class DogfoodSeedConnector(
    private val properties: IngestProperties,
    private val parser: SeedBatchParser,
) : SourceConnector {
    override fun descriptor() =
        ConnectorDescriptor(
            name = NAME,
            sourceSystem = NAME,
            nodeTypes = SeedBatchParser.SEEDABLE_NODES,
            edgeTypes = SeedBatchParser.SEEDABLE_EDGES,
            capabilities = setOf(Capability.WEBHOOK),
            // A batch is what the script read this time, not a claim that nothing else exists.
            fullSyncIsComplete = false,
        )

    override fun healthCheck(): HealthStatus =
        if (properties.enabled) HealthStatus.up("accepting seed batches") else HealthStatus.down(UNCONFIGURED)

    override fun discover() = DiscoveryResult()

    /** Nothing to read: the seed script pushes. */
    override fun sync(request: SyncRequest): Sequence<GraphDelta> = emptySequence()

    override fun verifyWebhook(
        headers: Map<String, String>,
        body: ByteArray,
    ): Boolean =
        DeploymentReports.bearerMatches(
            headers.entries.firstOrNull { it.key.equals(AUTHORIZATION, ignoreCase = true) }?.value,
            properties.token,
        )

    override fun onWebhook(event: WebhookEvent): GraphDelta? =
        when (val parsed = parser.parse(event.body)) {
            is ParsedSeed.Valid -> parsed.delta
            is ParsedSeed.Invalid -> null
        }

    override fun deliveryId(event: WebhookEvent): String = DeploymentReports.deliveryIdOf(event.body)

    private companion object {
        const val NAME = SeedIngestService.CONNECTOR
        const val AUTHORIZATION = "Authorization"
        const val UNCONFIGURED = "ingest.token is not set, so seed batches are refused"
    }
}
