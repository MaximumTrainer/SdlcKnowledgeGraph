package com.repodatagraph.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status

/**
 * Whether the API can reach what it depends on, as `sdlc_dependency_up{dependency}` (#44, FR9): 1
 * while the dependency's health check says UP and 0 otherwise, read afresh on every scrape. The
 * actuator's health is not a metric, and an alert can only read metrics; this is the bridge the
 * Neo4jUnreachable alert reads.
 */
class DependencyHealthMetrics(
    private val indicators: Map<String, HealthIndicator>,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        indicators.forEach { (dependency, indicator) ->
            Gauge
                .builder("sdlc.dependency.up", indicator) { status(it) }
                .description("1 while the dependency's health check is UP, otherwise 0")
                .tag("dependency", dependency)
                .strongReference(true)
                .register(registry)
        }
    }

    private fun status(indicator: HealthIndicator): Double =
        runCatching { indicator.health().status }
            .map { if (it == Status.UP) 1.0 else 0.0 }
            .getOrDefault(0.0)
}
