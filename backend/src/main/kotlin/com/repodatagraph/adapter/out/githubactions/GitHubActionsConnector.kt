package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.application.ingest.DeploymentReportMapper
import com.repodatagraph.application.ingest.DeploymentReportParser
import com.repodatagraph.application.ingest.DeploymentReports
import com.repodatagraph.application.ingest.ParsedReport
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
 * The deploy pipeline as a source system (#7): it reports each deployment as it happens, and there
 * is nothing to poll.
 *
 * A report is believed only with the bearer token this instance was configured with, compared in
 * constant time. The same report posted twice is one delivery, named by a hash of its content, so the
 * sync machinery applies it once however often a retrying workflow step sends it.
 */
@Component
class GitHubActionsConnector(
    private val properties: IngestProperties,
    private val parser: DeploymentReportParser,
    private val mapper: DeploymentReportMapper,
) : SourceConnector {
    override fun descriptor() =
        ConnectorDescriptor(
            name = NAME,
            sourceSystem = NAME,
            nodeTypes = setOf("Repository", "Pipeline", "Artifact", "Deployment", "Environment"),
            edgeTypes = setOf("HAS_PIPELINE", "BUILT_FROM", "DEPLOYED_TO", "TO_ENVIRONMENT"),
            capabilities = setOf(Capability.WEBHOOK),
            // It reports one deployment at a time, never everything there is.
            fullSyncIsComplete = false,
        )

    override fun healthCheck(): HealthStatus =
        if (properties.enabled) HealthStatus.up("accepting deployment reports") else HealthStatus.down(UNCONFIGURED)

    override fun discover() = DiscoveryResult()

    /** Nothing to read: the pipeline pushes. */
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
            is ParsedReport.Valid -> mapper.map(parsed.report).delta
            // The ingest endpoint refuses an invalid report before it gets here; one sent to the
            // generic webhook route instead is recorded as nothing rather than as half a deployment.
            is ParsedReport.Invalid -> null
        }

    override fun deliveryId(event: WebhookEvent): String = DeploymentReports.deliveryIdOf(event.body)

    private companion object {
        const val NAME = DeploymentReports.CONNECTOR
        const val AUTHORIZATION = "Authorization"
        const val UNCONFIGURED = "ingest.token is not set, so deployment reports are refused"
    }
}
