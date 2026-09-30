package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.lifecycle.AppliedMigration
import com.repodatagraph.domain.lifecycle.ArchivalDisabledException
import com.repodatagraph.domain.lifecycle.ArchiveCounts
import com.repodatagraph.domain.lifecycle.ArchiveResult
import com.repodatagraph.domain.lifecycle.ArchiveStatus
import com.repodatagraph.domain.lifecycle.InvalidMigrationException
import com.repodatagraph.domain.lifecycle.MigrationApplyResult
import com.repodatagraph.domain.lifecycle.MigrationChecksumException
import com.repodatagraph.domain.lifecycle.MigrationFailedException
import com.repodatagraph.domain.lifecycle.MigrationStatus
import com.repodatagraph.domain.lifecycle.NodeHistory
import com.repodatagraph.domain.lifecycle.OntologyAheadException
import com.repodatagraph.domain.lifecycle.toWire
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.`in`.LifecycleUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * The data lifecycle's administration (#33): the status an operator reads, the ontology migrations
 * an admin applies, the archive run or rehearsed, and one node's history.
 *
 * Reads need graph:read; applying migrations and running the archive need graph:admin as well as
 * graph:write, and a read-only deployment refuses both before they get here (ScopePolicy,
 * ReadOnlyGuard). Every answer is spelled out here rather than serialised from the domain, so the
 * wire names - `dry-run`, `P7D`, `missing-from-sync` - are the API's, whatever the code calls them.
 */
@RestController
@RequestMapping("/api/v1/lifecycle")
@Tag(name = "Lifecycle", description = "Versions, retirement, archival and ontology migrations")
class LifecycleController(
    private val lifecycle: LifecycleUseCase,
) {
    @GetMapping
    @Operation(
        operationId = "getLifecycleStatus",
        summary = "The lifecycle at a glance: migrations, versioning, the archive, each connector's rules",
    )
    fun status(): Map<String, Any?> {
        val status = lifecycle.status()
        return mapOf(
            "registryVersion" to status.registryVersion,
            "migrations" to LifecycleResponses.migrations(status.migrations),
            "versioning" to
                mapOf(
                    "enabled" to status.versioning.enabled,
                    "maxVersions" to status.versioning.maxVersions,
                    "excludedTypes" to status.versioning.excludedTypes.toList(),
                ),
            "archive" to LifecycleResponses.archive(status.archive),
            "connectors" to
                status.connectors.map {
                    mapOf(
                        "name" to it.name,
                        "sourceSystem" to it.sourceSystem,
                        "missingFromFullSync" to it.missingFromFullSync.wireName,
                        "gracePeriod" to it.gracePeriod.toWire(),
                        "requireSuccessfulRun" to it.requireSuccessfulRun,
                        "fullSyncIsComplete" to it.fullSyncIsComplete,
                    )
                },
        )
    }

    @GetMapping("/migrations")
    @Operation(
        operationId = "getOntologyMigrations",
        summary = "Which ontology version the graph is on, and the migrations pending and applied",
    )
    fun migrations(): Map<String, Any?> = LifecycleResponses.migrations(lifecycle.migrations())

    @PostMapping("/migrations/apply")
    @Operation(
        operationId = "applyOntologyMigrations",
        summary = "Apply every pending ontology migration, in order, each in its own transaction",
    )
    fun apply(): Map<String, Any?> {
        val result: MigrationApplyResult = lifecycle.applyMigrations()
        return mapOf("applied" to result.applied.map(LifecycleResponses::applied), "dbVersion" to result.dbVersion)
    }

    @PostMapping("/archive")
    @Operation(operationId = "runArchive", summary = "Rehearse the archive, or run it as configured")
    fun archive(
        @Parameter(description = "true to count what the archive would take and change nothing")
        @RequestParam(defaultValue = "false")
        dryRun: Boolean,
    ): Map<String, Any?> = LifecycleResponses.archiveResult(lifecycle.archive(dryRun))

    @GetMapping("/history")
    @Operation(operationId = "getNodeHistory", summary = "A node's current validity and its earlier versions, newest first")
    fun history(
        @Parameter(description = "The node, as Type:key")
        @RequestParam
        nodeId: String,
    ): ResponseEntity<Map<String, Any?>> {
        val key =
            runCatching { NodeKey.parse(nodeId) }
                .getOrElse { throw InvalidQueryParameterException("nodeId", it.message.orEmpty()) }
        val history =
            lifecycle.history(key)
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "node not found", "nodeId" to nodeId))
        return ResponseEntity.ok(LifecycleResponses.history(history))
    }
}

