package com.repodatagraph.application.lifecycle

import com.repodatagraph.application.connector.DeltaResult
import com.repodatagraph.application.connector.RunStatus
import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.domain.lifecycle.ArchivalDisabledException
import com.repodatagraph.domain.lifecycle.ArchiveCounts
import com.repodatagraph.domain.lifecycle.ArchiveMode
import com.repodatagraph.domain.lifecycle.ArchiveRecord
import com.repodatagraph.domain.lifecycle.ArchiveSettings
import com.repodatagraph.domain.port.out.ArchiveSink
import com.repodatagraph.domain.port.out.ArchiveStore
import com.repodatagraph.domain.port.out.ArchiveWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The archive (#33, FR6) and the order its safety rests on: off unless enabled, a rehearsal unless
 * told otherwise, and never a purge of anything it has not first written somewhere.
 */
class ArchiveServiceTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val cutoff = Instant.parse("2025-09-30T12:00:00Z")
    private val store: ArchiveStore = mock()
    private val writer: ArchiveWriter = mock()
    private val runs: SyncRunRecorder = mock()
    private val written = mutableListOf<ArchiveRecord>()
    private val sink =
        object : ArchiveSink {
            override val location = "/archive/archive-20260930T120000Z.jsonl"

            override fun write(record: ArchiveRecord) {
                written += record
            }

            override fun close() = Unit
        }

    private fun settings(
        enabled: Boolean = true,
        mode: ArchiveMode = ArchiveMode.PURGE,
    ) = ArchiveSettings(enabled, mode, Duration.ofDays(365), "0 0 4 * * *", "./archive")

    private fun service(settings: ArchiveSettings = settings()) =
        ArchiveService(settings, store, writer, runs, Clock.fixed(now, ZoneOffset.UTC))

    private fun eligible(
        nodes: Long,
        edges: Long,
    ) {
        whenever(store.count(cutoff)).thenReturn(ArchiveCounts(nodes, edges))
        whenever(writer.open(now)).thenReturn(sink)
        whenever(store.export(eq(cutoff), any())).thenAnswer {
            val out = it.arguments[1] as ArchiveSink
            repeat(nodes.toInt()) { index -> out.write(ArchiveRecord("node", mapOf("id" to "CloudResource:$index"))) }
            ArchiveCounts(nodes, edges)
        }
        whenever(store.purge(cutoff)).thenReturn(ArchiveCounts(nodes, edges))
    }

    @Test
    fun `the cutoff is the retention before now`() {
        assertThat(service().status().cutoff).isEqualTo(cutoff)
    }

    @Test
    fun `a dry run counts what it would take and writes nothing, even when archival is off`() {
        eligible(10, 2)

        val result = service(settings(enabled = false)).run(dryRun = true)

        assertThat(result.dryRun).isTrue()
        assertThat(result.wouldArchive).isEqualTo(ArchiveCounts(10, 2))
        verify(store, never()).export(any(), any())
        verify(store, never()).purge(any())
        verifyNoInteractions(writer, runs)
    }

    @Test
    fun `a real run is refused while archival is disabled`() {
        assertThrows<ArchivalDisabledException> { service(settings(enabled = false)).run(dryRun = false) }

        verifyNoInteractions(writer, runs)
        verify(store, never()).purge(any())
    }

    @Test
    fun `enabled in its default mode, a run only reports`() {
        eligible(10, 0)

        val result = service(settings(mode = ArchiveMode.DRY_RUN)).run(dryRun = false)

        assertThat(result.dryRun).isTrue()
        verify(store, never()).purge(any())
        verifyNoInteractions(writer)
    }

    @Test
    fun `export writes the file and leaves the graph as it was`() {
        eligible(10, 0)

        val result = service(settings(mode = ArchiveMode.EXPORT)).run(dryRun = false)

        assertThat(result.dryRun).isFalse()
        assertThat(result.archived).isEqualTo(ArchiveCounts(10, 0))
        assertThat(result.purged).isNull()
        assertThat(result.file).isEqualTo(sink.location)
        assertThat(written).hasSize(10)
        verify(store, never()).purge(any())
    }

    @Test
    fun `purge writes the file first, then deletes, and records the run`() {
        eligible(10, 3)

        val result = service().run(dryRun = false)

        assertThat(written).hasSize(10)
        assertThat(result.purged).isEqualTo(ArchiveCounts(10, 3))
        assertThat(result.syncRunId).isNotBlank()
        val totals = argumentCaptor<DeltaResult>()
        verify(runs).recordInternalRun(
            eq(result.syncRunId!!),
            eq(ArchiveService.CONNECTOR),
            eq(RunStatus.SUCCESS),
            totals.capture(),
            any(),
            anyOrNull(),
        )
        assertThat(totals.firstValue.nodesUpserted).isEqualTo(10)
    }

    @Test
    fun `when the file cannot be written nothing is purged`() {
        eligible(10, 0)
        whenever(writer.open(now)).thenThrow(IOException("disk full"))

        assertThrows<IOException> { service().run(dryRun = false) }

        verify(store, never()).purge(any())
    }

    @Test
    fun `status says whether it is on, how, and what it would take now`() {
        eligible(4, 1)

        val status = service(settings(enabled = false, mode = ArchiveMode.DRY_RUN)).status()

        assertThat(status.enabled).isFalse()
        assertThat(status.mode).isEqualTo(ArchiveMode.DRY_RUN)
        assertThat(status.retention).isEqualTo(Duration.ofDays(365))
        assertThat(status.eligible).isEqualTo(ArchiveCounts(4, 1))
    }
}
