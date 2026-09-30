package com.repodatagraph.ontology

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.support.EnumConformanceReport
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.data.neo4j.core.Neo4jClient
import java.util.UUID

/**
 * The enum conformance report (#81) against a real Neo4j: a value an enum does not allow, written
 * before the enum was declared, is listed with how many facts hold it, and is neither rewritten nor
 * hidden from a read. The integration tests share one database, so each value here is unique.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class EnumConformanceIT {
    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var nodes: NodeUseCase

    @Autowired
    private lateinit var registry: OntologyRegistry

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    private val run = UUID.randomUUID().toString().take(8)

    @Test
    fun `a node value outside its enum is listed with its count, and still reads`() {
        val status = "DONE-$run"
        val first = deployment("first", status)
        deployment("second", status)

        val rows = EnumConformanceReport(neo4jClient, registry).run()

        assertThat(rows.filter { it.value == status }.map { it.path to it.count }).containsExactly("Deployment.status" to 2L)
        assertThat(nodes.get("Deployment", first.key)?.props?.get("status")).isEqualTo(status)
    }

    @Test
    fun `an edge value outside its enum is listed too`() {
        val rule = "guessed-$run"
        val repository = NodeKey("Repository", "github.com/acme/conformance-$run")
        val bucket = NodeKey("CloudResource", "aws:arn:aws:s3:::conformance-$run")
        graphStore.upsertNode(GraphNode(repository, mapOf("url" to "https://${repository.key}"), Provenance.manual()))
        graphStore.upsertNode(
            GraphNode(bucket, mapOf("provider" to "aws", "resourceId" to "arn:aws:s3:::conformance-$run"), Provenance.manual()),
        )
        graphStore.upsertEdge(GraphEdge("OWNS_RESOURCE", repository, bucket, mapOf("rule" to rule), Provenance.manual()))

        val rows = EnumConformanceReport(neo4jClient, registry).run()

        assertThat(rows.filter { it.value == rule }.map { it.path to it.count }).containsExactly("OWNS_RESOURCE.rule" to 1L)
    }

    @Test
    fun `a value its enum allows is not listed`() {
        deployment("allowed", "SUCCESS")

        val rows = EnumConformanceReport(neo4jClient, registry).run()

        assertThat(rows.map { it.value }).doesNotContain("SUCCESS")
    }

    private fun deployment(
        name: String,
        status: String,
    ): NodeKey {
        val key = NodeKey("Deployment", "ghcr.io/acme/$name-$run@sha256:1#production#1759233600")
        graphStore.upsertNode(
            GraphNode(
                key,
                mapOf(
                    "artifactKey" to "ghcr.io/acme/$name-$run@sha256:1",
                    "environmentKey" to "production",
                    "deployedAt" to "2025-09-30T12:00:00Z",
                    "artifactId" to "Artifact:ghcr.io/acme/$name-$run@sha256:1",
                    "environmentId" to "Environment:production",
                    "status" to status,
                ),
                Provenance.manual(),
            ),
        )
        return key
    }
}

/**
 * `./gradlew ontologyLint --report-data`: the same report against a database that is already running,
 * named by `ontology.reportData.uri` (and `.username`, `.password`), printed and never failing. It
 * says what a migration (#33) would have to rewrite; deciding whether to is not a build's job.
 */
class EnumConformanceDataReport {
    @Test
    fun `report the stored values outside their enums`() {
        val uri = System.getProperty("ontology.reportData.uri").orEmpty()
        assumeTrue(uri.isNotBlank(), "no database named: set NEO4J_URI and run ./gradlew ontologyLint --report-data")
        val auth =
            AuthTokens.basic(
                System.getProperty("ontology.reportData.username").orEmpty().ifBlank { "neo4j" },
                System.getProperty("ontology.reportData.password").orEmpty(),
            )

        GraphDatabase.driver(uri, auth).use { driver ->
            val report = EnumConformanceReport(Neo4jClient.create(driver), YamlOntologyLoader(DefaultResourceLoader()).load())
            println(report.render(report.run()))
        }
    }
}