/** The lifecycle's answers as the API spells them. */
internal object LifecycleResponses {
    fun migrations(status: MigrationStatus): Map<String, Any?> =
        mapOf(
            "registryVersion" to status.registryVersion,
            "dbVersion" to status.dbVersion,
            "mode" to status.mode.wireName,
            "upToDate" to status.upToDate,
            "pending" to
                status.pending.map {
                    mapOf("version" to it.version, "name" to it.name, "checksum" to it.checksum, "description" to it.description)
                },
            "applied" to status.applied.map(::applied),
        )

    fun applied(migration: AppliedMigration): Map<String, Any?> =
        mapOf(
            "version" to migration.version,
            "name" to migration.name,
            "checksum" to migration.checksum,
            "appliedAt" to migration.appliedAt.toString(),
            "durationMs" to migration.durationMs,
            "baseline" to migration.baseline,
        )

    fun archive(status: ArchiveStatus): Map<String, Any?> =
        mapOf(
            "enabled" to status.enabled,
            "mode" to status.mode.wireName,
            "retention" to status.retention.toWire(),
            "schedule" to status.schedule,
            "cutoff" to status.cutoff.toString(),
            "eligible" to counts(status.eligible),
        )

    fun archiveResult(result: ArchiveResult): Map<String, Any?> =
        mapOf(
            "dryRun" to result.dryRun,
            "mode" to result.mode.wireName,
            "cutoff" to result.cutoff.toString(),
            "wouldArchive" to counts(result.wouldArchive),
            "archived" to result.archived?.let(::counts),
            "purged" to result.purged?.let(::counts),
            "file" to result.file,
            "syncRunId" to result.syncRunId,
        ).filterValues { it != null }

    fun counts(counts: ArchiveCounts) = mapOf("nodes" to counts.nodes, "edges" to counts.edges)

    fun history(history: NodeHistory): Map<String, Any?> =
        mapOf(
            "nodeId" to history.key.id,
            "current" to
                mapOf(
                    "validFrom" to history.current.validFrom.toString(),
                    "validTo" to history.current.validTo?.toString(),
                    "propsFrom" to history.current.propsFrom.toString(),
                    "retired" to history.current.retired,
                    "retiredReason" to history.current.retiredReason?.wireName,
                    "resurrectedAt" to history.current.resurrectedAt?.toString(),
                    "props" to history.current.props,
                ),
            "versions" to
                history.versions.map {
                    mapOf(
                        "validFrom" to it.validFrom.toString(),
                        "validTo" to it.validTo.toString(),
                        "retired" to it.retired,
                        "retiredReason" to it.retiredReason?.wireName,
                        "props" to it.props,
                        "provenance" to it.provenance,
                    )
                },
        )
}

/** The lifecycle's refusals: each a conflict with the graph's state, naming what an operator can do about it. */
@RestControllerAdvice(assignableTypes = [LifecycleController::class])
class LifecycleRestExceptionHandler {
    @ExceptionHandler(MigrationChecksumException::class)
    fun onChecksum(exception: MigrationChecksumException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "migration checksum changed",
                "migration" to exception.migration,
                "recorded" to exception.recorded,
                "actual" to exception.actual,
            ),
        )

    @ExceptionHandler(MigrationFailedException::class)
    fun onFailed(exception: MigrationFailedException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "migration failed",
                "migration" to exception.migration,
                "detail" to (exception.cause?.message ?: exception.message.orEmpty()),
            ),
        )

    /** A shipped migration that cannot be run, or a graph a newer build wrote: fixed by a release, not a retry. */
    @ExceptionHandler(InvalidMigrationException::class, OntologyAheadException::class)
    fun onUnrunnable(exception: IllegalStateException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "migrations cannot run", "detail" to exception.message.orEmpty()))

    @ExceptionHandler(ArchivalDisabledException::class)
    fun onDisabled(exception: ArchivalDisabledException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "archival is disabled", "setting" to exception.setting))
}
