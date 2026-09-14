package com.repodatagraph.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

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
) {
    private companion object {
        const val DEFAULT_SCHEDULE = "0 */15 * * * *"
    }
}

private const val DEFAULT_SCHEDULE = "0 */15 * * * *"
