package com.repodatagraph.observability

import com.repodatagraph.domain.port.out.connector.SyncMode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * What a connector's runs look like from outside (#29, FR1): counted by how they ended, timed in
 * buckets an alert can use, and how long it has been since each connector last succeeded. Tags are
 * bounded by the connectors that were registered, so a name nobody registered never makes a series.
 */
class SyncMetricsTest {
    private val meters = SimpleMeterRegistry()
    private val clock = MutableClock(Instant.parse("2026-09-29T12:00:00Z"))
    private val metrics = SyncMetrics(meters, clock)

    private var running = false
    private var stored: Instant? = null
    private var seedReads = 0

    private fun register(modes: Set<SyncMode> = setOf(SyncMode.FULL, SyncMode.INCREMENTAL, SyncMode.WEBHOOK)) =
        metrics.register(
            connector = "fake",
            sourceSystem = "fake-system",
            modes = modes,
            running = { running },
            lastSuccess = {
                seedReads++
                stored
            },
        )

    private fun counter(
        name: String,
        vararg tags: String,
    ) = meters
        .find(name)
        .tags(*tags)
        .counter()
        ?.count()

    private fun freshness() = checkNotNull(meters.find("sdlc.sync.freshness").tag("connector", "fake").gauge()).value()

    private fun inProgress() = checkNotNull(meters.find("sdlc.sync.in.progress").tag("connector", "fake").gauge()).value()

    @Test
    fun `a registered connector has its series from the start, at zero`() {
        register()

        assertThat(inProgress()).isEqualTo(0.0)
        assertThat(counter("sdlc.sync.runs", "connector", "fake", "mode", "FULL", "status", "FAILED")).isEqualTo(0.0)
        assertThat(counter("sdlc.sync.errors", "connector", "fake", "kind", "page")).isEqualTo(0.0)
        assertThat(counter("sdlc.webhook.events", "connector", "fake", "result", "rejected")).isEqualTo(0.0)
    }

    @Test
    fun `a connector that does not take webhooks has no webhook series`() {
        register(modes = setOf(SyncMode.FULL))

        assertThat(meters.find("sdlc.webhook.events").counters()).isEmpty()
        assertThat(meters.find("sdlc.sync.runs").tag("mode", "WEBHOOK").counters()).isEmpty()
    }

    @Test
    fun `a finished run is counted by mode and status, timed, and adds what it wrote`() {
        register()

        metrics.runFinished("fake", SyncMode.FULL, "SUCCESS", Duration.ofSeconds(3), nodes = 3, edges = 2, tombstones = 1)

        assertThat(counter("sdlc.sync.runs", "connector", "fake", "sourceSystem", "fake-system", "mode", "FULL", "status", "SUCCESS"))
            .isEqualTo(1.0)
        val timer = checkNotNull(meters.find("sdlc.sync.duration").tags("connector", "fake", "mode", "FULL").timer())
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(3.0)
        assertThat(counter("sdlc.sync.nodes.upserted", "connector", "fake")).isEqualTo(3.0)
        assertThat(counter("sdlc.sync.edges.upserted", "connector", "fake")).isEqualTo(2.0)
        assertThat(counter("sdlc.sync.tombstones", "connector", "fake")).isEqualTo(1.0)
    }

    @Test
    fun `durations are bucketed at 1s, 5s, 30s, 2m and 10m`() {
        register()
        metrics.runFinished("fake", SyncMode.FULL, "SUCCESS", Duration.ofSeconds(2), nodes = 0, edges = 0, tombstones = 0)

        val timer = checkNotNull(meters.find("sdlc.sync.duration").tags("connector", "fake", "mode", "FULL").timer())
        val buckets = timer.takeSnapshot().histogramCounts().map { it.bucket(TimeUnit.SECONDS) }
        assertThat(buckets).containsExactly(1.0, 5.0, 30.0, 120.0, 600.0)
    }

