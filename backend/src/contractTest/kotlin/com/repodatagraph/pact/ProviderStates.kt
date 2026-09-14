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
                        "url" to "https://github.com/acme/payments",
                        "host" to "github.com",
                        "org" to "acme",
                        "name" to "payments",
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
                        "url" to "https://github.com/acme/payments",
                        "host" to "github.com",
                        "org" to "acme",
                        "name" to "payments",
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

    fun twoRepositoriesExist() {
        emptyGraph()
        repository(REPOSITORY_KEY_OWNED)
        repository(SHARED_LIB_KEY)
    }

    /** The edge as well as its ends: an interaction that lists or removes one needs it to be there. */
    fun paymentsDependsOnSharedLib() {
        twoRepositoriesExist()
        graphStore.upsertEdge(
            GraphEdge(
                type = "DEPENDS_ON",
                from = NodeKey("Repository", REPOSITORY_KEY_OWNED),
                to = NodeKey("Repository", SHARED_LIB_KEY),
                props = mapOf("kind" to "library"),
                provenance = Provenance.manual(),
            ),
        )
    }

    /** The key is `host/org/name`, so the parts a Repository is identified by come from it. */
    private fun repository(key: String) {
        val (host, org, name) = key.split("/")
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("Repository", key),
                props =
                    mapOf(
                        "url" to "https://$key",
                        "host" to host,
                        "org" to org,
                        "name" to name,
                        "defaultBranch" to "main",
                        "topics" to listOf("payments"),
                        "codeowners" to listOf("@acme/platform"),
                    ),
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
        const val TWO_REPOSITORIES_EXIST = "two repositories exist"
        const val PAYMENTS_DEPENDS_ON_SHARED_LIB = "payments DEPENDS_ON shared-lib"

        private const val REPOSITORY_KEY = "R1"
        private const val TEAM_KEY = "platform"
        private const val REPOSITORY_KEY_OWNED = "github.com/acme/payments"
        private const val SHARED_LIB_KEY = "github.com/acme/shared-lib"

        /** Every state this provider can seed. */
        val ALL =
            setOf(
                REPOSITORY_R1_EXISTS,
                NO_REPOSITORIES_EXIST,
                NO_TEAMS_EXIST,
                TEAM_PLATFORM_EXISTS,
                TWO_REPOSITORIES_EXIST,
                PAYMENTS_DEPENDS_ON_SHARED_LIB,
            )
    }
}
