package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.ConnectorDetail
import com.repodatagraph.adapter.`in`.rest.dto.ConnectorSummary
import com.repodatagraph.adapter.`in`.rest.dto.HealthView
import com.repodatagraph.adapter.`in`.rest.dto.SyncAccepted
import com.repodatagraph.adapter.`in`.rest.dto.SyncRunView
import com.repodatagraph.application.connector.AdapterRegistry
import com.repodatagraph.application.connector.SyncInProgressException
import com.repodatagraph.application.connector.SyncService
import com.repodatagraph.application.connector.UnknownConnectorException
import com.repodatagraph.application.connector.UnsupportedCapabilityException
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.SyncMode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.TaskScheduler
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

/**
 * The connectors, what they have done, and how to make them do it again.
 *
 * A sync answers 202 with a run id rather than waiting: reading somebody else's estate takes as long
 * as it takes, and a caller holding an HTTP connection open for it learns nothing they could not
 * learn by following the run.
 */
@RestController
@RequestMapping("/api/v1/connectors")
@Tag(name = "Connectors", description = "Ingestion from systems of record")
class ConnectorController(
    private val registry: AdapterRegistry,
    private val syncService: SyncService,
    private val graphStore: GraphStore,
    private val taskScheduler: TaskScheduler,
) {
    @GetMapping
    @Operation(summary = "List every connector, with its state and last run")
    fun list(): List<ConnectorSummary> =
        registry.all().map { registered ->
            ConnectorSummary.from(
                registered,
                registered.connector.healthCheck(),
                stateOf(registered.name),
                syncService.isRunning(registered.name),
            )
        }

    @GetMapping("/{name}")
    @Operation(summary = "One connector, its descriptor and what the graph remembers about it")
    fun get(
        @PathVariable name: String,
    ): ConnectorDetail {
        val registered = registry.find(name) ?: throw UnknownConnectorException(name)
        return ConnectorDetail.from(
            registered,
            registered.connector.healthCheck(),
            stateOf(name),
            syncService.isRunning(name),
        )
    }

    @GetMapping("/{name}/health")
    @Operation(summary = "Whether the source system is reachable now")
    fun health(
        @PathVariable name: String,
    ): HealthView {
        val registered = registry.find(name) ?: throw UnknownConnectorException(name)
        val status = registered.connector.healthCheck()
        return HealthView(status.status.name, status.detail)
    }

    /**
     * The run is registered before this returns, so a caller that immediately asks for it finds it.
     * Only the work happens on another thread.
     */
    @PostMapping("/{name}/sync")
    @Operation(summary = "Ask a connector to sync now")
    fun sync(
        @PathVariable name: String,
        @RequestParam(defaultValue = "incremental") mode: String,
    ): ResponseEntity<SyncAccepted> {
        val syncMode = modeOf(mode)
        val runId = syncService.start(name, syncMode)
        // Scheduled for now rather than run here: the caller gets the run id immediately and
        // follows the run, instead of holding a connection open for however long the source takes.
        taskScheduler.schedule({ syncService.execute(name, runId, syncMode) }, Instant.now())
        return ResponseEntity.accepted().body(SyncAccepted(runId))
    }

    @GetMapping("/{name}/runs")
    @Operation(summary = "This connector's runs, newest first")
    fun runs(
        @PathVariable name: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<SyncRunView> {
        registry.find(name) ?: throw UnknownConnectorException(name)
        return graphStore
            .findNodes(SYNC_RUN)
            .map { SyncRunView.from(it) }
            .filter { it.connector == name }
            .sortedByDescending { it.startedAt }
            .take(limit)
    }

    private fun stateOf(name: String) = graphStore.findNode(NodeKey(CONNECTOR_STATE, name))

    private fun modeOf(mode: String): SyncMode =
        when (mode.lowercase()) {
            "full" -> SyncMode.FULL
            "incremental" -> SyncMode.INCREMENTAL
            else -> throw IllegalArgumentException("mode must be 'full' or 'incremental', not '$mode'")
        }

    private companion object {
        const val SYNC_RUN = "SyncRun"
        const val CONNECTOR_STATE = "ConnectorState"
    }
}

/** Maps the connector refusals onto the statuses a caller can act on. */
@RestControllerAdvice
class ConnectorRestExceptionHandler {
    @ExceptionHandler(UnknownConnectorException::class)
    fun onUnknown(exception: UnknownConnectorException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf("error" to "unknown connector", "connector" to exception.connector),
        )

    /**
     * 409 rather than queueing. Two runs of one connector interleave their writes, and the result is
     * a graph nobody can reason about afterwards - better to refuse and let the caller wait.
     */
    @ExceptionHandler(SyncInProgressException::class)
    fun onInProgress(exception: SyncInProgressException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "sync in progress", "connector" to exception.connector),
        )

    @ExceptionHandler(UnsupportedCapabilityException::class)
    fun onUnsupported(exception: UnsupportedCapabilityException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "error" to "unsupported capability",
                "connector" to exception.connector,
                "capability" to exception.capability.name,
            ),
        )
}
