package com.repodatagraph.config.observability

import com.repodatagraph.domain.model.SourceLag
import com.repodatagraph.domain.port.`in`.SourceFreshnessUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.actuate.health.Status
import java.time.Duration
import java.time.Instant

/**
 * The `freshness` health component (#93, FR-3): per source, when it last synced successfully and how
 * far that is behind its window. A source past its window turns it WARN, never DOWN: lag is a reason
 * to look, not a reason to take the instance out of service, and a status Spring does not order
 * leaves the whole health, and its HTTP status, to the other components.
 */
class FreshnessHealthIndicatorTest {
    private val sources: SourceFreshnessUseCase = mock()
    private val indicator = FreshnessHealthIndicator(sources)

    private val lastSync = Instant.parse("2026-09-29T06:00:00Z")
    private val github = SourceLag("github", Duration.ofHours(24), lastSync, Duration.ofHours(30), lagging = true)
    private val aws = SourceLag("aws", Duration.ofHours(6), null, null, lagging = false)

    @Test
    fun `is UP, with each source's lag, when none is behind`() {
        whenever(sources.lag()).thenReturn(listOf(aws))

        val health = indicator.health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details).containsEntry("lagging", emptyList<String>())
        @Suppress("UNCHECKED_CAST")
        val detail = (health.details["sources"] as Map<String, Map<String, Any?>>).getValue("aws")
        assertThat(detail).containsEntry("lastSuccessAt", null).containsEntry("windowSeconds", 21600L)
    }

    @Test
    fun `is WARN, naming every source behind, when one is`() {
        whenever(sources.lag()).thenReturn(listOf(github, aws))

        val health = indicator.health()

        assertThat(health.status).isEqualTo(FreshnessHealthIndicator.WARN)
        assertThat(health.status.code).isEqualTo("WARN")
        assertThat(health.details).containsEntry("lagging", listOf("github"))
        @Suppress("UNCHECKED_CAST")
        val detail = (health.details["sources"] as Map<String, Map<String, Any?>>).getValue("github")
        assertThat(detail)
            .containsEntry("lastSuccessAt", "2026-09-29T06:00:00Z")
            .containsEntry("lagSeconds", 108000L)
            .containsEntry("lag", "PT30H")
            .containsEntry("windowSeconds", 86400L)
            .containsEntry("window", "PT24H")
            .containsEntry("lagging", true)
    }

    @Test
    fun `is never DOWN, and UNKNOWN when lag cannot be read`() {
        whenever(sources.lag()).thenThrow(IllegalStateException("Neo4j is unreachable"))

        assertThat(indicator.health().status).isEqualTo(Status.UNKNOWN)
    }

    @Test
    fun `is UP with nothing to report on an instance no source has synced`() {
        whenever(sources.lag()).thenReturn(emptyList())

        assertThat(indicator.health().status).isEqualTo(Status.UP)
    }
}
