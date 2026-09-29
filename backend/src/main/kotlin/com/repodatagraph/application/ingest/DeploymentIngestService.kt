package com.repodatagraph.application.ingest

import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.application.connector.SyncService
import com.repodatagraph.config.IngestProperties
import com.repodatagraph.domain.port.`in`.DeploymentIngestOutcome
import com.repodatagraph.domain.port.`in`.DeploymentIngestUseCase
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import org.springframework.stereotype.Service

/**
 * Applies a deploy report through the github-actions connector, so it is recorded like any other
 * webhook: in a sync run, deduplicated by delivery, with the connector's provenance.
 *
 * The order of the checks is the contract (#7, FR5): off, then unauthorised, then invalid, then
 * applied. The token is checked before the body is read, so a caller without it cannot use the
 * validation errors to learn the payload.
 */
@Service
class DeploymentIngestService(
    private val properties: IngestProperties,
    private val parser: DeploymentReportParser,
    private val mapper: DeploymentReportMapper,
    private val syncService: SyncService,
    private val recorder: SyncRunRecorder,
) : DeploymentIngestUseCase {
    override fun ingest(
        authorization: String?,
        body: ByteArray,
    ): DeploymentIngestOutcome =
        when {
            !properties.enabled -> DeploymentIngestOutcome.Disabled
            !DeploymentReports.bearerMatches(authorization, properties.token) -> DeploymentIngestOutcome.Unauthorized
            else ->
                when (val parsed = parser.parse(body)) {
                    is ParsedReport.Invalid -> DeploymentIngestOutcome.Invalid(parsed.errors)
                    is ParsedReport.Valid -> apply(parsed.report, checkNotNull(authorization), body)
                }
        }

    private fun apply(
        report: DeploymentReport,
        authorization: String,
        body: ByteArray,
    ): DeploymentIngestOutcome.Accepted {
        val mapped = mapper.map(report)
        val name = DeploymentReports.CONNECTOR
        val alreadyApplied = recorder.runForDelivery(name, DeploymentReports.deliveryIdOf(body)) != null
        syncService.applyWebhook(name, WebhookEvent(name, mapOf(AUTHORIZATION to authorization), body))

        return DeploymentIngestOutcome.Accepted(
            deploymentIds = mapped.deploymentKeys.map { it.id },
            created = !alreadyApplied,
            nodes = mapped.delta.nodes.size,
            edges = mapped.delta.edges.size,
        )
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
