package com.repodatagraph.application.ingest

import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.application.connector.SyncService
import com.repodatagraph.config.IngestProperties
import com.repodatagraph.domain.port.`in`.SeedIngestOutcome
import com.repodatagraph.domain.port.`in`.SeedIngestUseCase
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import org.springframework.stereotype.Service

/**
 * Applies a seed batch through the dogfood-seed connector, so it is recorded like any other webhook:
 * in a sync run, deduplicated by delivery, and credited to `dogfood-seed` (#47, FR10).
 *
 * Decides in the same order as [DeploymentIngestService]: off, then unauthorised, then invalid, then
 * applied.
 */
@Service
class SeedIngestService(
    private val properties: IngestProperties,
    private val parser: SeedBatchParser,
    private val syncService: SyncService,
    private val recorder: SyncRunRecorder,
) : SeedIngestUseCase {
    override fun seed(
        authorization: String?,
        body: ByteArray,
    ): SeedIngestOutcome =
        when {
            !properties.enabled -> SeedIngestOutcome.Disabled
            !DeploymentReports.bearerMatches(authorization, properties.token) -> SeedIngestOutcome.Unauthorized
            else ->
                when (val parsed = parser.parse(body)) {
                    is ParsedSeed.Invalid -> SeedIngestOutcome.Invalid(parsed.errors)
                    is ParsedSeed.Valid -> apply(parsed, checkNotNull(authorization), body)
                }
        }

    private fun apply(
        parsed: ParsedSeed.Valid,
        authorization: String,
        body: ByteArray,
    ): SeedIngestOutcome.Accepted {
        val alreadyApplied = recorder.runForDelivery(CONNECTOR, DeploymentReports.deliveryIdOf(body)) != null
        syncService.applyWebhook(CONNECTOR, WebhookEvent(CONNECTOR, mapOf(AUTHORIZATION to authorization), body))

        return SeedIngestOutcome.Accepted(
            created = !alreadyApplied,
            nodes = parsed.delta.nodes.size,
            edges = parsed.delta.edges.size,
        )
    }

    companion object {
        /** The connector a batch is applied through, and the source system its facts are credited to. */
        const val CONNECTOR = "dogfood-seed"
        private const val AUTHORIZATION = "Authorization"
    }
}
