package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.CandidateQuery
import com.repodatagraph.domain.model.CandidateStatus
import com.repodatagraph.domain.model.DeploymentEvidence
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.TouchedKeys
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.LinkQueries
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
 * The link engine's reads that are Cypher rather than single edges (#28): the review page's page of
 * candidates with its filters, a candidate by its id, the deployments that name a resource, and what
 * a sync run touched. Each test names its nodes after a run of its own, and searches by it, so what
 * other tests wrote is never counted.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jLinkQueriesIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var queries: LinkQueries

    private val tag = "links-it-" + UUID.randomUUID().toString().take(8)
    private val at = Instant.parse("2026-09-30T12:00:00Z")

    private val billing = NodeKey("Repository", "github.com/$tag/billing")
    private val payments = NodeKey("Repository", "github.com/$tag/payments")
    private val queue = NodeKey("CloudResource", "aws:arn:aws:sqs:eu-west-1:1:$tag-queue")
    private val site = NodeKey("CloudResource", "azure:/subscriptions/s/resourcegroups/rg/providers/microsoft.web/sites/$tag-site")
    private val bucket = NodeKey("CloudResource", "aws:arn:aws:s3:::$tag-bucket")

    @BeforeEach
    fun seed() {
        listOf(billing, payments).forEach { repository ->
            node(repository, mapOf("url" to "https://${repository.key}", "name" to repository.key.substringAfterLast('/')))
        }
        node(queue, mapOf("provider" to "aws", "resourceId" to queue.key.substringAfter(':'), "name" to "$tag-queue", "accountId" to "1"))
        node(site, mapOf("provider" to "azure", "resourceId" to site.key.substringAfter(':'), "name" to "$tag-site", "accountId" to "s"))
        node(
            bucket,
            mapOf("provider" to "aws", "resourceId" to bucket.key.substringAfter(':'), "name" to "$tag-bucket", "accountId" to "2"),
        )
    }

    private fun node(
        key: NodeKey,
        props: Map<String, Any?>,
    ) = graphStore.upsertNode(GraphNode(key, props, Provenance.manual(at)))

    private fun candidate(
        resource: NodeKey,
        repository: NodeKey,
        id: String,
        status: String,
        confidence: Double,
        createdAt: Instant = at,
        closed: Boolean = false,
    ) = graphStore.upsertEdge(
        GraphEdge(
            "CANDIDATE_LINK",
            resource,
            repository,
            mapOf(
                "candidateId" to "$tag-$id",
                "status" to status,
                "rule" to "naming",
                "evidence" to listOf("name=${resource.key.substringAfterLast(':')}", "normalised=billing"),
                "evidenceHash" to "0123456789abcdef",
                "createdAt" to createdAt,
            ),
            Provenance(
                sourceSystem = "link-engine",
                ingestedAt = at,
                confidence = confidence,
                inferred = true,
                validFrom = at,
                validTo = if (closed) at.plusSeconds(60) else null,
            ),
        ),
    )

    private fun seedCandidates() {
        candidate(queue, billing, "queue", "pending", 0.4)
        candidate(site, billing, "site", "conflict", 0.7)
        candidate(bucket, billing, "bucket", "rejected", 0.4)
        candidate(bucket, payments, "gone", "pending", 0.4, closed = true)
        candidate(queue, payments, "newer", "pending", 0.4, createdAt = at.plusSeconds(3600))
    }

    @Test
    fun `the open candidates are listed strongest first, then newest, without closed ones`() {
        seedCandidates()

        val page = queries.candidates(CandidateQuery(search = tag))

        assertThat(page.items.map { it.id }).containsExactly("$tag-site", "$tag-newer", "$tag-queue")
        assertThat(page.totalElements).isEqualTo(3)
        val site = page.items.first()
        assertThat(site.resource.name).isEqualTo("$tag-site")
        assertThat(site.resource.provider).isEqualTo("azure")
        assertThat(site.resource.accountId).isEqualTo("s")
        assertThat(site.repository.key).isEqualTo(billing.key)
        assertThat(site.repository.name).isEqualTo("billing")
        assertThat(site.confidence).isEqualTo(0.7)
        assertThat(site.rule).isEqualTo("naming")
        assertThat(site.evidence).containsEntry("normalised", "billing")
        assertThat(site.status).isEqualTo(CandidateStatus.CONFLICT)
        assertThat(site.createdAt).isEqualTo(at)
    }

    @Test
    fun `filters by status, provider and confidence narrow the page, and it pages`() {
        seedCandidates()

        assertThat(queries.candidates(CandidateQuery(statuses = setOf(CandidateStatus.REJECTED), search = tag)).items.map { it.id })
            .containsExactly("$tag-bucket")
        assertThat(queries.candidates(CandidateQuery(provider = "azure", search = tag)).items.map { it.id })
            .containsExactly("$tag-site")
        assertThat(queries.candidates(CandidateQuery(minConfidence = 0.5, search = tag)).items.map { it.id })
            .containsExactly("$tag-site")
        val second = queries.candidates(CandidateQuery(search = tag, page = 1, size = 1))
        assertThat(second.items.map { it.id }).containsExactly("$tag-newer")
        assertThat(second.totalElements).isEqualTo(3)
    }

    @Test
    fun `the search matches a resource's name or key, or a repository's key, whatever its case`() {
        seedCandidates()

        assertThat(queries.candidates(CandidateQuery(search = "$tag-QUEUE".uppercase())).items.map { it.id })
            .containsExactlyInAnyOrder("$tag-queue", "$tag-newer")
        assertThat(queries.candidates(CandidateQuery(search = "github.com/$tag/payments")).items.map { it.id })
            .containsExactly("$tag-newer")
    }

    @Test
    fun `a candidate is found by its id, and an unknown id finds nothing`() {
        seedCandidates()

        val found = queries.candidate("$tag-bucket")

        assertThat(found?.resource?.key).isEqualTo(bucket.key)
        assertThat(found?.repository?.key).isEqualTo(billing.key)
        assertThat(found?.status).isEqualTo(CandidateStatus.REJECTED)
        assertThat(queries.candidate("$tag-nothing")).isNull()
    }

    @Test
    fun `a deployment naming a resource is traced to the repository its artifact was built from`() {
        val artifact = NodeKey("Artifact", "ghcr.io/$tag/payments@sha256:1")
        val deployment = NodeKey("Deployment", "${artifact.key}|production|2026-09-30T12:00:00Z")
        val production = NodeKey("Environment", "production")
        node(artifact, mapOf("name" to "$tag/payments", "version" to "1", "artifactType" to "container-image"))
        node(production, mapOf("name" to "production", "type" to "production"))
        node(
            deployment,
            mapOf("deployedAt" to at, "status" to "SUCCESS", "targetResourceKeys" to listOf(queue.key)),
        )
        graphStore.upsertEdge(GraphEdge("BUILT_FROM", artifact, payments, emptyMap(), Provenance.manual(at)))
        graphStore.upsertEdge(GraphEdge("DEPLOYED_TO", artifact, deployment, emptyMap(), Provenance.manual(at)))
        graphStore.upsertEdge(GraphEdge("TO_ENVIRONMENT", deployment, production, emptyMap(), Provenance.manual(at)))

        assertThat(queries.deploymentsTargeting(queue.key))
            .containsExactly(DeploymentEvidence(deployment.key, artifact.key, payments.key, "production", at))
        assertThat(queries.deploymentsTargeting(bucket.key)).isEmpty()
    }

    @Test
    fun `what a run touched is the repositories and resources it wrote, and those its files and deployments name`() {
        val runId = "$tag-run"
        val run = NodeKey("SyncRun", runId)
        val iac = NodeKey("IacFile", "${billing.key}:infra/main.tf")
        val deployment = NodeKey("Deployment", "$tag-deployment")
        node(run, mapOf("id" to runId, "connector" to "github"))
        node(iac, mapOf("repoKey" to billing.key, "path" to "infra/main.tf", "format" to "terraform"))
        node(deployment, mapOf("deployedAt" to at, "status" to "SUCCESS", "targetResourceKeys" to listOf(site.key)))
        listOf(payments, queue, iac, deployment).forEach { written ->
            graphStore.upsertEdge(GraphEdge("PRODUCED", run, written, emptyMap(), Provenance.manual(at)))
        }

        assertThat(queries.touchedBy(runId))
            .isEqualTo(TouchedKeys(resources = setOf(queue.key, site.key), repositories = setOf(payments.key, billing.key)))
        assertThat(queries.touchedBy("$tag-no-run")).isEqualTo(TouchedKeys())
    }
}
