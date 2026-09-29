package com.repodatagraph.config.observability

import com.repodatagraph.application.connector.FreshnessCalculator
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * The `connectors` component of /actuator/health (#29, FR4): DOWN, with `stale` naming them, while
 * any enabled connector is past its freshness threshold, and UP otherwise - including on an instance
 * with no connector enabled at all, which has nothing to be stale about.
 *
 * It is a component of the overall health, not of the readiness probe: an instance with an old graph
 * still answers correctly about what it has, and taking it out of service would turn "stale" into
 * "down". [FreshnessReadiness] adds it to readiness only when an operator opts in.
 *
 * When freshness cannot be read at all - Neo4j unreachable, say - it answers UNKNOWN rather than
 * DOWN. The neo4j component already says what is wrong; this one does not know, and saying DOWN would
 * send whoever reads it looking at the connectors.
 */
@Component
class ConnectorsHealthIndicator(
    private val freshness: FreshnessCalculator,
) : HealthIndicator {
    override fun health(): Health {
        val stale =
            try {
                freshness.staleConnectors()
            } catch (
                // Whatever the graph store throws, the answer is the same: unknown, not down.
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                return Health.unknown().withException(failure).build()
            }
        return if (stale.isEmpty()) Health.up().build() else Health.down().withDetail("stale", stale).build()
    }
}
