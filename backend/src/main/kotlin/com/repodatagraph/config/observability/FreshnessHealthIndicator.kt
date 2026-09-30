package com.repodatagraph.config.observability

import com.repodatagraph.domain.model.SourceLag
import com.repodatagraph.domain.port.`in`.SourceFreshnessUseCase
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.stereotype.Component

/**
 * The `freshness` component of /actuator/health (#93, FR-3): per source, when a sync of it last
 * succeeded and how far that is behind its window, and WARN while any source is past it.
 *
 * WARN, never DOWN. A source that has not synced for a day leaves an instance that still answers
 * correctly about what it has, and fly.io's check (`/actuator/health/readiness`) and the compose
 * healthcheck (`/actuator/health`) must not restart it, or refuse to start it, for that. WARN is a
 * status Spring's aggregation does not order (`management.endpoint.health.status.order`), so it
 * leaves the whole health to the other components; Spring answers 200 for a status its HTTP mapping
 * does not name, which only DOWN and OUT_OF_SERVICE are; and neither probe group includes this
 * component. `FreshnessHealthIT` holds all three, so configuring either setting to take WARN in fails
 * the build rather than the next quiet weekend.
 *
 * UNKNOWN when lag cannot be read at all, as the connectors component answers (#29): the neo4j
 * component already says what is wrong.
 */
@Component
class FreshnessHealthIndicator(
    private val sources: SourceFreshnessUseCase,
) : HealthIndicator {
    override fun health(): Health {
        val lag =
            try {
                sources.lag()
            } catch (
                // Whatever the graph store throws, the answer is the same: unknown, not down.
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                return Health.unknown().withException(failure).build()
            }
        val lagging = lag.filter { it.lagging }.map { it.source }
        return Health
            .status(if (lagging.isEmpty()) Status.UP else WARN)
            .withDetail("lagging", lagging)
            .withDetail("sources", lag.associate { it.source to detail(it) })
            .build()
    }

    private fun detail(lag: SourceLag): Map<String, Any?> =
        linkedMapOf(
            "lastSuccessAt" to lag.lastSuccessAt?.toString(),
            "lag" to lag.lag?.toString(),
            "lagSeconds" to lag.lag?.seconds,
            "window" to lag.window.toString(),
            "windowSeconds" to lag.window.seconds,
            "lagging" to lag.lagging,
        )

    companion object {
        /** Past its window: worth a look, never a reason to take the instance out of service. */
        val WARN = Status("WARN", "a source is behind its freshness window")
    }
}
