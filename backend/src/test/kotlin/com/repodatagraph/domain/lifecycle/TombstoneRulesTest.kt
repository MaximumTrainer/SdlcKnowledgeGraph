package com.repodatagraph.domain.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * A connector's rules for what a complete full sync stops reporting (#33, FR3). Reconciliation
 * (#150) decides when a run may retire anything at all; these decide what, per connector.
 */
class TombstoneRulesTest {
    private val runStarted = Instant.parse("2026-09-30T12:00:00Z")

    @Test
    fun `by default whatever was last stated before the run began is retired, with no grace`() {
        assertThat(TombstoneRules().retireStatedBefore(runStarted)).isEqualTo(runStarted)
    }

    @Test
    fun `a grace period spares what the source stated within it`() {
        val rules = TombstoneRules(gracePeriod = Duration.ofDays(7))

        assertThat(rules.retireStatedBefore(runStarted)).isEqualTo(Instant.parse("2026-09-23T12:00:00Z"))
    }

    @Test
    fun `a connector that ignores what it stops reporting retires nothing`() {
        val rules = TombstoneRules(missingFromFullSync = MissingFromFullSync.IGNORE, gracePeriod = Duration.ofDays(7))

        assertThat(rules.retireStatedBefore(runStarted)).isNull()
    }

    @Test
    fun `only a successful run may retire anything, whatever the connector says`() {
        // #150 made this so for every connector, and nothing here can turn it off: a partial run that
        // retired what it failed to read would be the most destructive bug the system could have.
        assertThat(TombstoneRules().requireSuccessfulRun).isTrue()
        assertThat(TombstoneRules(MissingFromFullSync.IGNORE, Duration.ofDays(1)).requireSuccessfulRun).isTrue()
    }

    @Test
    fun `a negative grace period is refused`() {
        assertThrows<IllegalArgumentException> { TombstoneRules(gracePeriod = Duration.ofDays(-1)) }
    }

    @Test
    fun `reasons and policies have the names configuration and the API use`() {
        assertThat(RetiredReason.entries.map { it.wireName })
            .containsExactly("source-deleted", "source-retired", "missing-from-sync", "manual")
        assertThat(RetiredReason.fromWire("missing-from-sync")).isEqualTo(RetiredReason.MISSING_FROM_SYNC)
        assertThat(RetiredReason.fromWire("nonsense")).isNull()
        assertThat(RetiredReason.fromWire(null)).isNull()
        assertThat(MissingFromFullSync.entries.map { it.wireName }).containsExactly("tombstone", "ignore")
    }
}