    @Test
    fun `pages and errors are counted by connector, errors by kind`() {
        register()

        metrics.page("fake")
        metrics.page("fake")
        metrics.error("fake", SyncErrorKind.PAGE)
        metrics.error("fake", SyncErrorKind.RUN)

        assertThat(counter("sdlc.sync.pages", "connector", "fake", "sourceSystem", "fake-system")).isEqualTo(2.0)
        assertThat(counter("sdlc.sync.errors", "connector", "fake", "kind", "page")).isEqualTo(1.0)
        assertThat(counter("sdlc.sync.errors", "connector", "fake", "kind", "run")).isEqualTo(1.0)
    }

    @Test
    fun `a rejected webhook is an event and an error of its own kind`() {
        register()

        metrics.webhook("fake", WebhookResult.REJECTED)
        metrics.webhook("fake", WebhookResult.APPLIED)
        metrics.webhook("fake", WebhookResult.IGNORED)

        assertThat(counter("sdlc.webhook.events", "connector", "fake", "result", "rejected")).isEqualTo(1.0)
        assertThat(counter("sdlc.webhook.events", "connector", "fake", "result", "applied")).isEqualTo(1.0)
        assertThat(counter("sdlc.webhook.events", "connector", "fake", "result", "ignored")).isEqualTo(1.0)
        assertThat(counter("sdlc.sync.errors", "connector", "fake", "kind", "webhook_signature")).isEqualTo(1.0)
    }

    @Test
    fun `in progress reads whether a run is going now`() {
        register()

        running = true
        assertThat(inProgress()).isEqualTo(1.0)
        running = false
        assertThat(inProgress()).isEqualTo(0.0)
    }

    @Test
    fun `freshness is not a number for a connector that has never succeeded`() {
        register()

        assertThat(freshness()).isNaN()
    }

    @Test
    fun `freshness is the seconds since the last success, and is in seconds`() {
        register()
        metrics.succeeded("fake", clock.instant())

        clock.advance(Duration.ofSeconds(90))

        assertThat(freshness()).isEqualTo(90.0)
        assertThat(
            meters
                .find("sdlc.sync.freshness")
                .gauge()
                ?.id
                ?.baseUnit,
        ).isEqualTo("seconds")
    }

    @Test
    fun `freshness is seeded once from what the graph remembers, not read on every scrape`() {
        stored = clock.instant().minusSeconds(600)
        register()

        assertThat(freshness()).isEqualTo(600.0)
        assertThat(freshness()).isEqualTo(600.0)
        assertThat(seedReads).isEqualTo(1)
    }

    @Test
    fun `a seed that fails reads as unknown and is tried again at the next scrape`() {
        var failing = true
        metrics.register("fake", "fake-system", setOf(SyncMode.FULL), running = { false }) {
            check(!failing) { "Neo4j is down" }
            clock.instant().minusSeconds(60)
        }

        assertThat(freshness()).isNaN()
        failing = false
        assertThat(freshness()).isEqualTo(60.0)
    }

    @Test
    fun `a success recorded here replaces what was seeded`() {
        stored = clock.instant().minusSeconds(600)
        register()
        freshness()

        metrics.succeeded("fake", clock.instant())

        assertThat(freshness()).isEqualTo(0.0)
    }

    @Test
    fun `a connector that was never registered makes no series`() {
        register()

        metrics.runFinished("unregistered", SyncMode.FULL, "SUCCESS", Duration.ZERO, nodes = 1, edges = 0, tombstones = 0)
        metrics.page("unregistered")
        metrics.error("unregistered", SyncErrorKind.PAGE)
        metrics.webhook("unregistered", WebhookResult.REJECTED)
        metrics.succeeded("unregistered", clock.instant())

        assertThat(meters.meters.map { it.id.getTag("connector") }).containsOnly("fake")
    }

    /** A clock a test can move, because freshness is a difference between two readings of it. */
    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        fun advance(by: Duration) {
            now = now.plus(by)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }
}
