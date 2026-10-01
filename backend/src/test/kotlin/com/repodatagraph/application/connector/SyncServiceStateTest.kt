package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import com.repodatagraph.observability.SyncMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Every finished scheduled or manual run is written to the connector's state (#29, FR3), whatever
 * its status: a state that only heard about successes could not count the failures since the last
 * one. What the state then does with a failure is [SyncRunRecorder]'s business.
 */
class SyncServiceStateTest {
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
                capabilities = setOf(Capability.FULL, Capability.WEBHOOK),
            ),
        )
        val registered = RegisteredConnector(connector, enabled = true)
        val registry: AdapterRegistry = mock()
        whenever(registry.all()).thenReturn(listOf(registered))
        whenever(registry.find("scripted")).thenReturn(registered)
        SyncService(registry, writer, recorder, SyncMetrics(SimpleMeterRegistry(), clock), clock)
    }

    @Test
    fun `a partial run is written to the state, without a watermark`() {
        whenever(connector.sync(any())).thenAnswer {
            sequenceOf<() -> GraphDelta>({ GraphDelta() }, { throw IllegalStateException("fell over") }).map { it() }
        }
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult())

        service.execute("scripted", "run-1", SyncMode.FULL)

        verify(recorder).recordState(eq("scripted"), eq("run-1"), eq(RunStatus.PARTIAL), isNull())
    }

    @Test
    fun `a failed run is written to the state`() {
        whenever(connector.sync(any())).thenThrow(IllegalStateException("no credentials"))

        service.execute("scripted", "run-1", SyncMode.FULL)

        verify(recorder).recordState(eq("scripted"), eq("run-1"), eq(RunStatus.FAILED), isNull())
    }

    @Test
    fun `the state is written before the run says it has finished`() {
        whenever(connector.sync(any())).thenReturn(sequenceOf(GraphDelta(watermark = WATERMARK)))
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult())

        service.execute("scripted", "run-1", SyncMode.FULL)

        // Whoever waits for the run to finish then reads the state, so the state has to be there first.
        inOrder(recorder) {
            verify(recorder).recordState(eq("scripted"), eq("run-1"), eq(RunStatus.SUCCESS), eq(WATERMARK))
            verify(recorder).recordRun(eq("run-1"), any(), any(), eq(RunStatus.SUCCESS), any(), eq(WATERMARK), isNull(), isNull())
        }
    }

    @Test
    fun `the connector is still running while the run's outcome is being written down`() {
        whenever(connector.sync(any())).thenReturn(sequenceOf(GraphDelta(watermark = WATERMARK)))
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult())
        val runningWhileWritten = mutableListOf<Boolean>()
        doAnswer { runningWhileWritten += service.isRunning("scripted") }
            .whenever(recorder)
            .recordState(any(), any(), any(), anyOrNull())
        doAnswer { runningWhileWritten += service.isRunning("scripted") }
            .whenever(recorder)
            .recordRun(any(), any(), any(), eq(RunStatus.SUCCESS), any(), anyOrNull(), anyOrNull(), anyOrNull())

        val runId = service.start("scripted", SyncMode.FULL)
        service.execute("scripted", runId, SyncMode.FULL)

        assertTrue(runningWhileWritten.size == 2 && runningWhileWritten.all { it }) {
            "the connector was released before its run was written down: $runningWhileWritten"
        }
        assertTrue(!service.isRunning("scripted")) { "the connector was never released" }
    }

    @Test
    fun `a webhook run leaves the state alone, since one event says nothing about the rest`() {
        whenever(connector.onWebhook(any())).thenReturn(GraphDelta())
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult())

        service.applyWebhook("scripted", WebhookEvent("scripted", emptyMap(), ByteArray(0)))

        verify(recorder, never()).recordState(any(), any(), any(), anyOrNull())
    }

    private companion object {
        val WATERMARK: Instant = Instant.parse("2026-09-01T10:00:00Z")
    }
}
