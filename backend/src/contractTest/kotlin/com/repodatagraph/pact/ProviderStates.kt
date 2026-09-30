package com.repodatagraph.pact

import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Instant

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

    /**
     * A repository linked to a configuration item, seeded the way the ServiceNow connector writes one.
     *
     * The CI carries more than the endpoint reports - criticality and a support group among them -
     * because that is the point of the interaction: verification has to prove the projection is
     * narrow, and a CI with only the four reported fields could not tell a projection from a
     * passthrough.
     */
    fun repositoryR1RelatesToACi() {
        repositoryR1Exists()
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("ConfigurationItem", CI_KEY),
                props =
                    mapOf(
                        "sourceSystem" to "servicenow",
                        "instance" to "sn.example.test",
                        "sysId" to "a1",
                        "ciName" to "payments-api",
                        "ciClass" to "cmdb_ci_app",
                        "serviceId" to "SVC-1",
                        "businessCriticality" to "1 - most critical",
                        "supportGroup" to "Payments On Call",
                    ),
                provenance = Provenance.manual(),
            ),
        )
        graphStore.upsertEdge(
            GraphEdge(
                type = "RELATES_TO_CI",
                from = NodeKey("Repository", REPOSITORY_KEY),
                to = NodeKey("ConfigurationItem", CI_KEY),
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

    /**
     * One run of the GitHub connector that failed, written the way `SyncRunRecorder` writes one: a
     * finished run with counts and an error, so the list has a duration and a snippet to render and
     * the run in full has something to show besides.
     */
    fun failedSyncRunExists() {
        emptyGraph()
        val startedAt = Instant.parse("2026-09-01T10:00:00Z")
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("SyncRun", SYNC_RUN_ID),
                props =
                    mapOf(
                        "id" to SYNC_RUN_ID,
                        "connector" to "github",
                        "sourceSystem" to "github",
                        "mode" to "FULL",
                        "status" to "FAILED",
                        "startedAt" to startedAt,
                        "finishedAt" to startedAt.plusSeconds(90),
                        "nodesUpserted" to 3,
                        "edgesUpserted" to 2,
                        "tombstones" to 1,
                        "error" to "page 2 failed: the source answered 502",
                    ),
                provenance = Provenance(sourceSystem = "sdlc-knowledge-graph", ingestedAt = startedAt, validFrom = startedAt),
            ),
        )
    }

    /**
     * The graph view's background (#9): payments owned by a team, depending on a library and owning
     * a bucket through an edge a link rule inferred, so the neighbourhood has an inferred edge to
     * carry its confidence and flag.
     */
    fun paymentsHasANeighbourhood() {
        paymentsDependsOnSharedLib()
        graphStore.upsertNode(GraphNode(NodeKey("Team", TEAM_KEY), mapOf("name" to TEAM_KEY), Provenance.manual()))
        graphStore.upsertEdge(
            GraphEdge(
                type = "OWNED_BY",
                from = NodeKey("Repository", REPOSITORY_KEY_OWNED),
                to = NodeKey("Team", TEAM_KEY),
                provenance = Provenance.manual(),
            ),
        )
        val bucket = NodeKey("CloudResource", BUCKET_KEY)
        graphStore.upsertNode(
            GraphNode(
                key = bucket,
                props =
                    mapOf(
                        "provider" to "aws",
                        "resourceId" to BUCKET_KEY.removePrefix("aws:"),
                        "resourceType" to "s3",
                        "name" to "acme-logs",
                    ),
                provenance = Provenance.manual(),
            ),
        )
        val now = Instant.now()
        graphStore.upsertEdge(
            GraphEdge(
                type = "OWNS_RESOURCE",
                from = NodeKey("Repository", REPOSITORY_KEY_OWNED),
                to = bucket,
                props = mapOf("rule" to "tag"),
                provenance = Provenance(sourceSystem = "aws", ingestedAt = now, validFrom = now, confidence = 0.7, inferred = true),
            ),
        )
    }

    /** A repository with more neighbours than the graph view's cap, so the answer is cut short. */
    fun hubRepositoryDependsOnMany() {
        emptyGraph()
        repository(HUB_KEY)
        repeat(HUB_DEPENDENCIES) { index ->
            val key = "github.com/acme/dependency-$index"
            repository(key)
            graphStore.upsertEdge(
                GraphEdge(
                    type = "DEPENDS_ON",
                    from = NodeKey("Repository", HUB_KEY),
                    to = NodeKey("Repository", key),
                    props = mapOf("kind" to "library"),
                    provenance = Provenance.manual(),
                ),
            )
        }
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
        const val R1_RELATES_TO_A_CI = "repository R1 is linked to a configuration item"
        const val FAILED_SYNC_RUN_EXISTS = "a FAILED sync run of github exists"
        const val PAYMENTS_HAS_A_NEIGHBOURHOOD = "payments has a neighbourhood with an inferred edge"
        const val HUB_DEPENDS_ON_MANY = "a hub repository depends on more than 500 others"

        private const val REPOSITORY_KEY = "R1"
        private const val TEAM_KEY = "platform"
        private const val REPOSITORY_KEY_OWNED = "github.com/acme/payments"
        private const val SHARED_LIB_KEY = "github.com/acme/shared-lib"
        private const val CI_KEY = "servicenow:sn.example.test:a1"
        private const val SYNC_RUN_ID = "pact-run-1"
        private const val BUCKET_KEY = "aws:arn:aws:s3:::acme-logs"
        private const val HUB_KEY = "github.com/acme/hub"

        /** One more than the graph view's cap, counting the hub itself. */
        private const val HUB_DEPENDENCIES = 500

        /** Every state this provider can seed. */
        val ALL =
            setOf(
                REPOSITORY_R1_EXISTS,
                NO_REPOSITORIES_EXIST,
                NO_TEAMS_EXIST,
                TEAM_PLATFORM_EXISTS,
                TWO_REPOSITORIES_EXIST,
                PAYMENTS_DEPENDS_ON_SHARED_LIB,
                R1_RELATES_TO_A_CI,
                FAILED_SYNC_RUN_EXISTS,
                PAYMENTS_HAS_A_NEIGHBOURHOOD,
                HUB_DEPENDS_ON_MANY,
            )
    }
}
