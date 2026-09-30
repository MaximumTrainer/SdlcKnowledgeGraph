package com.repodatagraph.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * How long a source's facts stay fresh, and when a fact has gone stale (#93, FR-1).
 *
 * A window is per source system, with a default for every source configuration says nothing about.
 * A fact is stale when its source last stated it longer ago than the window, and only while it is
 * current: a closed fact is history, which does not go stale.
 */
class FreshnessPolicyTest {
    private val sources = listOf("manual", "github", "aws")
    private val policy = FreshnessPolicy(Duration.ofHours(24), mapOf("aws" to Duration.ofHours(6)), sources)
    private val now = Instant.parse("2026-09-30T12:00:00Z")

    private fun fact(
        source: String = "aws",
        ingestedAgo: Duration,
        validTo: Instant? = null,
    ) = Provenance(
        sourceSystem = source,
        ingestedAt = now.minus(ingestedAgo),
        validFrom = now.minus(Duration.ofDays(30)),
        validTo = validTo,
    )

    @Test
    fun `a source configuration names has its own window, and every other the default`() {
        assertThat(policy.windowFor("aws")).isEqualTo(Duration.ofHours(6))
        assertThat(policy.windowFor("github")).isEqualTo(Duration.ofHours(24))
    }

    @Test
    fun `every declared source is published with its window, in declaration order`() {
        assertThat(policy.windows()).containsExactly(
            org.assertj.core.api.Assertions
                .entry("manual", Duration.ofHours(24)),
            org.assertj.core.api.Assertions
                .entry("github", Duration.ofHours(24)),
            org.assertj.core.api.Assertions
                .entry("aws", Duration.ofHours(6)),
        )
        assertThat(policy.defaultWindow).isEqualTo(Duration.ofHours(24))
    }

    @Test
    fun `a fact older than its source's window is stale`() {
        assertThat(policy.isStale(fact(ingestedAgo = Duration.ofHours(9)), now)).isTrue()
    }

    @Test
    fun `a fact within its window is not, and nor is one exactly at its edge`() {
        assertThat(policy.isStale(fact(ingestedAgo = Duration.ofHours(2)), now)).isFalse()
        assertThat(policy.isStale(fact(ingestedAgo = Duration.ofHours(6)), now)).isFalse()
    }

    @Test
    fun `the window is the fact's own source's`() {
        assertThat(policy.isStale(fact(source = "github", ingestedAgo = Duration.ofHours(9)), now)).isFalse()
    }

    @Test
    fun `a closed fact is history, not stale`() {
        val closed = fact(ingestedAgo = Duration.ofDays(3), validTo = now.minus(Duration.ofDays(2)))

        assertThat(policy.isStale(closed, now)).isFalse()
    }

    @Test
    fun `a fact that closes later is still current, and can be stale`() {
        val closesLater = fact(ingestedAgo = Duration.ofDays(3), validTo = now.plus(Duration.ofDays(1)))

        assertThat(policy.isStale(closesLater, now)).isTrue()
    }

    @Test
    fun `a window for a source the registry does not declare is refused, naming it`() {
        assertThatThrownBy { FreshnessPolicy(Duration.ofHours(24), mapOf("jira" to Duration.ofHours(1)), sources) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("jira")
    }

    @Test
    fun `a window that is not positive is refused`() {
        assertThatThrownBy { FreshnessPolicy(Duration.ZERO, emptyMap(), sources) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FreshnessPolicy(Duration.ofHours(24), mapOf("aws" to Duration.ofHours(-1)), sources) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("aws")
    }
}
