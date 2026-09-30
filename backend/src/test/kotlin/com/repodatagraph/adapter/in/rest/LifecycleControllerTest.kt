package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.lifecycle.AppliedMigration
import com.repodatagraph.domain.lifecycle.ArchivalDisabledException
import com.repodatagraph.domain.lifecycle.ArchiveCounts
import com.repodatagraph.domain.lifecycle.ArchiveMode
import com.repodatagraph.domain.lifecycle.ArchiveResult
import com.repodatagraph.domain.lifecycle.ArchiveStatus
import com.repodatagraph.domain.lifecycle.ConnectorLifecycle
import com.repodatagraph.domain.lifecycle.CurrentValidity
import com.repodatagraph.domain.lifecycle.LifecycleStatus
import com.repodatagraph.domain.lifecycle.MigrationApplyResult
import com.repodatagraph.domain.lifecycle.MigrationChecksumException
import com.repodatagraph.domain.lifecycle.MigrationFailedException
import com.repodatagraph.domain.lifecycle.MigrationInfo
import com.repodatagraph.domain.lifecycle.MigrationMode
import com.repodatagraph.domain.lifecycle.MigrationStatus
import com.repodatagraph.domain.lifecycle.MissingFromFullSync
import com.repodatagraph.domain.lifecycle.NodeHistory
import com.repodatagraph.domain.lifecycle.NodeVersionView
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.lifecycle.VersioningSettings
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.`in`.LifecycleUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant

/**
 * The lifecycle administration surface over HTTP (#33): the status an operator reads, the migrations
 * an admin applies, the archive run or rehearsed, and one node's history. The scopes each needs are
 * [com.repodatagraph.adapter.in.security.ScopePolicy]'s, tested there; this is the shape of each
 * answer and each refusal.
 */
