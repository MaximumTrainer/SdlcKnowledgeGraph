package com.repodatagraph.config.observability

import com.repodatagraph.application.connector.FreshnessCalculator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.actuate.health.Status

/**
 * The `connectors` health component (#29, FR4): DOWN, naming them, while any enabled connector is
 * stale; UP otherwise, including on an instance with no connector enabled at all.
 */
class ConnectorsHealthIndicatorTest {
    private val freshness: FreshnessCalculator = mock()
    private val indicator = ConnectorsHealthIndicator(freshness)

    @Test
    fun `is up when nothing is stale`() {
        whenever(freshness.staleConnectors()).thenReturn(emptyList())

        assertThat(indicator.health().status).isEqualTo(Status.UP)
    }

    @Test
    fun `is down and names every stale connector`() {
        whenever(freshness.staleConnectors()).thenReturn(listOf("github", "servicenow"))

        val health = indicator.health()

        assertThat(health.status).isEqualTo(Status.DOWN)
        assertThat(health.details).containsEntry("stale", listOf("github", "servicenow"))
    }

    @Test
    fun `is unknown rather than down when freshness cannot be read`() {
        whenever(freshness.staleConnectors()).thenThrow(IllegalStateException("Neo4j is unreachable"))

        assertThat(indicator.health().status).isEqualTo(Status.UNKNOWN)
    }
}
