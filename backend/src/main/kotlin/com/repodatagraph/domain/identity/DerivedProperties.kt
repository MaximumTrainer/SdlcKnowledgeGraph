package com.repodatagraph.domain.identity

import org.springframework.stereotype.Component

/**
 * Fills in the properties a node's identity is built from, when the caller supplied the one thing
 * they all come from.
 *
 * A Repository is keyed on `host/org/name`, but nobody has those three to hand - they have a remote,
 * in whatever notation their tool wrote it. Asking a caller to split it themselves would push the
 * normalising out to every caller, which is the duplication that lets `Acme/Payments` and
 * `acme/payments` become two nodes (#8).
 *
 * So this runs before validation rather than after: the registry can then require `host`, `org` and
 * `name` honestly, because by the time it looks they are there.
 */
@Component
class DerivedProperties(
    private val gitRemoteParser: GitRemoteParser,
) {
    /**
     * @throws com.repodatagraph.domain.exception.InvalidGitRemoteException if a Repository's `url`
     *   is not a git remote. Raised here, before validation, so the caller is told the URL is wrong
     *   rather than that three properties they never sent are missing.
     */
    fun expand(
        type: String,
        props: Map<String, Any?>,
    ): Map<String, Any?> =
        when (type) {
            "Repository" -> repository(props)
            "Change" -> repositoryKeyed(props) + sha(props)
            "PullRequest" -> repositoryKeyed(props)
            else -> props
        }

    /**
     * A Change or a PullRequest names its repository by key (#85). Stored as the repository's own
     * key, whatever remote notation the caller used, so what the node says and what it is keyed by
     * agree - and so it can be matched against the Repository node it belongs to.
     */
    private fun repositoryKeyed(props: Map<String, Any?>): Map<String, Any?> {
        val repositoryKey = props[REPOSITORY_KEY]?.toString()?.trim()
        if (repositoryKey.isNullOrEmpty()) return props
        return props + (REPOSITORY_KEY to gitRemoteParser.parse(repositoryKey).key)
    }

    /** A sha is hexadecimal, so it is stored in the one case it is keyed in. */
    private fun sha(props: Map<String, Any?>): Map<String, Any?> {
        val sha = props[SHA]?.toString()?.trim()
        if (sha.isNullOrEmpty()) return emptyMap()
        return mapOf(SHA to sha.lowercase())
    }

    companion object {
        private const val REPOSITORY_KEY = "repositoryKey"
        private const val SHA = "sha"
        private const val PROVIDER = "provider"
        private const val PROVIDER_ID = "providerId"

        /** The hosts that are one provider each, whose repositories' provider ids that provider assigns. */
        private val PROVIDER_OF_HOST = mapOf("github.com" to "github", "gitlab.com" to "gitlab")

        /**
         * Properties of [type] the server derives and a caller may not send, each with what it is
         * derived from. `orgRepo` was a Repository's required identity before #8 and is `org/name`
         * now, emitted on output for compatibility and refused on input (#88).
         */
        fun derivedOnly(type: String): Map<String, String> = if (type == "Repository") mapOf("orgRepo" to "url") else emptyMap()
    }

    /**
     * Left alone when there is no `url`: a caller that supplied `host`, `org` and `name` directly is
     * already saying what the parser would have worked out, and the registry will catch the case
     * where they supplied neither.
     */
    private fun repository(props: Map<String, Any?>): Map<String, Any?> {
        val url = props["url"]?.toString()?.trim()
        val remote = if (url.isNullOrEmpty()) null else gitRemoteParser.parse(url)
        val located =
            if (remote == null) {
                props
            } else {
                props +
                    mapOf(
                        // The canonical form, not the one supplied, so the graph stores one spelling.
                        "url" to remote.canonicalUrl,
                        "host" to remote.host,
                        "org" to remote.org,
                        "name" to remote.name,
                    )
            }
        return providerAlias(located)
    }

    /**
     * The provider id as the string it is, whatever JSON type it came as, and its provider in lower
     * case (#88). A provider id on github.com or gitlab.com is that provider's unless the caller said
     * otherwise, so a GitHub App holding only the id need not also say GitHub; on any other host the
     * provider has to be named, and the validator says so.
     */
    private fun providerAlias(props: Map<String, Any?>): Map<String, Any?> {
        // A blank value is no value: stored, it would be an alias every other blank collides with.
        val providerId = props[PROVIDER_ID]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val stated =
            props[PROVIDER]
                ?.toString()
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        val provider = stated ?: PROVIDER_OF_HOST[props["host"]?.toString()]?.takeIf { providerId != null }
        return props - PROVIDER_ID - PROVIDER +
            listOfNotNull(
                providerId?.let { PROVIDER_ID to it },
                provider?.let { PROVIDER to it },
            )
    }
}
