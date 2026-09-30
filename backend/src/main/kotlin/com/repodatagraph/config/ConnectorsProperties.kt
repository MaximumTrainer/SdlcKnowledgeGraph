package com.repodatagraph.config

import com.repodatagraph.domain.lifecycle.MissingFromFullSync
import com.repodatagraph.domain.lifecycle.TombstoneRules
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/**
 * Per-connector configuration, bound from `connectors.<name>.*`.
 *
 * Disabled by default, deliberately. A connector reaches a real system with real credentials, so
 * adding one to the classpath must not start it talking to anything; enabling it is a separate,
 * visible decision in configuration.
 */
@ConfigurationProperties("connectors")
data class ConnectorsProperties(
    /** Keyed by `descriptor().name`. A connector with no entry here gets [ConnectorSettings] defaults. */
    val settings: Map<String, ConnectorSettings> = emptyMap(),
) {
    fun settingsFor(name: String): ConnectorSettings = settings[name] ?: ConnectorSettings()
}

data class ConnectorSettings(
    @DefaultValue("false") val enabled: Boolean = false,
    /** Every quarter of an hour. Frequent enough to be useful, rare enough not to hammer an API. */
    @DefaultValue(DEFAULT_SCHEDULE) val schedule: String = DEFAULT_SCHEDULE,
    /**
     * The shared secret a webhook is signed with.
     *
     * Empty by default rather than absent, so a connector asked to verify a webhook without one
     * refuses instead of treating "no secret" as "any signature will do".
     */
    @DefaultValue("") val webhookSecret: String = "",
    /**
     * How long after its last successful run the connector counts as stale (#29, FR4). Unset means
     * the default for what the connector can do, which `RegisteredConnector.freshnessThreshold`
     * decides: it depends on the connector's capabilities, which configuration cannot see.
     */
    val freshnessThreshold: Duration? = null,
    /** What the connector does with what a complete full sync stops reporting (#33, FR3). */
    val lifecycle: ConnectorLifecycleSettings = ConnectorLifecycleSettings(),
) {
    private companion object {
        const val DEFAULT_SCHEDULE = "0 */15 * * * *"
    }
}

private const val DEFAULT_SCHEDULE = "0 */15 * * * *"

/**
 * `connectors.settings.<name>.lifecycle.*` (#33, FR3). By default a complete full sync retires what
 * it no longer mentions at once, as #150 did; a connector whose source is flaky can wait a grace
 * period first, and one whose full sync is not a statement about everything can ignore it.
 */
data class ConnectorLifecycleSettings(
    /** `tombstone` or `ignore`. */
    @DefaultValue("tombstone") val missingFromFullSync: String = "tombstone",
    @DefaultValue("PT0S") val gracePeriod: Duration = Duration.ZERO,
) {
    fun rules(): TombstoneRules = TombstoneRules(MissingFromFullSync.fromWire(missingFromFullSync), gracePeriod)
}
