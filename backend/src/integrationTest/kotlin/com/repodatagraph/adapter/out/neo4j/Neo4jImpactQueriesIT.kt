package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.application.impact.TraversalFilterBuilder
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactDirection
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ImpactQueryPort
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * The impact Cypher against a real Neo4j (#21): which paths the registry-built traversal finds, in
 * which direction and under which names, what it refuses to walk, and the deployment lineage the
 * why-failed analysis reads. Plain Cypher, no APOC, as the dogfood instance has none.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jImpactQueriesIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var queries: ImpactQueryPort

    @Autowired
    private lateinit var traversals: TraversalFilterBuilder

    @Autowired
    private lateinit var identityResolver: IdentityResolver

    /** Unique per test, so nodes other tests wrote to the same database are never reached. */
    private val run = "it-" + UUID.randomUUID().toString().take(8)
    private val at = Instant.parse("2026-09-01T10:00:00Z")

    private val sharedLib = NodeKey("Repository", "github.com/$run/shared-lib")
    private val payments = NodeKey("Repository", "github.com/$run/payments")
    private val checkout = NodeKey("Repository", "github.com/$run/checkout")
    private val database = NodeKey("CloudResource", "aws:arn:$run:db")
    private val platform = NodeKey("Team", "$run-platform")
    private val paymentsTeam = NodeKey("Team", "$run-payments")

    private fun provenance(
        confidence: Double = 1.0,
        inferred: Boolean = false,
        validTo: Instant? = null,
    ) = Provenance(
        sourceSystem = "manual",
        ingestedAt = at,
        validFrom = at,
        confidence = confidence,
        inferred = inferred,
        validTo = validTo,
    )

    private fun node(
        key: NodeKey,
        props: Map<String, Any?>,
    ) = graphStore.upsertNode(GraphNode(key, props, provenance()))

    private fun edge(
        type: String,
        from: NodeKey,
        to: NodeKey,
        confidence: Double = 1.0,
        inferred: Boolean = false,
        props: Map<String, Any?> = emptyMap(),
        validTo: Instant? = null,
    ) = graphStore.upsertEdge(GraphEdge(type, from, to, props, provenance(confidence, inferred, validTo)))

    @BeforeEach
    fun graph() {
        listOf(sharedLib, payments, checkout).forEach { node(it, mapOf("url" to "https://${it.key}", "defaultBranch" to "main")) }
        node(database, mapOf("provider" to "aws", "resourceId" to "arn:$run:db", "resourceType" to "rds", "name" to "db"))
        node(platform, mapOf("name" to platform.key))
        node(paymentsTeam, mapOf("name" to paymentsTeam.key))
        edge("OWNED_BY", sharedLib, platform)
        edge("OWNED_BY", payments, paymentsTeam)
        edge("DEPENDS_ON", payments, sharedLib, props = mapOf("kind" to "library"))
        edge("DEPENDS_ON", checkout, payments, confidence = 0.95, props = mapOf("kind" to "api"))
        edge("OWNS_RESOURCE", payments, database, confidence = 0.4, inferred = true)
    }

    @Test
    fun `downstream finds every node the change reaches, with each step named and scored`() {
        val search = queries.paths(sharedLib, traversals.impact(ImpactDirection.DOWNSTREAM), 3, 1000)

        assertThat(search.truncated).isFalse()
        val byTarget = search.paths.groupBy { it.target.id }
        assertThat(byTarget.keys).containsExactlyInAnyOrder(payments.id, checkout.id, database.id)

        val toDatabase = byTarget.getValue(database.id).single().steps
        assertThat(toDatabase.map { it.edge }).containsExactly("DEPENDED_ON_BY", "OWNS_RESOURCE")
        assertThat(toDatabase.map { it.from }).containsExactly(sharedLib.id, payments.id)
        assertThat(toDatabase.map { it.to }).containsExactly(payments.id, database.id)
        assertThat(toDatabase.map { it.confidence }).containsExactly(1.0, 0.4)
        assertThat(toDatabase.map { it.inferred }).containsExactly(false, true)

        val toCheckout = byTarget.getValue(checkout.id).single()
        assertThat(toCheckout.steps.map { it.confidence }).containsExactly(1.0, 0.95)
        assertThat(toCheckout.target.props["url"]).isEqualTo("https://${checkout.key}")
    }

    @Test
    fun `the depth bounds the walk`() {
        val search = queries.paths(sharedLib, traversals.impact(ImpactDirection.DOWNSTREAM), 1, 1000)

        assertThat(search.paths.map { it.target.id }).containsExactly(payments.id)
    }

    @Test
    fun `upstream walks the same edges the other way`() {
        val search = queries.paths(database, traversals.impact(ImpactDirection.UPSTREAM), 3, 1000)

        val toSharedLib = search.paths.single { it.target.id == sharedLib.id }
        assertThat(toSharedLib.steps.map { it.edge }).containsExactly("OWNED_BY_REPO", "DEPENDS_ON")
        assertThat(search.paths.map { it.target.id }).doesNotContain(checkout.id)
    }

    @Test
    fun `an edge that is not flagged as propagating is never walked`() {
        val search = queries.paths(sharedLib, traversals.impact(ImpactDirection.DOWNSTREAM), 5, 1000)

        assertThat(search.paths.map { it.target.type }).doesNotContain("Team")
    }

    @Test
    fun `a closed edge is history, not a path`() {
        val ledger = NodeKey("Repository", "github.com/$run/ledger")
        node(ledger, mapOf("url" to "https://${ledger.key}", "defaultBranch" to "main"))
        edge("DEPENDS_ON", ledger, sharedLib, props = mapOf("kind" to "library"), validTo = at.plusSeconds(60))

        val search = queries.paths(sharedLib, traversals.impact(ImpactDirection.DOWNSTREAM), 3, 1000)

        assertThat(search.paths.map { it.target.id }).doesNotContain(ledger.id)
    }

    @Test
    fun `more paths than the limit are cut short and say so`() {
        val search = queries.paths(sharedLib, traversals.impact(ImpactDirection.DOWNSTREAM), 3, 2)

        assertThat(search.paths).hasSize(2)
        assertThat(search.truncated).isTrue()
    }

    @Test
    fun `ownership is found through the repository that owns the resource`() {
        val paths = queries.ownerPaths(database, traversals.ownershipInheritance(), traversals.ownerEdges(), 3)

        val owner = paths.single()
        assertThat(owner.team.id).isEqualTo(paymentsTeam.id)
        assertThat(owner.team.props["name"]).isEqualTo(paymentsTeam.key)
        assertThat(owner.via.map { it.edge }).containsExactly("OWNED_BY_REPO", "OWNED_BY")
        assertThat(owner.via.map { it.confidence }).containsExactly(0.4, 1.0)
    }

    @Test
    fun `a node's own ownership is a path of one step`() {
        val paths = queries.ownerPaths(sharedLib, traversals.ownershipInheritance(), traversals.ownerEdges(), 3)

        assertThat(paths.map { it.team.id to it.via.map { step -> step.edge } }).containsExactly(platform.id to listOf("OWNED_BY"))
    }

    private fun deploy(
        repository: NodeKey,
        digest: String,
        commitSha: String,
        status: String,
        deployedAt: String,
    ): NodeKey {
        val artifact = NodeKey("Artifact", "ghcr.io/$run/${repository.key.substringAfterLast('/')}@sha256:$digest")
        node(artifact, mapOf("name" to artifact.key, "version" to commitSha, "artifactType" to "container-image", "commitSha" to commitSha))
        edge("BUILT_FROM", artifact, repository, props = mapOf("commitSha" to commitSha))
        val staging = NodeKey("Environment", "$run-staging")
        node(staging, mapOf("name" to staging.key, "type" to "staging"))
        val props =
            mapOf(
                "artifactKey" to artifact.key,
                "environmentKey" to staging.key,
                "deployedAt" to Instant.parse(deployedAt),
                "artifactId" to artifact.key,
                "environmentId" to staging.key,
                "status" to status,
            )
        val deployment = identityResolver.keyFor("Deployment", props)
        node(deployment, props)
        edge("DEPLOYED_TO", artifact, deployment)
        edge("TO_ENVIRONMENT", deployment, staging)
        return deployment
    }

    @Test
    fun `the facts behind a deployment are its lineage, its repository's history there and its dependencies' deployments there`() {
        val pipeline = NodeKey("Pipeline", "github-actions:${payments.key}:.github/workflows/deploy.yml")
        node(pipeline, mapOf("provider" to "github-actions", "name" to "deploy.yml", "repoId" to payments.key, "lastRunStatus" to "FAILED"))
        edge("HAS_PIPELINE", payments, pipeline)
        val good = deploy(payments, "aaa", "c1", "SUCCESS", "2026-09-01T10:00:00Z")
        val lib = deploy(sharedLib, "bbb", "s9", "SUCCESS", "2026-09-02T09:00:00Z")
        val failed = deploy(payments, "ccc", "c2", "FAILED", "2026-09-02T10:00:00Z")

        val facts = queries.deploymentFacts(failed, 2)!!

        assertThat(facts.deployment.id).isEqualTo(failed.id)
        assertThat(facts.status).isEqualTo("FAILED")
        assertThat(facts.deployedAt).isEqualTo(Instant.parse("2026-09-02T10:00:00Z"))
        assertThat(facts.commitSha).isEqualTo("c2")
        assertThat(facts.repository?.id).isEqualTo(payments.id)
        assertThat(facts.pipeline?.id).isEqualTo(pipeline.id)
        assertThat(facts.environment?.key?.key).isEqualTo("$run-staging")
        assertThat(facts.history.map { it.id to it.commitSha }).containsExactlyInAnyOrder(good.id to "c1", failed.id to "c2")
        val dependency = facts.dependencyDeployments.single()
        assertThat(dependency.id).isEqualTo(lib.id)
        assertThat(dependency.repository).isEqualTo(sharedLib)
        assertThat(dependency.commitSha).isEqualTo("s9")
        assertThat(dependency.deployedAt).isEqualTo(Instant.parse("2026-09-02T09:00:00Z"))
    }

    @Test
    fun `a deployment that is not there has no facts`() {
        assertThat(queries.deploymentFacts(NodeKey("Deployment", "$run-none"), 2)).isNull()
    }
}
