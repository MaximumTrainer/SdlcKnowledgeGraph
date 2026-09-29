package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * When a sync may close what its source stopped reporting (#150).
 *
 * Only a full run that succeeded saw everything, so only it can tell "gone" from "not looked at". A
 * partial run that tombstoned the estate it failed to read would be the most destructive bug this
 * system could have, so each way of not reconciling is asserted as well as the one way of doing it.
 */
class SyncServiceReconciliationTest {
    private val runStarted = Instant.parse("2026-09-01T11:00:00Z")
    private val clock = Clock.fixed(runStarted, ZoneOffset.UTC)
    private val writer: GraphDeltaWriter = mock()
    private val recorder: SyncRunRecorder = mock()

    private fun service(
        pages: List<() -> GraphDelta>,
        fullSyncIsComplete: Boolean = true,
    ): Pair<SyncService, ConnectorDescriptor> {
        val descriptor =
            ConnectorDescriptor(
                name = "scripted",
                sourceSystem = "scripted",
                nodeTypes = setOf("Repository"),
                edgeTypes = emptySet(),
                capabilities = setOf(Capability.FULL, Capability.INCREMENTAL),
                fullSyncIsComplete = fullSyncIsComplete,
            )
        val connector: SourceConnector = mock()
        whenever(connector.descriptor()).thenReturn(descriptor)
        whenever(connector.sync(any())).thenAnswer { pages.asSequence().map { it() } }
        val registry: AdapterRegistry = mock()
        whenever(registry.find("scripted")).thenReturn(RegisteredConnector(connector, enabled = true))
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult(nodesUpserted = 1))
        return SyncService(registry, writer, recorder, SyncMetrics(SimpleMeterRegistry(), clock), clock) to descriptor
    }

    private val onePage: List<() -> GraphDelta> = listOf({ GraphDelta() })

    @Test
    fun `a full run that succeeds closes what its source asserted before the run began`() {
        val (service, descriptor) = service(onePage)
        whenever(writer.reconcile(descriptor, runStarted)).thenReturn(3)

        service.execute("scripted", "run-1", SyncMode.FULL)

        verify(writer).reconcile(descriptor, runStarted)
        val totals = argumentCaptor<DeltaResult>()
        verify(recorder).recordRun(
            eq("run-1"),
            any(),
            eq(SyncMode.FULL),
            eq(RunStatus.SUCCESS),
            totals.capture(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        assertThat(totals.firstValue.tombstones).describedAs("the run records what it closed").isEqualTo(3)
    }

    @Test
    fun `a partial run closes nothing`() {
        val (service, _) = service(listOf({ GraphDelta() }, { throw IllegalStateException("fell over") }))

        service.execute("scripted", "run-1", SyncMode.FULL)

        verify(writer, never()).reconcile(any(), any())
    }

    @Test
    fun `an incremental run closes nothing, because it did not look at everything`() {
        val (service, _) = service(onePage)

        service.execute("scripted", "run-1", SyncMode.INCREMENTAL)

        verify(writer, never()).reconcile(any(), any())
    }

    @Test
    fun `a connector whose full sync is scoped rather than complete closes nothing`() {
        val (service, _) = service(onePage, fullSyncIsComplete = false)

        service.execute("scripted", "run-1", SyncMode.FULL)

        verify(writer, never()).reconcile(any(), any())
    }
}
