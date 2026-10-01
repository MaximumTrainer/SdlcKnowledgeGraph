package com.repodatagraph.pact

import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Duration
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
    private val ontologyVersion: String = "1.4.0",
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
     * The one source with a sync run, a successful one of github thirty hours ago: past the default
     * freshness window of a day, so github is reported behind it (#93, FR-3). No connector is enabled
     * here, so no source that has never synced is listed beside it.
     */
    fun githubSyncedThirtyHoursAgo() {
        emptyGraph()
        val finishedAt = Instant.now().minus(Duration.ofHours(THIRTY_HOURS))
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("SyncRun", LAGGING_SYNC_RUN_ID),
                props =
                    mapOf(
                        "id" to LAGGING_SYNC_RUN_ID,
                        "connector" to "github",
                        "sourceSystem" to "github",
                        "mode" to "FULL",
                        "status" to "SUCCESS",
                        "startedAt" to finishedAt.minusSeconds(90),
                        "finishedAt" to finishedAt,
                    ),
                provenance = Provenance(sourceSystem = "sdlc-knowledge-graph", ingestedAt = finishedAt, validFrom = finishedAt),
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

    /**
     * The link review's background (#28): a queue named after the billing repository, which the
     * naming rule proposed as its owner below the threshold, pending under a known id.
     */
    fun pendingNamingCandidate() {
        emptyGraph()
        val billing = NodeKey("Repository", BILLING_KEY)
        graphStore.upsertNode(
            GraphNode(
                billing,
                mapOf(
                    "url" to "https://$BILLING_KEY",
                    "host" to "github.com",
                    "org" to "acme",
                    "name" to "billing",
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                ),
                Provenance.manual(),
            ),
        )
        val queue = NodeKey("CloudResource", QUEUE_KEY)
        graphStore.upsertNode(
            GraphNode(
                queue,
                mapOf(
                    "provider" to "aws",
                    "resourceId" to QUEUE_KEY.removePrefix("aws:"),
                    "resourceType" to "sqs",
                    "name" to "billing-prod",
                    "accountId" to "111111111111",
                ),
                Provenance.manual(),
            ),
        )
        val now = Instant.now()
        graphStore.upsertEdge(
            GraphEdge(
                type = "CANDIDATE_LINK",
                from = queue,
                to = billing,
                props =
                    mapOf(
                        "candidateId" to CANDIDATE_ID,
                        "status" to "pending",
                        "rule" to "naming",
                        "evidence" to listOf("matched=name", "name=billing-prod", "normalised=billing"),
                        "evidenceHash" to "0123456789abcdef",
                        "createdAt" to now,
                    ),
                provenance =
                    Provenance(sourceSystem = "link-engine", ingestedAt = now, validFrom = now, confidence = 0.4, inferred = true),
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

    /**
     * The issue's lineage (#85): a Change in payments IMPLEMENTS a Chorus task, the 1.4.0 artifact
     * CONTAINS the change and was deployed to production. Keyed as the identity rules key them: a
     * Change as `<repositoryKey>@<sha>`, a work item as its URI.
     */
    fun workItemIsLiveInProduction() {
        emptyGraph()
        repository(REPOSITORY_KEY_OWNED)
        val change = NodeKey("Change", "$REPOSITORY_KEY_OWNED@$CHANGE_SHA")
        graphStore.upsertNode(
            GraphNode(
                change,
                mapOf("repositoryKey" to REPOSITORY_KEY_OWNED, "sha" to CHANGE_SHA, "committedAt" to Instant.parse("2026-09-13T10:00:00Z")),
                Provenance.manual(),
            ),
        )
        val workItem = NodeKey("ExternalWorkItem", WORK_ITEM_URI)
        graphStore.upsertNode(GraphNode(workItem, mapOf("uri" to WORK_ITEM_URI, "system" to "chorus"), Provenance.manual()))
        graphStore.upsertEdge(GraphEdge(type = "IMPLEMENTS", from = change, to = workItem, provenance = Provenance.manual()))
        val artifact = deployed("1.4.0", "2026-09-13T11:00:00Z")
        graphStore.upsertEdge(GraphEdge(type = "CONTAINS", from = artifact, to = change, provenance = Provenance.manual()))
    }

    /** A deployment of an artifact nothing says the contents of, so its lineage is unknown (#85). */
    fun deploymentWithoutLineage() {
        emptyGraph()
        deployed("1.3.0", "2026-09-12T11:00:00Z")
    }

    fun noWorkItemsExist() {
        emptyGraph()
    }

    /** `acme/payments:<version>` deployed to production at [deployedAt], as the deployment ingest links one. */
    private fun deployed(
        version: String,
        deployedAt: String,
    ): NodeKey {
        val artifact = NodeKey("Artifact", "acme/payments:$version")
        graphStore.upsertNode(
            GraphNode(
                artifact,
                mapOf("registry" to "ghcr.io", "name" to "acme/payments", "version" to version, "artifactType" to "container-image"),
                Provenance.manual(),
            ),
        )
        val environment = NodeKey("Environment", PRODUCTION)
        graphStore.upsertNode(GraphNode(environment, mapOf("name" to PRODUCTION, "type" to PRODUCTION), Provenance.manual()))
        val at = Instant.parse(deployedAt)
        val deployment = NodeKey("Deployment", "${artifact.key}#$PRODUCTION#${at.epochSecond}")
        graphStore.upsertNode(
            GraphNode(
                deployment,
                mapOf(
                    "artifactKey" to artifact.key,
                    "environmentKey" to PRODUCTION,
                    "deployedAt" to at,
                    "artifactId" to artifact.key,
                    "environmentId" to PRODUCTION,
                    "status" to "SUCCESS",
                ),
                Provenance.manual(),
            ),
        )
        graphStore.upsertEdge(GraphEdge(type = "DEPLOYED_TO", from = artifact, to = deployment, provenance = Provenance.manual()))
        graphStore.upsertEdge(GraphEdge(type = "TO_ENVIRONMENT", from = deployment, to = environment, provenance = Provenance.manual()))
        return artifact
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

    /** acme/payments, holding the GitHub id 123456 as its provider id alias (#88). */
    fun paymentsHasGitHubId() {
        emptyGraph()
        graphStore.upsertNode(githubRepository(REPOSITORY_KEY_OWNED))
    }

    /**
     * The same repository after GitHub renamed it and moved it to another organisation (#88): one
     * node, under its new key, remembering the old one. Seeded as the rename leaves it rather than
     * by renaming, so the state does not depend on the behaviour an interaction is checking.
     */
    fun paymentsWasRenamed() {
        emptyGraph()
        graphStore.upsertNode(githubRepository(RENAMED_KEY))
        neo4jClient
            .query("MATCH (n:Repository { key: \$key }) SET n.prov_previousKeys = [\$previous]")
            .bindAll(mapOf("key" to RENAMED_KEY, "previous" to REPOSITORY_KEY_OWNED))
            .run()
    }

    private fun githubRepository(key: String): GraphNode {
        val (host, org, name) = key.split('/')
        return GraphNode(
            key = NodeKey("Repository", key),
            props =
                mapOf(
                    "url" to "https://$key",
                    "host" to host,
                    "org" to org,
                    "name" to name,
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                    "provider" to "github",
                    "providerId" to GITHUB_ID,
                ),
            provenance = Provenance.manual(),
        )
    }

    /**
     * The graph on the build's ontology, with nothing in it (#33): the lifecycle status as a fresh
     * instance reports it, the archive off and every connector on its default rules.
     */
    fun lifecycleAtItsDefaults() {
        emptyGraph()
        onOntology(ontologyVersion)
    }

    /** A graph written by the previous ontology version, so applying the migrations moves it on. */
    fun graphOnThePreviousOntologyVersion() {
        emptyGraph()
        onOntology(PREVIOUS_ONTOLOGY_VERSION)
    }

    /** One cloud resource retired two years ago, past any retention the archive might have. */
    fun factRetiredLongAgo() {
        lifecycleAtItsDefaults()
        val began = Instant.now().minus(Duration.ofDays(THREE_YEARS_DAYS))
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("CloudResource", BUCKET_KEY),
                props =
                    mapOf(
                        "provider" to "aws",
                        "resourceId" to "arn:aws:s3:::acme-logs",
                        "resourceType" to "s3-bucket",
                        "name" to "acme-logs",
                    ),
                provenance =
                    Provenance(
                        sourceSystem = "aws",
                        ingestedAt = began,
                        validFrom = began,
                        validTo = Instant.now().minus(Duration.ofDays(TWO_YEARS_DAYS)),
                    ),
            ),
        )
    }

    /** github.com/acme/payments described "v1" in January and "v2" since February. */
    fun paymentsHasOneEarlierVersion() {
        lifecycleAtItsDefaults()
        listOf("v1" to Instant.parse("2026-01-01T00:00:00Z"), "v2" to Instant.parse("2026-02-01T00:00:00Z")).forEach { (description, at) ->
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
                            "topics" to emptyList<String>(),
                            "codeowners" to emptyList<String>(),
                            "description" to description,
                        ),
                    provenance = Provenance(sourceSystem = Provenance.MANUAL, ingestedAt = at, validFrom = at),
                ),
            )
        }
    }

    private fun onOntology(version: String) {
        neo4jClient.query("CREATE (:Ontology { version: \$version })").bindAll(mapOf("version" to version)).run()
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
        const val WORK_ITEM_IS_LIVE = "work item chorus://task/01JABC is live in production"
        const val DEPLOYMENT_WITHOUT_LINEAGE = "a deployment whose artifact contains no changes"
        const val NO_WORK_ITEMS_EXIST = "no work items exist"
        const val PAYMENTS_HAS_GITHUB_ID = "repository github.com/acme/payments has GitHub id 123456"
        const val PAYMENTS_WAS_RENAMED =
            "repository with GitHub id 123456 was renamed from acme/payments to acme-platform/payments-service"
        const val GITHUB_SYNCED_THIRTY_HOURS_AGO = "the github source last synced thirty hours ago"
        const val LIFECYCLE_AT_ITS_DEFAULTS = "the lifecycle is at its defaults"
        const val GRAPH_ON_PREVIOUS_ONTOLOGY = "the graph is on the previous ontology version"
        const val FACT_RETIRED_LONG_AGO = "a fact retired long ago"
        const val PAYMENTS_HAS_ONE_EARLIER_VERSION = "the payments repository has one earlier version"
        const val PENDING_NAMING_CANDIDATE = "a pending naming candidate links the billing queue to github.com/acme/billing"

        private const val REPOSITORY_KEY = "R1"
        private const val TEAM_KEY = "platform"
        private const val REPOSITORY_KEY_OWNED = "github.com/acme/payments"
        private const val SHARED_LIB_KEY = "github.com/acme/shared-lib"
        private const val CI_KEY = "servicenow:sn.example.test:a1"
        private const val SYNC_RUN_ID = "pact-run-1"
        private const val LAGGING_SYNC_RUN_ID = "pact-run-lagging"
        private const val THIRTY_HOURS = 30L
        private const val BUCKET_KEY = "aws:arn:aws:s3:::acme-logs"
        private const val BILLING_KEY = "github.com/acme/billing"
        private const val QUEUE_KEY = "aws:arn:aws:sqs:eu-west-1:111111111111:billing-prod"
        private const val CANDIDATE_ID = "pact-candidate-1"
        private const val HUB_KEY = "github.com/acme/hub"
        private const val CHANGE_SHA = "a1b2c3"
        private const val WORK_ITEM_URI = "chorus://task/01JABC"
        private const val PRODUCTION = "production"
        private const val GITHUB_ID = "123456"
        private const val RENAMED_KEY = "github.com/acme-platform/payments-service"
        private const val PREVIOUS_ONTOLOGY_VERSION = "1.3.0"
        private const val TWO_YEARS_DAYS = 730L
        private const val THREE_YEARS_DAYS = 1095L

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
                WORK_ITEM_IS_LIVE,
                DEPLOYMENT_WITHOUT_LINEAGE,
                NO_WORK_ITEMS_EXIST,
                PAYMENTS_HAS_GITHUB_ID,
                PAYMENTS_WAS_RENAMED,
                GITHUB_SYNCED_THIRTY_HOURS_AGO,
                LIFECYCLE_AT_ITS_DEFAULTS,
                GRAPH_ON_PREVIOUS_ONTOLOGY,
                FACT_RETIRED_LONG_AGO,
                PAYMENTS_HAS_ONE_EARLIER_VERSION,
                PENDING_NAMING_CANDIDATE,
            )
    }
}
