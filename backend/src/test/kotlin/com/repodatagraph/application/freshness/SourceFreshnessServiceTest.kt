package com.repodatagraph.application.freshness

import com.repodatagraph.application.connector.AdapterRegistry
import com.repodatagraph.application.connector.RegisteredConnector
import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.port.out.SyncRunStore
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
 * How far behind each source is (#93, FR-3): the end of its last successful sync run, whatever kind
 * of run, against its freshness window.
 *
 * A source is reported when a run of it has succeeded or a connector for it is enabled; the rest -
 * `manual`, say - have nothing to lag. One whose connector has never succeeded is measured from when
 * this instance started watching, as #29 measures a connector, so a new deployment is not behind
 * before its first run has had a window to happen.
 */
class SourceFreshnessServiceTest {
    private val started = Instant.parse("2026-09-30T12:00:00Z")
    private val clock = MovableClock(started)
    private val syncRuns: SyncRunStore = mock()
    private val registry: AdapterRegistry = mock()
    private val policy =
        FreshnessPolicy(
            Duration.ofHours(24),
            mapOf("aws" to Duration.ofHours(6)),
            listOf("manual", "github", "servicenow", "aws", "dogfood-seed"),
        )
    private val service by lazy { SourceFreshnessService(policy, registry, syncRuns, clock) }

    private fun connector(
        name: String,
        source: String = name,
        enabled: Boolean = true,
    ): RegisteredConnector {
        val connector: SourceConnector = mock()
        whenever(connector.descriptor()).thenReturn(
            ConnectorDescriptor(name, source, emptySet(), emptySet(), setOf(Capability.FULL)),
        )
        return RegisteredConnector(connector, enabled)
    }

    private fun given(
        connectors: List<RegisteredConnector> = emptyList(),
        lastSuccess: Map<String, Instant> = emptyMap(),
    ) {
        whenever(registry.all()).thenReturn(connectors)
        whenever(registry.enabled()).thenReturn(connectors.filter { it.enabled })
        whenever(syncRuns.lastSuccessBySource()).thenReturn(lastSuccess)
    }

    @Test
    fun `a source whose last success is older than its window is lagging, by how much`() {
        given(lastSuccess = mapOf("github" to started.minus(Duration.ofHours(30))))

        val github = service.lag().single()

        assertThat(github.source).isEqualTo("github")
        assertThat(github.window).isEqualTo(Duration.ofHours(24))
        assertThat(github.lastSuccessAt).isEqualTo(started.minus(Duration.ofHours(30)))
        assertThat(github.lag).isEqualTo(Duration.ofHours(30))
        assertThat(github.lagging).isTrue()
    }

    @Test
    fun `a source within its window is not`() {
        given(lastSuccess = mapOf("aws" to started.minus(Duration.ofHours(5))))

        assertThat(service.lag().single().lagging).isFalse()
    }

    @Test
    fun `each source is measured against its own window`() {
        given(lastSuccess = mapOf("aws" to started.minus(Duration.ofHours(9)), "github" to started.minus(Duration.ofHours(9))))

        assertThat(service.lag().associate { it.source to it.lagging }).containsExactly(
            org.assertj.core.api.Assertions
                .entry("github", false),
            org.assertj.core.api.Assertions
                .entry("aws", true),
        )
    }

    @Test
    fun `sources are listed in declaration order, and only those with a run or an enabled connector`() {
        given(
            connectors = listOf(connector("servicenow"), connector("github", enabled = false)),
            lastSuccess = mapOf("dogfood-seed" to started, "aws" to started),
        )

        assertThat(service.lag().map { it.source }).containsExactly("servicenow", "aws", "dogfood-seed")
    }

    @Test
    fun `a source whose connector has never succeeded is not lagging within its first window`() {
        given(connectors = listOf(connector("servicenow")))
        service.lag()
        clock.advance(Duration.ofHours(23))

        val servicenow = service.lag().single()

        assertThat(servicenow.lastSuccessAt).isNull()
        assertThat(servicenow.lag).isNull()
        assertThat(servicenow.lagging).isFalse()
    }

    @Test
    fun `and is once a whole window has passed without one`() {
        given(connectors = listOf(connector("servicenow")))
        service.lag()
        clock.advance(Duration.ofHours(25))

        assertThat(service.lag().single().lagging).isTrue()
    }

    @Test
    fun `a success in the future, from a skewed clock, is no lag rather than a negative one`() {
        given(lastSuccess = mapOf("github" to started.plus(Duration.ofMinutes(5))))

        assertThat(service.lag().single().lag).isEqualTo(Duration.ZERO)
    }

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
