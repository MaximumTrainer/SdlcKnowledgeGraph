package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.adapter.out.github.GitHubWebhookVerifier
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
import java.time.Clock
import java.time.Instant

/**
 * GitHub Actions as a source system, heard from two ways that write the same facts.
 *
 * The deploy pipeline reports each deployment it makes (#7), believed only with the ingest token,
 * compared in constant time; the same report posted twice is one delivery, named by a hash of its
 * content. And the connector reads what GitHub Actions did from GitHub itself (#90): workflow runs,
 * the packages they published and the deployments they made, polled from a cursor on its schedule and
 * read back as soon as GitHub's signed `workflow_run` and `deployment_status` events say something
 * moved. A request carrying GitHub's signature is judged by that signature alone, with this
 * connector's own webhook secret, so the ingest token cannot carry an event GitHub did not sign.
 *
 * A full run reads a window of recent runs, never everything GitHub Actions ever did, so it is not a
 * complete statement of the source and retires nothing it did not see.
 */
@Component
class GitHubActionsConnector(
    private val properties: IngestProperties,
    private val parser: DeploymentReportParser,
    private val mapper: DeploymentReportMapper,
    private val deployments: GitHubDeployments,
    private val webhookVerifier: GitHubWebhookVerifier,
    private val clock: Clock,
) : SourceConnector {
    override fun descriptor() =
        ConnectorDescriptor(
            name = NAME,
            sourceSystem = NAME,
            nodeTypes = setOf("Repository", "Pipeline", "Artifact", "Deployment", "Environment"),
            edgeTypes = setOf("HAS_PIPELINE", "BUILT_FROM", "DEPLOYED_TO", "TO_ENVIRONMENT"),
            capabilities = setOf(Capability.WEBHOOK, Capability.INCREMENTAL, Capability.FULL),
            fullSyncIsComplete = false,
            version = VERSION,
        )

    /** Up while it can do either of its jobs; down, with why, when it can do neither or GitHub is silent. */
    override fun healthCheck(): HealthStatus {
        val reports = if (properties.enabled) "accepting deployment reports" else null
        return when {
            deployments.isConfigured() && !deployments.isReachable() -> HealthStatus.down("GitHub did not answer")
            deployments.isConfigured() -> HealthStatus.up(listOfNotNull(reports, READING).joinToString("; "))
            reports != null -> HealthStatus.up(reports)
            else -> HealthStatus.down(UNCONFIGURED)
        }
    }

    override fun discover() = DiscoveryResult()

    /** From the cursor, with when this run began as the next one, as the GitHub connector does. */
    override fun sync(request: SyncRequest): Sequence<GraphDelta> {
        require(deployments.isConfigured()) { NOT_READING }
        return deployments.sync(request.since, Instant.now(clock))
    }

    override fun verifyWebhook(
        headers: Map<String, String>,
        body: ByteArray,
    ): Boolean =
        if (webhookVerifier.isSigned(headers)) {
            webhookVerifier.verify(headers, body, NAME)
        } else {
            DeploymentReports.bearerMatches(
                headers.entries.firstOrNull { it.key.equals(AUTHORIZATION, ignoreCase = true) }?.value,
                properties.token,
            )
        }

    override fun onWebhook(event: WebhookEvent): GraphDelta? =
        if (webhookVerifier.isSigned(event.headers)) {
            deployments.handle(event)
        } else {
            when (val parsed = parser.parse(event.body)) {
                is ParsedReport.Valid -> mapper.map(parsed.report).delta
                // The ingest endpoint refuses an invalid report before it gets here; one sent to the
                // generic webhook route instead is recorded as nothing rather than as half a deployment.
                is ParsedReport.Invalid -> null
            }
        }

    /** GitHub's own id for a delivery of its events; a report's content otherwise. */
    override fun deliveryId(event: WebhookEvent): String =
        webhookVerifier.deliveryId(event)?.takeIf { webhookVerifier.isSigned(event.headers) }
            ?: DeploymentReports.deliveryIdOf(event.body)

    companion object {
        /**
         * The version of what this connector writes (#86, FR-6). 2.0.0 added reading workflow runs,
         * packages and deployments from GitHub (#90) beside the deploy pipeline's reports.
         */
        const val VERSION = "2.0.0"
        private const val NAME = DeploymentReports.CONNECTOR
        private const val AUTHORIZATION = "Authorization"
        private const val READING = "reading workflow runs and deployments"
        private const val UNCONFIGURED =
            "ingest.token is not set, so deployment reports are refused, and connectors.github has no org and token to read with"
        private const val NOT_READING = "connectors.github needs at least one org and a token before workflow runs can be read"
    }
}
