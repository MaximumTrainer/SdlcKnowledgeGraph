package com.repodatagraph.application.freshness

import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.model.Provenance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Whether a fact read back now is stale (#93, FR-1): the policy, asked at the clock's time. */
class FactFreshnessTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val freshness =
        FactFreshness(
            FreshnessPolicy(Duration.ofHours(24), mapOf("aws" to Duration.ofHours(6)), listOf("manual", "aws")),
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun ingested(
        source: String,
        hoursAgo: Long,
    ) = Provenance.stated(source, now.minus(Duration.ofHours(hoursAgo)))

    @Test
    fun `a fact is stale once its source's window has passed since it was ingested`() {
        assertThat(freshness.stale(ingested("aws", 9))).isTrue()
        assertThat(freshness.stale(ingested("aws", 2))).isFalse()
        assertThat(freshness.stale(ingested("manual", 9))).isFalse()
        assertThat(freshness.stale(ingested("manual", 25))).isTrue()
    }
}
