package com.repodatagraph.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

/**
 * Whether the API can reach what it depends on, as `sdlc_dependency_up{dependency}` (#44, FR9): 1
 * while the dependency's health check says UP, 0 otherwise, read afresh on every scrape. The
 * Neo4jUnreachable alert reads it, because a health check an alert cannot see is one nobody hears.
 */
class DependencyHealthMetricsTest {
    private val meters = SimpleMeterRegistry()
    private var neo4j: Health = Health.up().build()

    private fun up() = checkNotNull(meters.find("sdlc.dependency.up").tag("dependency", "neo4j").gauge()).value()

    init {
        DependencyHealthMetrics(mapOf("neo4j" to HealthIndicator { neo4j })).bindTo(meters)
    }

    @Test
    fun `is 1 while the dependency is up`() {
        assertThat(up()).isEqualTo(1.0)
    }

    @Test
    fun `is 0 while it is down, and follows it back up`() {
        neo4j = Health.down().build()
        assertThat(up()).isEqualTo(0.0)

        neo4j = Health.up().build()
        assertThat(up()).isEqualTo(1.0)
    }

    @Test
    fun `is 0 when the check itself throws`() {
        val broken = SimpleMeterRegistry()
        DependencyHealthMetrics(mapOf("neo4j" to HealthIndicator { error("driver closed") })).bindTo(broken)

        assertThat(checkNotNull(broken.find("sdlc.dependency.up").gauge()).value()).isEqualTo(0.0)
    }
}
