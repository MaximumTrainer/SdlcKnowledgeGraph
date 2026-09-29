package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.SourceConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * How fresh a connector is, and when that makes it stale (#29, FR3 and FR4).
 *
 * Stale means an enabled connector whose last success is older than its threshold. One that has never
 * succeeded is measured from when this instance started watching it instead, so a fresh deployment
 * gets one threshold to finish its first run before it is called stale.
 */
class FreshnessCalculatorTest {
    private val started = Instant.parse("2026-09-29T12:00:00Z")
    private val clock = MovableClock(started)
    private val recorder: SyncRunRecorder = mock()
    private val registry: AdapterRegistry = mock()
    private val calculator by lazy { FreshnessCalculator(registry, recorder, clock) }

    private fun connector(
        name: String = "scheduled",
        enabled: Boolean = true,
        capabilities: Set<Capability> = setOf(Capability.FULL),
        threshold: Duration? = null,
    ): RegisteredConnector {
        val connector: SourceConnector = mock()
        whenever(connector.descriptor()).thenReturn(
            ConnectorDescriptor(
                name = name,
                sourceSystem = name,
                nodeTypes = emptySet(),
                edgeTypes = emptySet(),
                capabilities = capabilities,
            ),
        )
        return RegisteredConnector(connector, enabled, threshold)
    }

    @Test
    fun `a scheduled connector allows two hours by default`() {
        assertThat(connector().freshnessThreshold).isEqualTo(Duration.ofHours(2))
    }

    @Test
    fun `a connector that takes webhooks allows a day between its scheduled runs by default`() {
        val webhooks = connector(capabilities = setOf(Capability.FULL, Capability.WEBHOOK))

        assertThat(webhooks.freshnessThreshold).isEqualTo(Duration.ofHours(24))
    }

    @Test
    fun `a configured threshold wins over the default`() {
        val configured = connector(capabilities = setOf(Capability.WEBHOOK), threshold = Duration.ofMinutes(30))

        assertThat(configured.freshnessThreshold).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `a success within the threshold is fresh, with its age`() {
        val scheduled = connector()
        whenever(recorder.lastSuccessAt("scheduled")).thenReturn(started.minus(Duration.ofMinutes(90)))

        val freshness = calculator.of(scheduled)

        assertThat(freshness.lastSuccessAt).isEqualTo(started.minus(Duration.ofMinutes(90)))
        assertThat(freshness.age).isEqualTo(Duration.ofMinutes(90))
        assertThat(freshness.threshold).isEqualTo(Duration.ofHours(2))
        assertThat(freshness.stale).isFalse()
    }

    @Test
    fun `a success older than the threshold is stale`() {
        val scheduled = connector()
        whenever(recorder.lastSuccessAt("scheduled")).thenReturn(started.minus(Duration.ofHours(3)))

        assertThat(calculator.of(scheduled).stale).isTrue()
    }

    @Test
    fun `a disabled connector is never stale, however old its success`() {
        val disabled = connector(enabled = false)
        whenever(recorder.lastSuccessAt("scheduled")).thenReturn(started.minus(Duration.ofDays(30)))

        val freshness = calculator.of(disabled)

        assertThat(freshness.stale).isFalse()
        assertThat(freshness.age).isEqualTo(Duration.ofDays(30))
    }

    @Test
    fun `a connector that never succeeded has no age and is not stale within its first threshold`() {
        val scheduled = connector()
        calculator
        clock.advance(Duration.ofMinutes(119))

        val freshness = calculator.of(scheduled)

        assertThat(freshness.lastSuccessAt).isNull()
        assertThat(freshness.age).isNull()
        assertThat(freshness.stale).isFalse()
    }

    @Test
    fun `a connector that never succeeded is stale once the instance has waited longer than its threshold`() {
        val scheduled = connector()
        calculator
        clock.advance(Duration.ofHours(2).plusSeconds(1))

        assertThat(calculator.of(scheduled).stale).isTrue()
    }

    @Test
    fun `the stale connectors are the enabled ones past their threshold, by name`() {
        val stale = connector(name = "stale")
        val fresh = connector(name = "fresh")
        val off = connector(name = "off", enabled = false)
        whenever(registry.enabled()).thenReturn(listOf(stale, fresh))
        whenever(registry.all()).thenReturn(listOf(fresh, off, stale))
        whenever(recorder.lastSuccessAt("stale")).thenReturn(started.minus(Duration.ofHours(5)))
        whenever(recorder.lastSuccessAt("fresh")).thenReturn(started)
        whenever(recorder.lastSuccessAt("off")).thenReturn(started.minus(Duration.ofDays(9)))

        assertThat(calculator.staleConnectors()).containsExactly("stale")
    }

    /** A clock a test can move forward, since staleness is about time passing. */
    private class MovableClock(
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
