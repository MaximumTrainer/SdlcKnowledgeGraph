package com.repodatagraph.domain.ontology

import com.repodatagraph.domain.identity.GitRemoteParser

/**
 * The keys of what a repository's history is made of (#85). Both name their repository, and the
 * repository in any remote form resolves to its own key, so a Change or a PullRequest lands beside
 * the Repository node it belongs to whichever notation a connector wrote.
 */
internal class ChangeIdentity(
    private val gitRemoteParser: GitRemoteParser,
) {
    fun keyFor(
        type: String,
        props: Map<String, Any?>,
    ): String =
        when (type) {
            // `<repositoryKey>@<sha>`: a sha is hexadecimal, so its case means nothing and it is lowercased.
            CHANGE -> repositoryKeyOf(props, type) + "@" + required(props, "sha", type).trim().lowercase()
            // `<repositoryKey>/pull/<number>`: a number only identifies a pull request within its repository.
            PULL_REQUEST -> {
                val number = required(props, "number", type).trim()
                repositoryKeyOf(props, type) + "/pull/" + (number.toLongOrNull()?.toString() ?: number)
            }
            else -> throw IdentityResolutionException("no change identity rule for node type '$type'")
        }

    private fun repositoryKeyOf(
        props: Map<String, Any?>,
        type: String,
    ): String = gitRemoteParser.parse(required(props, "repositoryKey", type).trim()).key

    private fun required(
        props: Map<String, Any?>,
        name: String,
        type: String,
    ): String =
        props[name]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IdentityResolutionException("$type needs '$name' to derive its identity")

    private companion object {
        const val CHANGE = "Change"
        const val PULL_REQUEST = "PullRequest"
    }
}
