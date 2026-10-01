package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkContext
import com.repodatagraph.application.links.LinkProposal
import com.repodatagraph.application.links.LinkRule
import com.repodatagraph.domain.exception.InvalidGitRemoteException
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.GraphNode

/**
 * The tag rule (#28, FR2): a cloud tag that names the repository, under one of [keys], in any form a
 * git remote is written. The strongest automatic evidence: someone put it on the resource on purpose.
 *
 * Tags are read from the resource's `tags`, `key=value` entries as the cloud connectors write them.
 * Keys are matched without regard to case, in the order configured, and each repository is proposed
 * once, citing the first tag that named it.
 */
class TagLinkRule(
    private val confidence: Double = DEFAULT_CONFIDENCE,
    private val keys: List<String> = DEFAULT_KEYS,
) : LinkRule {
    override val name = "tag"

    private val remotes = GitRemoteParser()

    override fun evaluate(
        resource: GraphNode,
        ctx: LinkContext,
    ): List<LinkProposal> {
        val tags =
            (resource.props["tags"] as? Collection<*>)
                .orEmpty()
                .map { it.toString() }
                .filter { '=' in it }
                .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val found = linkedMapOf<String, LinkProposal>()
        keys.forEach { wanted ->
            tags.filter { (key, _) -> key.equals(wanted, ignoreCase = true) }.forEach { (key, value) ->
                val repoKey = repoKeyOf(value)
                if (repoKey != null && ctx.repository(repoKey) != null && repoKey !in found) {
                    found[repoKey] = LinkProposal(repoKey, confidence, name, mapOf("tag" to key, "value" to value))
                }
            }
        }
        return found.values.toList()
    }

    private fun repoKeyOf(value: String): String? =
        try {
            remotes.parse(value).key
        } catch (_: InvalidGitRemoteException) {
            null
        }

    companion object {
        const val DEFAULT_CONFIDENCE = 0.95
        val DEFAULT_KEYS = listOf("repo", "repository", "source-repo", "git-repo")
    }
}
