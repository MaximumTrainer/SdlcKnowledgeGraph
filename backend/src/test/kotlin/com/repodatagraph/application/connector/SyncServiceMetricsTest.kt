package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import com.repodatagraph.observability.SyncMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * What [SyncService] reports about the runs it drives (#29, FR1). The service decides what happened
 * and [SyncMetrics] only counts it, so these tests are about the decisions reaching the meters: a
 * run's status and totals, each page, each kind of failure, and what became of a webhook.
 */
class SyncServiceMetricsTest {
    private val now = Instant.parse("2026-09-29T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val meters = SimpleMeterRegistry()
    private val writer: GraphDeltaWriter = mock()
    private val recorder: SyncRunRecorder = mock()
    private val connector: SourceConnector = mock()

    private val descriptor =
        ConnectorDescriptor(
            name = "scripted",
            sourceSystem = "scripted-system",
            nodeTypes = setOf("Repository"),
            edgeTypes = emptySet(),
            capabilities = setOf(Capability.FULL, Capability.INCREMENTAL, Capability.WEBHOOK),
        )

    private val service: SyncService by lazy {
        whenever(connector.descriptor()).thenReturn(descriptor)
        val registered = RegisteredConnector(connector, enabled = true)
        val registry: AdapterRegistry = mock()
        whenever(registry.all()).thenReturn(listOf(registered))
        whenever(registry.find("scripted")).thenReturn(registered)
        SyncService(registry, writer, recorder, SyncMetrics(meters, clock), clock)
    }

    private fun pages(vararg pages: () -> GraphDelta) {
        whenever(connector.sync(any())).thenAnswer { pages.asSequence().map { it() } }
    }

    private fun count(
        name: String,
        vararg tags: String,
    ) = meters
        .find(name)
        .tags("connector", "scripted", *tags)
        .counters()
        .sumOf { it.count() }

    private fun gauge(name: String) = checkNotNull(meters.find(name).tag("connector", "scripted").gauge()).value()

    private val onePage = GraphDelta(nodes = listOf(NodeUpsert("Repository", mapOf("url" to "https://github.com/acme/one"))))

    @Test
    fun `every registered connector has its gauges before it first runs`() {
        service

        assertThat(gauge("sdlc.sync.in.progress")).isEqualTo(0.0)
        assertThat(gauge("sdlc.sync.freshness")).isNaN()
    }

    @Test
    fun `a successful run is counted with what it wrote, and the connector is fresh`() {
        pages({ onePage })
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult(nodesUpserted = 3, edgesUpserted = 2))

        service.execute("scripted", "run-1", SyncMode.INCREMENTAL)

        assertThat(count("sdlc.sync.runs", "mode", "INCREMENTAL", "status", "SUCCESS")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.nodes.upserted")).isEqualTo(3.0)
        assertThat(count("sdlc.sync.edges.upserted")).isEqualTo(2.0)
        assertThat(count("sdlc.sync.pages")).isEqualTo(1.0)
        assertThat(
            meters
                .find("sdlc.sync.duration")
                .tags("connector", "scripted", "mode", "INCREMENTAL")
                .timer()
                ?.count(),
        ).isEqualTo(1)
        assertThat(gauge("sdlc.sync.freshness")).isEqualTo(0.0)
    }

    @Test
    fun `a page that cannot be read is a page error, and the run is partial and not fresh`() {
        pages({ onePage }, { throw IllegalStateException("fell over") })
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult(nodesUpserted = 1))

        service.execute("scripted", "run-1", SyncMode.FULL)

        assertThat(count("sdlc.sync.errors", "kind", "page")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.runs", "mode", "FULL", "status", "PARTIAL")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.pages")).isEqualTo(1.0)
        assertThat(gauge("sdlc.sync.freshness")).isNaN()
    }

    @Test
    fun `a page that cannot be written is a page error too`() {
        pages({ onePage })
        whenever(writer.apply(any(), any(), any())).thenThrow(IllegalStateException("bad node"))

        service.execute("scripted", "run-1", SyncMode.FULL)

        assertThat(count("sdlc.sync.errors", "kind", "page")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.runs", "status", "PARTIAL")).isEqualTo(1.0)
    }

    @Test
    fun `a run that fails as a whole is a run error`() {
        whenever(connector.sync(any())).thenThrow(IllegalStateException("no credentials"))

        service.execute("scripted", "run-1", SyncMode.FULL)

        assertThat(count("sdlc.sync.errors", "kind", "run")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.runs", "mode", "FULL", "status", "FAILED")).isEqualTo(1.0)
    }

    @Test
    fun `a run shows as in progress from when it starts until it ends`() {
        pages({ GraphDelta() })
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult())

        val runId = service.start("scripted", SyncMode.FULL)
        assertThat(gauge("sdlc.sync.in.progress")).isEqualTo(1.0)

        service.execute("scripted", runId, SyncMode.FULL)
        assertThat(gauge("sdlc.sync.in.progress")).isEqualTo(0.0)
    }

    @Test
    fun `a webhook that becomes a run is applied, and its run is counted as a webhook run`() {
        whenever(connector.onWebhook(any())).thenReturn(onePage)
        whenever(writer.apply(any(), any(), any())).thenReturn(DeltaResult(nodesUpserted = 1))

        service.applyWebhook("scripted", webhook())

        assertThat(count("sdlc.webhook.events", "result", "applied")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.runs", "mode", "WEBHOOK", "status", "SUCCESS")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.nodes.upserted")).isEqualTo(1.0)
    }

    @Test
    fun `a webhook that could not be written is applied as a failed run, and a run error`() {
        whenever(connector.onWebhook(any())).thenReturn(onePage)
        whenever(writer.apply(any(), any(), any())).thenThrow(IllegalStateException("bad node"))

        service.applyWebhook("scripted", webhook())

        assertThat(count("sdlc.webhook.events", "result", "applied")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.runs", "mode", "WEBHOOK", "status", "FAILED")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.errors", "kind", "run")).isEqualTo(1.0)
    }

    @Test
    fun `a webhook the connector finds nothing in is ignored`() {
        whenever(connector.onWebhook(any())).thenReturn(null)

        service.applyWebhook("scripted", webhook())

        assertThat(count("sdlc.webhook.events", "result", "ignored")).isEqualTo(1.0)
        assertThat(count("sdlc.sync.runs")).isEqualTo(0.0)
    }

    @Test
    fun `a delivery that was already applied is ignored rather than applied twice`() {
        whenever(connector.deliveryId(any())).thenReturn("delivery-1")
        whenever(recorder.runForDelivery("scripted", "delivery-1")).thenReturn("run-0")

        service.applyWebhook("scripted", webhook())

        assertThat(count("sdlc.webhook.events", "result", "ignored")).isEqualTo(1.0)
        assertThat(count("sdlc.webhook.events", "result", "applied")).isEqualTo(0.0)
    }

    @Test
    fun `freshness is seeded from the last success the graph recorded`() {
        whenever(recorder.lastSuccessAt("scripted")).thenReturn(now.minusSeconds(120))

        service

        assertThat(gauge("sdlc.sync.freshness")).isEqualTo(120.0)
    }

    private fun webhook() = WebhookEvent("scripted", emptyMap(), ByteArray(0))
}
