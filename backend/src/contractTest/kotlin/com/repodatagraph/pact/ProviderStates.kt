package com.repodatagraph.pact

import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * The graphs each pact interaction assumes, seeded before it is replayed.
 *
 * Every state starts from an empty graph, so verification cannot pass because of something an
 * earlier interaction happened to leave behind. Seeding goes through [GraphStore] rather than raw
 * Cypher so the nodes carry the same keys and provenance the application itself would write.
 *
 * [ALL] is asserted against the committed pacts by `ProviderStatesTest`.
 */
class ProviderStates(
    private val graphStore: GraphStore,
    private val neo4jClient: Neo4jClient,
) {
    /**
     * One repository addressable as `R1`. The store derives ids as `Type:key`, and the repository
     * endpoints accept a bare key as well as a full id, so `R1` is a valid path segment for a node
     * whose key is `R1` — which keeps the pact free of URL-encoded slashes.
     */
    fun repositoryR1Exists() {
        emptyGraph()
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("Repository", REPOSITORY_KEY),
                props =
                    mapOf(
                        "orgRepo" to "acme/payments",
                        "defaultBranch" to "main",
                        "topics" to listOf("payments"),
                        "codeowners" to listOf("@acme/platform"),
                    ),
                provenance = Provenance.manual(),
            ),
        )
    }

    fun noRepositoriesExist() {
        emptyGraph()
    }

    fun noTeamsExist() {
        emptyGraph()
    }

    /**
     * One Team, owned by one Repository.
     *
     * The edge is part of the state rather than an extra: the interaction that refuses a delete has
     * to have something to refuse over, and the one that cascades has to have something to cascade.
     */
    fun teamPlatformExists() {
        emptyGraph()
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("Team", TEAM_KEY),
                props = mapOf("name" to TEAM_KEY),
                provenance = Provenance.manual(),
            ),
        )
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("Repository", REPOSITORY_KEY_OWNED),
                props =
                    mapOf(
                        "orgRepo" to "acme/payments",
                        "url" to "https://github.com/acme/payments",
                        "defaultBranch" to "main",
                        "topics" to listOf("payments"),
                        "codeowners" to listOf("@acme/platform"),
                    ),
                provenance = Provenance.manual(),
            ),
        )
        graphStore.upsertEdge(
            GraphEdge(
                type = "OWNED_BY",
                from = NodeKey("Repository", REPOSITORY_KEY_OWNED),
                to = NodeKey("Team", TEAM_KEY),
                provenance = Provenance.manual(),
            ),
        )
    }

    private fun emptyGraph() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run()
    }

    companion object {
        const val REPOSITORY_R1_EXISTS = "a repository with id R1 exists"
        const val NO_REPOSITORIES_EXIST = "no repositories exist"
        const val NO_TEAMS_EXIST = "no Team nodes exist"
        const val TEAM_PLATFORM_EXISTS = "a Team named platform exists"

        private const val REPOSITORY_KEY = "R1"
        private const val TEAM_KEY = "platform"
        private const val REPOSITORY_KEY_OWNED = "github.com/acme/payments"

        /** Every state this provider can seed. */
        val ALL =
            setOf(
                REPOSITORY_R1_EXISTS,
                NO_REPOSITORIES_EXIST,
                NO_TEAMS_EXIST,
                TEAM_PLATFORM_EXISTS,
            )
    }
}
