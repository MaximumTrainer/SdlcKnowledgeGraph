package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.PartialReadException
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.observability.SyncMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * What a run counts (#86, FR-6): what its pages wrote and left unchanged, added up, and what it could
 * not read or write - each item a connector names as unreadable, and each page that could not be
 * written - so "failed" is a number rather than only a status.
 */
class SyncServiceAccountingTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC)
    private val writer: GraphDeltaWriter = mock()
    private val recorder: SyncRunRecorder = mock()
    private val connector: SourceConnector = mock()

    private val service: SyncService by lazy {
        whenever(connector.descriptor()).thenReturn(
            ConnectorDescriptor(
                name = "scripted",
                sourceSystem = "scripted",
                nodeTypes = setOf("Repository"),
                edgeTypes = emptySet(),
                capabilities = setOf(Capability.FULL),
                version = "3.1.4",
            ),
        )
        val registered = RegisteredConnector(connector, enabled = true)
        val registry: AdapterRegistry = mock()
        whenever(registry.all()).thenReturn(listOf(registered))
        whenever(registry.find("scripted")).thenReturn(registered)
        SyncService(registry, writer, recorder, SyncMetrics(SimpleMeterRegistry(), clock), clock)
    }

    @Test
    fun `adds up what every page wrote and left unchanged`() {
        whenever(connector.sync(any())).thenReturn(sequenceOf(GraphDelta(), GraphDelta()))
        whenever(writer.apply(any(), any(), any()))
            .thenReturn(DeltaResult(nodesUpserted = 2, written = 2))
            .thenReturn(DeltaResult(nodesUpserted = 3, written = 1, unchanged = 2))

        service.execute("scripted", "run-1", SyncMode.FULL)

        val totals = finalTotals()
        assertThat(totals.written).isEqualTo(3)
        assertThat(totals.unchanged).isEqualTo(2)
        assertThat(totals.failed).isZero()
    }

    @Test
    fun `counts each item the connector could not read`() {
        whenever(connector.sync(any())).thenAnswer {
            sequence {
                yield(GraphDelta())
                throw PartialReadException(listOf("acme/a: cannot read package.json", "acme/b: cannot read go.mod"))
            }
        }
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult(nodesUpserted = 1, written = 1))

        service.execute("scripted", "run-1", SyncMode.FULL)

        assertThat(finalTotals().failed).isEqualTo(2)
    }

    @Test
    fun `counts a page that could not be written as one failure`() {
        whenever(connector.sync(any())).thenReturn(sequenceOf(GraphDelta(), GraphDelta()))
        whenever(writer.apply(any(), any(), any()))
            .thenThrow(IllegalStateException("constraint"))
            .thenReturn(DeltaResult(nodesUpserted = 1, written = 1))

        service.execute("scripted", "run-1", SyncMode.FULL)

        assertThat(finalTotals().failed).isEqualTo(1)
        assertThat(finalTotals().written).isEqualTo(1)
    }

    @Test
    fun `counts a run that failed outright as one failure`() {
        whenever(connector.sync(any())).thenThrow(IllegalStateException("no credentials"))

        service.execute("scripted", "run-1", SyncMode.FULL)

        assertThat(finalTotals().failed).isEqualTo(1)
    }

    /** The totals the run was finally recorded with, after the RUNNING record written at the start. */
    private fun finalTotals(): DeltaResult {
        val totals = argumentCaptor<DeltaResult>()
        verify(recorder, org.mockito.kotlin.atLeastOnce()).recordRun(
            eq("run-1"),
            any(),
            eq(SyncMode.FULL),
            any(),
            totals.capture(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        return totals.lastValue
    }
}
