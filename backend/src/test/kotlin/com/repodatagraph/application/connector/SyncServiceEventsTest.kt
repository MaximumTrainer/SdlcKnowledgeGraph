package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import com.repodatagraph.observability.SyncMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * A finished run is announced (#28, FR7), so whatever reads the graph a connector just wrote - the
 * link engine - can follow it. Announced after the run is recorded as finished, never before: a
 * listener reading the run back must find it ended.
 */
class SyncServiceEventsTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC)
    private val writer: GraphDeltaWriter = mock()
    private val recorder: SyncRunRecorder = mock()
    private val connector: SourceConnector = mock()
    private val announced = mutableListOf<Any>()
    private var finishedWhenAnnounced = false
    private val events =
        ApplicationEventPublisher { event ->
            announced += event
            finishedWhenAnnounced =
                mockingDetails(recorder).invocations.any { it.method.name == "recordRun" && it.arguments[3] != RunStatus.RUNNING }
        }

    private val service: SyncService by lazy {
        whenever(connector.descriptor()).thenReturn(
            ConnectorDescriptor(
                name = "github",
                sourceSystem = "github",
                nodeTypes = setOf("Repository"),
                edgeTypes = emptySet(),
                capabilities = setOf(Capability.FULL, Capability.WEBHOOK),
            ),
        )
        val registered = RegisteredConnector(connector, enabled = true)
        val registry: AdapterRegistry = mock()
        whenever(registry.all()).thenReturn(listOf(registered))
        whenever(registry.find("github")).thenReturn(registered)
        SyncService(registry, writer, recorder, SyncMetrics(SimpleMeterRegistry(), clock), clock, events)
    }

    @Test
    fun `a finished run is announced with its connector, mode and status, after it is recorded`() {
        whenever(connector.sync(any())).thenReturn(sequenceOf(GraphDelta()))
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult(nodesUpserted = 1))

        service.execute("github", "run-1", SyncMode.FULL)

        assertEquals(listOf<Any>(SyncRunCompleted("github", "run-1", SyncMode.FULL, RunStatus.SUCCESS)), announced)
        assertTrue(finishedWhenAnnounced, "the run was announced before it was recorded as finished")
    }

    @Test
    fun `a failed run is announced as failed`() {
        whenever(connector.sync(any())).thenThrow(IllegalStateException("no credentials"))

        service.execute("github", "run-2", SyncMode.FULL)

        assertEquals(listOf<Any>(SyncRunCompleted("github", "run-2", SyncMode.FULL, RunStatus.FAILED)), announced)
    }

    @Test
    fun `an applied webhook is announced as a WEBHOOK run`() {
        val event = WebhookEvent(connector = "github", headers = emptyMap(), body = "{}".toByteArray())
        whenever(connector.deliveryId(any())).thenReturn(null)
        whenever(connector.onWebhook(any())).thenReturn(GraphDelta())
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult(nodesUpserted = 1))

        val runId = service.applyWebhook("github", event)

        assertEquals(listOf<Any>(SyncRunCompleted("github", runId!!, SyncMode.WEBHOOK, RunStatus.SUCCESS)), announced)
    }
}
