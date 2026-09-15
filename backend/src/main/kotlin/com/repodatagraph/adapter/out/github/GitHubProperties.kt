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
 * @param manifests what to read out of each repository's dependency manifests, if anything
 * @param iac whether to index infrastructure-as-code files
 */
@ConfigurationProperties("connectors.github")
data class GitHubProperties(
    val orgs: List<String> = emptyList(),
    @DefaultValue(PUBLIC_API) val baseUrl: String = PUBLIC_API,
    @DefaultValue("") val token: String = "",
    @DefaultValue("$DEFAULT_WAIT_SECONDS") val waitForResetSeconds: Long = DEFAULT_WAIT_SECONDS,
    @DefaultValue val manifests: ManifestSettings = ManifestSettings(),
    @DefaultValue val iac: IacSettings = IacSettings(),
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

/**
 * What to read out of a repository's dependency manifests.
 *
 * @param internalPackagePrefixes what this organisation publishes under. A dependency whose name
 *   starts with one of these is looked for among the repositories being read rather than recorded as
 *   a third-party library nobody here controls.
 * @param includeLockfiles off by default. A lockfile is the transitive closure - thousands of
 *   packages for a repository that declares twenty - and recording them all turns "who depends on
 *   this" into a question about npm's install graph rather than about this organisation's software.
 *   Worth turning on for "which repositories ship this exact vulnerable version", and only for that.
 * @param maxFileBytes files larger than this are skipped and logged. A manifest is kilobytes; a file
 *   of megabytes called `package.json` is something else, and reading it costs the run more than it
 *   is worth.
 */
data class ManifestSettings(
    @DefaultValue("true") val enabled: Boolean = true,
    val internalPackagePrefixes: List<String> = emptyList(),
    @DefaultValue("false") val includeLockfiles: Boolean = false,
    @DefaultValue("$DEFAULT_MAX_FILE_BYTES") val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
)

/** Whether infrastructure-as-code files are indexed as evidence for the link engine. */
data class IacSettings(
    @DefaultValue("true") val enabled: Boolean = true,
)

/** A megabyte. Nothing legitimate in these formats comes close. */
private const val DEFAULT_MAX_FILE_BYTES = 1_048_576L