@WebMvcTest(LifecycleController::class)
class LifecycleControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var lifecycle: LifecycleUseCase

    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val cutoff = Instant.parse("2025-09-30T12:00:00Z")

    private val pending =
        MigrationInfo(version = "1.4.0", name = "rename_ci_legacy_name", checksum = "a".repeat(64), description = "Move it")
    private val applied =
        AppliedMigration(
            version = "1.4.0",
            name = "rename_ci_legacy_name",
            checksum = "a".repeat(64),
            appliedAt = now,
            durationMs = 12,
            baseline = false,
        )

    private val behind =
        MigrationStatus(
            registryVersion = "1.4.0",
            dbVersion = "1.3.0",
            mode = MigrationMode.AUTO,
            pending = listOf(pending),
            applied = emptyList(),
        )

    @Test
    fun `the status names the migrations, the archive, versioning and each connector's rules`() {
        whenever(lifecycle.status()).thenReturn(
            LifecycleStatus(
                migrations = behind,
                versioning = VersioningSettings(enabled = true, maxVersions = 50, excludedTypes = listOf("Ontology", "SyncRun")),
                archive =
                    ArchiveStatus(
                        enabled = false,
                        mode = ArchiveMode.DRY_RUN,
                        retention = Duration.ofDays(365),
                        schedule = "0 0 4 * * *",
                        cutoff = cutoff,
                        eligible = ArchiveCounts(nodes = 10, edges = 3),
                    ),
                connectors =
                    listOf(
                        ConnectorLifecycle(
                            name = "fake-itsm",
                            sourceSystem = "fake-itsm",
                            missingFromFullSync = MissingFromFullSync.TOMBSTONE,
                            gracePeriod = Duration.ofDays(7),
                            fullSyncIsComplete = true,
                        ),
                    ),
            ),
        )

        mockMvc
            .perform(get("/api/v1/lifecycle"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.registryVersion").value("1.4.0"))
            .andExpect(jsonPath("$.migrations.dbVersion").value("1.3.0"))
            .andExpect(jsonPath("$.migrations.upToDate").value(false))
            .andExpect(jsonPath("$.versioning.enabled").value(true))
            .andExpect(jsonPath("$.versioning.maxVersions").value(50))
            .andExpect(jsonPath("$.versioning.excludedTypes[1]").value("SyncRun"))
            .andExpect(jsonPath("$.archive.enabled").value(false))
            .andExpect(jsonPath("$.archive.mode").value("dry-run"))
            .andExpect(jsonPath("$.archive.retention").value("P365D"))
            .andExpect(jsonPath("$.archive.schedule").value("0 0 4 * * *"))
            .andExpect(jsonPath("$.archive.cutoff").value("2025-09-30T12:00:00Z"))
            .andExpect(jsonPath("$.archive.eligible.nodes").value(10))
            .andExpect(jsonPath("$.archive.eligible.edges").value(3))
            .andExpect(jsonPath("$.connectors[0].name").value("fake-itsm"))
            .andExpect(jsonPath("$.connectors[0].missingFromFullSync").value("tombstone"))
            .andExpect(jsonPath("$.connectors[0].gracePeriod").value("P7D"))
            .andExpect(jsonPath("$.connectors[0].requireSuccessfulRun").value(true))
            .andExpect(jsonPath("$.connectors[0].fullSyncIsComplete").value(true))
    }

    @Test
    fun `the migrations say which are pending and whether the graph is up to date`() {
        whenever(lifecycle.migrations()).thenReturn(behind)

        mockMvc
            .perform(get("/api/v1/lifecycle/migrations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.registryVersion").value("1.4.0"))
            .andExpect(jsonPath("$.dbVersion").value("1.3.0"))
            .andExpect(jsonPath("$.mode").value("auto"))
            .andExpect(jsonPath("$.upToDate").value(false))
            .andExpect(jsonPath("$.pending[0].version").value("1.4.0"))
            .andExpect(jsonPath("$.pending[0].name").value("rename_ci_legacy_name"))
            .andExpect(jsonPath("$.pending[0].checksum").value("a".repeat(64)))
            .andExpect(jsonPath("$.pending[0].description").value("Move it"))
            .andExpect(jsonPath("$.applied").isEmpty)
    }

    @Test
    fun `applying answers with what was applied and the version the graph is now on`() {
        whenever(lifecycle.applyMigrations()).thenReturn(MigrationApplyResult(applied = listOf(applied), dbVersion = "1.4.0"))

        mockMvc
            .perform(post("/api/v1/lifecycle/migrations/apply"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dbVersion").value("1.4.0"))
            .andExpect(jsonPath("$.applied[0].version").value("1.4.0"))
            .andExpect(jsonPath("$.applied[0].name").value("rename_ci_legacy_name"))
            .andExpect(jsonPath("$.applied[0].appliedAt").value("2026-09-30T12:00:00Z"))
            .andExpect(jsonPath("$.applied[0].durationMs").value(12))
            .andExpect(jsonPath("$.applied[0].baseline").value(false))
    }

    @Test
    fun `an applied migration whose file changed is a conflict naming it and its checksums`() {
        whenever(lifecycle.applyMigrations()).thenThrow(
            MigrationChecksumException("V1_4_0__rename_ci_legacy_name", recorded = "0".repeat(64), actual = "a".repeat(64)),
        )

        mockMvc
            .perform(post("/api/v1/lifecycle/migrations/apply"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("migration checksum changed"))
            .andExpect(jsonPath("$.migration").value("V1_4_0__rename_ci_legacy_name"))
            .andExpect(jsonPath("$.recorded").value("0".repeat(64)))
            .andExpect(jsonPath("$.actual").value("a".repeat(64)))
    }

    @Test
    fun `a migration that fails is a conflict naming it, and the graph is left as it was`() {
        whenever(lifecycle.applyMigrations()).thenThrow(
            MigrationFailedException("V1_4_0__rename_ci_legacy_name", IllegalStateException("constraint violated")),
        )

        mockMvc
            .perform(post("/api/v1/lifecycle/migrations/apply"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("migration failed"))
            .andExpect(jsonPath("$.migration").value("V1_4_0__rename_ci_legacy_name"))
            .andExpect(jsonPath("$.detail").value("constraint violated"))
    }

    @Test
    fun `a dry run answers with what the archive would take`() {
        whenever(lifecycle.archive(true)).thenReturn(
            ArchiveResult(dryRun = true, mode = ArchiveMode.DRY_RUN, cutoff = cutoff, wouldArchive = ArchiveCounts(10, 3)),
        )

        mockMvc
            .perform(post("/api/v1/lifecycle/archive?dryRun=true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dryRun").value(true))
            .andExpect(jsonPath("$.mode").value("dry-run"))
            .andExpect(jsonPath("$.cutoff").value("2025-09-30T12:00:00Z"))
            .andExpect(jsonPath("$.wouldArchive.nodes").value(10))
            .andExpect(jsonPath("$.wouldArchive.edges").value(3))
        verify(lifecycle).archive(true)
    }

    @Test
    fun `a run answers with what it archived, what it purged and the file it wrote`() {
        whenever(lifecycle.archive(false)).thenReturn(
            ArchiveResult(
                dryRun = false,
                mode = ArchiveMode.PURGE,
                cutoff = cutoff,
                wouldArchive = ArchiveCounts(10, 3),
                archived = ArchiveCounts(10, 3),
                purged = ArchiveCounts(10, 3),
                file = "archive-20260930T120000Z.jsonl",
                syncRunId = "run-1",
            ),
        )

        mockMvc
            .perform(post("/api/v1/lifecycle/archive"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dryRun").value(false))
            .andExpect(jsonPath("$.mode").value("purge"))
            .andExpect(jsonPath("$.archived.nodes").value(10))
            .andExpect(jsonPath("$.purged.edges").value(3))
            .andExpect(jsonPath("$.file").value("archive-20260930T120000Z.jsonl"))
            .andExpect(jsonPath("$.syncRunId").value("run-1"))
    }

    @Test
    fun `a run while archival is off is a conflict that says how to turn it on`() {
        whenever(lifecycle.archive(false)).thenThrow(ArchivalDisabledException())

        mockMvc
            .perform(post("/api/v1/lifecycle/archive"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("archival is disabled"))
            .andExpect(jsonPath("$.setting").value("lifecycle.archive.enabled"))
    }

    @Test
    fun `a dryRun that is not a boolean is refused before anything runs`() {
        mockMvc
            .perform(post("/api/v1/lifecycle/archive?dryRun=perhaps"))
            .andExpect(status().isBadRequest)
        verifyNoInteractions(lifecycle)
    }

    @Test
    fun `a node's history lists its current validity and its earlier versions`() {
        val key = NodeKey("Repository", "github.com/acme/payments")
        val began = Instant.parse("2026-01-01T00:00:00Z")
        val changed = Instant.parse("2026-02-01T00:00:00Z")
        whenever(lifecycle.history(key)).thenReturn(
            NodeHistory(
                key = key,
                current =
                    CurrentValidity(
                        validFrom = began,
                        validTo = null,
                        propsFrom = changed,
                        props = mapOf("description" to "v2"),
                        retiredReason = null,
                        resurrectedAt = null,
                    ),
                versions =
                    listOf(
                        NodeVersionView(
                            validFrom = began,
                            validTo = changed,
                            retired = false,
                            retiredReason = null,
                            props = mapOf("description" to "v1"),
                            provenance = Provenance(sourceSystem = "manual", ingestedAt = began, validFrom = began, validTo = changed),
                        ),
                    ),
            ),
        )

        mockMvc
            .perform(get("/api/v1/lifecycle/history").param("nodeId", "Repository:github.com/acme/payments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nodeId").value("Repository:github.com/acme/payments"))
            .andExpect(jsonPath("$.current.validFrom").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.current.validTo").doesNotExist())
            .andExpect(jsonPath("$.current.propsFrom").value("2026-02-01T00:00:00Z"))
            .andExpect(jsonPath("$.current.retired").value(false))
            .andExpect(jsonPath("$.current.props.description").value("v2"))
            .andExpect(jsonPath("$.versions[0].validFrom").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.versions[0].validTo").value("2026-02-01T00:00:00Z"))
            .andExpect(jsonPath("$.versions[0].retired").value(false))
            .andExpect(jsonPath("$.versions[0].props.description").value("v1"))
            .andExpect(jsonPath("$.versions[0].provenance.sourceSystem").value("manual"))
    }

    @Test
    fun `a retired node's history says why it was retired`() {
        val key = NodeKey("Team", "a1")
        val began = Instant.parse("2026-01-01T00:00:00Z")
        whenever(lifecycle.history(key)).thenReturn(
            NodeHistory(
                key = key,
                current = CurrentValidity(began, now, began, mapOf("name" to "a1"), RetiredReason.MISSING_FROM_SYNC, null),
                versions = emptyList(),
            ),
        )

        mockMvc
            .perform(get("/api/v1/lifecycle/history").param("nodeId", "Team:a1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.retired").value(true))
            .andExpect(jsonPath("$.current.retiredReason").value("missing-from-sync"))
            .andExpect(jsonPath("$.current.validTo").value("2026-09-30T12:00:00Z"))
    }

    @Test
    fun `the history of a node that does not exist is not found`() {
        whenever(lifecycle.history(any())).thenReturn(null)

        mockMvc
            .perform(get("/api/v1/lifecycle/history").param("nodeId", "Team:nobody"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("node not found"))
            .andExpect(jsonPath("$.nodeId").value("Team:nobody"))
    }

    @Test
    fun `a node id that names no type is refused, naming the parameter`() {
        mockMvc
            .perform(get("/api/v1/lifecycle/history").param("nodeId", "just-a-key"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("nodeId"))
        verifyNoInteractions(lifecycle)
    }
}
