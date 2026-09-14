package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Turns what GitHub says into what the ontology declares.
 *
 * This is where GitHub's vocabulary stops. Identity, provenance and validity are the graph's, so the
 * only decisions made here are which GitHub field answers which declared property, and what to do
 * about a repository GitHub has archived.
 *
 * Keys are asked of [IdentityResolver] rather than assembled here. An edge addresses its ends by key,
 * so a second key derivation would let the key a node is stored under and the key an edge points at
 * drift apart without anything failing - which is exactly what #8 was.
 */
@Component
class GitHubRepositoryMapper(
    private val identityResolver: IdentityResolver,
) {
    fun map(
        repo: GitHubRepo,
        codeowners: Codeowners,
    ): GraphDelta {
        val props = repositoryProps(repo, codeowners)
        val repositoryKey = identityResolver.keyFor(REPOSITORY, props)
        val host = hostOf(repo.htmlUrl)

        val teams =
            codeowners.teams.map { slug ->
                NodeUpsert(type = TEAM, props = mapOf("name" to "$host/$slug"), observedAt = repo.pushedAt)
            }

        return GraphDelta(
            nodes =
                listOf(
                    NodeUpsert(
                        type = REPOSITORY,
                        props = props,
                        observedAt = repo.pushedAt,
                        // GitHub's own identifier, kept so a repository renamed on GitHub can be
                        // recognised as the one already in the graph rather than appearing as new.
                        sourceId = repo.nodeId,
                    ),
                ) + teams,
            edges =
                teams.map { team ->
                    EdgeUpsert(
                        type = OWNED_BY,
                        from = repositoryKey,
                        to = identityResolver.keyFor(TEAM, team.props),
                        observedAt = repo.pushedAt,
                    )
                },
            // Archived is GitHub saying "this is over" without deleting it, which is exactly what a
            // tombstone means here: the node stays, its validity closes.
            tombstones = if (repo.archived) listOf(repositoryKey) else emptyList(),
        )
    }

    /**
     * Only the properties GitHub actually answered.
     *
     * A field GitHub left null is left out rather than written as null: the registry treats a
     * declared optional property set to null as "this was set to nothing", which is a different claim
     * from "GitHub did not say".
     */
    private fun repositoryProps(
        repo: GitHubRepo,
        codeowners: Codeowners,
    ): Map<String, Any?> =
        buildMap {
            put("url", repo.htmlUrl)
            put("defaultBranch", repo.defaultBranch ?: DEFAULT_BRANCH)
            put("topics", repo.topics)
            put("codeowners", codeowners.handles)
            repo.language?.let { put("language", it) }
            repo.description?.let { put("description", it) }
        }

    /**
     * The forge the repository lives on, so a Team name says which GitHub it came from.
     *
     * Two orgs, or a public GitHub and an Enterprise Server, can each have a "platform" team. An
     * unqualified name would quietly make them one node, and nothing downstream could tell.
     */
    private fun hostOf(htmlUrl: String): String = URI.create(htmlUrl).host ?: DEFAULT_HOST

    private companion object {
        const val REPOSITORY = "Repository"
        const val TEAM = "Team"
        const val OWNED_BY = "OWNED_BY"
        const val DEFAULT_BRANCH = "main"
        const val DEFAULT_HOST = "github.com"
    }
}
