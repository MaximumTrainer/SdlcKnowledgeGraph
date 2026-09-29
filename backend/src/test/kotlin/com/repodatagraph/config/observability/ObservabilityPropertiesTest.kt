package com.repodatagraph.config.observability

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

/**
 * `observability.*` as an operator sets it (#29, FR4 and FR6). The defaults are what an instance gets
 * when nobody set anything, so they are pinned here rather than left to whatever the class says today.
 */
class ObservabilityPropertiesTest {
    private fun bind(vararg properties: Pair<String, String>): ObservabilityProperties =
        Binder(MapConfigurationPropertySource(properties.toMap()))
            .bindOrCreate("observability", ObservabilityProperties::class.java)

    @Test
    fun `keeps thirty days of sync runs and prunes them at half past three`() {
        val properties = bind()

        assertThat(properties.syncRunRetention).isEqualTo(Duration.ofDays(30))
        assertThat(properties.syncRunRetentionCron).isEqualTo("0 30 3 * * *")
        assertThat(properties.freshnessAffectsReadiness).isFalse()
    }

    @Test
    fun `binds an ISO retention and a cron`() {
        val properties =
            bind(
                "observability.sync-run-retention" to "P7D",
                "observability.sync-run-retention-cron" to "0 0 4 * * *",
            )

        assertThat(properties.syncRunRetention).isEqualTo(Duration.ofDays(7))
        assertThat(properties.syncRunRetentionCron).isEqualTo("0 0 4 * * *")
    }

    @Test
    fun `refuses a retention that would prune every finished run`() {
        assertThatThrownBy { ObservabilityProperties(syncRunRetention = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
