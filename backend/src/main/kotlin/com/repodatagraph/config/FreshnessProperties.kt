package com.repodatagraph.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/**
 * `freshness.*` (#93, FR-1): how long each source system's facts stay fresh once it has stated them.
 *
 * @param defaultWindow the window of every source [windows] does not name; a day by default, the
 *   longest a nightly sync may reasonably leave a fact unconfirmed
 * @param windows a window per source system, by its name in sources.yaml
 */
@ConfigurationProperties("freshness")
data class FreshnessProperties(
    @DefaultValue("PT24H") val defaultWindow: Duration = Duration.ofHours(DEFAULT_WINDOW_HOURS),
    val windows: Map<String, Duration> = emptyMap(),
) {
    private companion object {
        const val DEFAULT_WINDOW_HOURS = 24L
    }
}
