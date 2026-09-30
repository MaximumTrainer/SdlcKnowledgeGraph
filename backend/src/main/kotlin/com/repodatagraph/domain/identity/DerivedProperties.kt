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

    private companion object {
        const val REPOSITORY_KEY = "repositoryKey"
        const val SHA = "sha"
    }

    /**
     * Left alone when there is no `url`: a caller that supplied `host`, `org` and `name` directly is
     * already saying what the parser would have worked out, and the registry will catch the case
     * where they supplied neither.
     */
    private fun repository(props: Map<String, Any?>): Map<String, Any?> {
        val url = props["url"]?.toString()?.trim()
        if (url.isNullOrEmpty()) return props

        val remote = gitRemoteParser.parse(url)
        return props +
            mapOf(
                // The canonical form, not the one supplied, so the graph stores one spelling.
                "url" to remote.canonicalUrl,
                "host" to remote.host,
                "org" to remote.org,
                "name" to remote.name,
            )
    }
}
