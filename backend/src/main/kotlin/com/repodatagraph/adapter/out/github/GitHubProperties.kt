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
 * @param org the organisation whose repositories are read; the connector does nothing without one
 * @param baseUrl overridden for GitHub Enterprise Server, and by tests pointing at a fake
 * @param token a fine-grained token with read access to repository metadata and contents
 */
@ConfigurationProperties("connectors.github")
data class GitHubProperties(
    @DefaultValue("") val org: String = "",
    @DefaultValue(PUBLIC_API) val baseUrl: String = PUBLIC_API,
    @DefaultValue("") val token: String = "",
) {
    /**
     * Configured enough to be worth asking. A connector with no org has nothing to read, and one with
     * no token would be reading as an anonymous caller under a rate limit of sixty requests an hour.
     */
    fun isConfigured(): Boolean = org.isNotBlank() && token.isNotBlank()

    companion object {
        const val PUBLIC_API = "https://api.github.com"
    }
}
