package com.repodatagraph.adapter.out.github

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Where the GitHub connector looks, and with whose credentials.
 *
 * Separate from `connectors.settings.github.*`, which says whether the connector runs at all and on
 * what schedule. That split is deliberate: whether a connector is switched on is a decision about
 * this deployment, and where it points is a decision about this organisation.
 *
 * @param orgs the organisations whose repositories are read; the connector does nothing without one
 * @param baseUrl overridden for GitHub Enterprise Server, and by tests pointing at a fake
 * @param token a fine-grained token with read access to repository metadata and contents
 * @param waitForResetSeconds how long a run will wait out a rate limit before giving up on it
 */
@ConfigurationProperties("connectors.github")
data class GitHubProperties(
    val orgs: List<String> = emptyList(),
    @DefaultValue(PUBLIC_API) val baseUrl: String = PUBLIC_API,
    @DefaultValue("") val token: String = "",
    @DefaultValue("$DEFAULT_WAIT_SECONDS") val waitForResetSeconds: Long = DEFAULT_WAIT_SECONDS,
) {
    /**
     * Configured enough to be worth asking. A connector with no org has nothing to read, and one with
     * no token would be reading as an anonymous caller under a rate limit of sixty requests an hour.
     */
    fun isConfigured(): Boolean = orgs.any { it.isNotBlank() } && token.isNotBlank()

    companion object {
        const val PUBLIC_API = "https://api.github.com"

        /**
         * A minute. Long enough to ride out a burst, short enough that a run does not hold a worker
         * thread hostage: GitHub's primary limit resets on the hour, and waiting that out would block
         * a pool of four for long enough to stop every other connector.
         */
        const val DEFAULT_WAIT_SECONDS = 60L
    }
}
