package com.repodatagraph.config.observability

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/**
 * `observability.*` (#29).
 *
 * @param freshnessAffectsReadiness whether a stale connector also fails readiness. Bound here so it is
 *   documented in one place, but [FreshnessReadiness] reads it from the environment itself, because
 *   the health groups are built before any bean exists.
 * @param syncRunRetention how long a finished SyncRun is kept (FR6). Thirty days by default: long
 *   enough to compare this month's runs with last month's, short enough that a connector syncing every
 *   quarter of an hour leaves a few thousand runs rather than an ever-growing pile.
 * @param syncRunRetentionCron when the prune runs, in Spring's six-field cron. Nightly at half past
 *   three by default, when nothing else is likely to be syncing.
 */
@ConfigurationProperties("observability")
data class ObservabilityProperties(
    @DefaultValue("false") val freshnessAffectsReadiness: Boolean = false,
    @DefaultValue("P30D") val syncRunRetention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS),
    @DefaultValue(DEFAULT_CRON) val syncRunRetentionCron: String = DEFAULT_CRON,
) {
    init {
        // Zero or negative would prune every finished run each night, which is never what was meant.
        require(syncRunRetention > Duration.ZERO) { "observability.sync-run-retention must be positive" }
    }

    private companion object {
        const val DEFAULT_RETENTION_DAYS = 30L
        const val DEFAULT_CRON = "0 30 3 * * *"
    }
}
