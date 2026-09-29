package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.SyncRunDetail
import com.repodatagraph.adapter.`in`.rest.dto.SyncRunPageResponse
import com.repodatagraph.application.connector.RunStatus
import com.repodatagraph.domain.model.SyncRunQuery
import com.repodatagraph.domain.port.out.SyncRunStore
import com.repodatagraph.observability.LogEvents
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The history of connector runs, across every connector (#29, FR5).
 *
 * Every filter is checked here or by [SyncRunQuery] before the store is asked, and refused with 400
 * when it cannot mean anything - an unknown status, a page size past the ceiling, a time that is not
 * an instant. A filter that is merely unmatched, such as a connector that no longer exists, is an
 * empty page: its runs are history, and may still be there.
 */
@RestController
@RequestMapping("/api/v1/sync-runs")
@Tag(name = "Sync runs", description = "What each connector run did, newest first")
class SyncRunController(
    private val store: SyncRunStore,
) {
    @GetMapping
    @Operation(operationId = "listSyncRuns", summary = "List sync runs, newest first, filtered by connector, status and start time")
    fun list(
        @RequestParam(required = false) connector: String?,
        @Parameter(description = "RUNNING, SUCCESS, PARTIAL or FAILED, in any case")
        @RequestParam(required = false)
        status: String?,
        @Parameter(description = "Runs that started at or after this ISO-8601 instant, e.g. 2026-09-01T00:00:00Z")
        @RequestParam(required = false)
        from: String?,
        @Parameter(description = "Runs that started before this ISO-8601 instant")
        @RequestParam(required = false)
        to: String?,
        @Parameter(description = "Page number, from 0")
        @RequestParam(defaultValue = "0")
        page: Int,
        @Parameter(description = "Runs per page, 1 to ${SyncRunQuery.MAX_SIZE}")
        @RequestParam(defaultValue = "${SyncRunQuery.DEFAULT_SIZE}")
        size: Int,
    ): SyncRunPageResponse {
        val query =
            SyncRunQuery(
                connector = connector?.takeIf { it.isNotBlank() },
                status = status?.let(::statusOf),
                startedFrom = from?.let { instantOf("from", it) },
                startedBefore = to?.let { instantOf("to", it) },
                page = page,
                size = size,
            )
        return SyncRunPageResponse.from(store.find(query), query)
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getSyncRun", summary = "One sync run in full, with its whole error and details")
    fun get(
        @PathVariable id: String,
    ): SyncRunDetail = SyncRunDetail.from(store.findById(id) ?: throw SyncRunNotFoundException(id))

    /** The stored spelling, whatever case the caller used; anything else is not a status a run has. */
    private fun statusOf(status: String): String =
        RunStatus.entries.firstOrNull { it.name.equals(status, ignoreCase = true) }?.name
            ?: throw IllegalArgumentException(
                "status must be one of ${RunStatus.entries.joinToString()}, not '$status'",
            )

    private fun instantOf(
        name: String,
        value: String,
    ): Instant =
        try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("$name must be an ISO-8601 instant such as 2026-09-01T00:00:00Z")
        }
}

/** Asked for a run that was never recorded, or has been pruned since. */
class SyncRunNotFoundException(
    val id: String,
) : RuntimeException("no sync run $id")

/**
 * The sync run refusals, in the same shape as the rest of the API. Scoped to [SyncRunController], so
 * the 400 for a page that is not a number does not change what other endpoints answer for theirs.
 */
@RestControllerAdvice(assignableTypes = [SyncRunController::class])
class SyncRunRestExceptionHandler {
    @ExceptionHandler(SyncRunNotFoundException::class)
    fun onNotFound(exception: SyncRunNotFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "sync run not found", "id" to exception.id))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onMismatch(exception: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any>> {
        LogEvents.httpRequestRejected(exception)
        return ResponseEntity.badRequest().body(
            mapOf("error" to "invalid request", "detail" to "${exception.name} must be a whole number"),
        )
    }
}
