package com.repodatagraph.observability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A source behind its freshness window must never take the instance down (#93, FR-3). fly.io checks
 * `/actuator/health/readiness` and the compose healthcheck `/actuator/health`, so a dogfood instance
 * whose seed has not run for a day would be restarted, or never start, if lag could fail either.
 *
 * So the `freshness` component answers WARN, a status Spring's aggregation does not order: the
 * overall status stays what the other components say, every endpoint answers 200, and neither probe
 * group includes the component. Configured as the docker profile is: components shown, probes on,
 * and no group configured by name, since naming one (even only to show its components) would define
 * it afresh with every component in it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.endpoint.health.show-components=always",
        "management.endpoint.health.show-details=always",
        "management.endpoint.health.probes.enabled=true",
    ],
)
@Import(Neo4jTestcontainersConfig::class)
class FreshnessHealthIT {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    private val run = "freshness-health-it-" + UUID.randomUUID()

    @BeforeEach
    fun githubLastSucceededThirtyHoursAgo() {
        val finished = Instant.now().minus(Duration.ofHours(30))
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("SyncRun", run),
                props =
                    mapOf(
                        "id" to run,
                        "connector" to "github",
                        "sourceSystem" to "github",
                        "mode" to "FULL",
                        "status" to "SUCCESS",
                        "startedAt" to finished,
                        "finishedAt" to finished,
                    ),
                provenance = Provenance.stated("sdlc-knowledge-graph"),
            ),
        )
    }

    @AfterEach
    fun removeTheRun() {
        neo4jClient.query("MATCH (n:SyncRun { key: \$key }) DETACH DELETE n").bindAll(mapOf("key" to run)).run()
    }

    private fun health(path: String): JsonNode {
        val response = restTemplate.getForEntity(path, String::class.java)
        assertThat(response.statusCode.value()).describedAs("$path answered ${response.body}").isEqualTo(200)
        return ObjectMapper().readTree(response.body)
    }

    @Test
    fun `the freshness component warns, and the whole health stays UP with 200`() {
        val health = health("/actuator/health")

        assertThat(health.path("status").asText()).isEqualTo("UP")
        val freshness = health.path("components").path("freshness")
        assertThat(freshness.path("status").asText()).isEqualTo("WARN")
        assertThat(freshness.path("details").path("lagging").map { it.asText() }).contains("github")
    }

    @Test
    fun `the component read on its own answers 200 too`() {
        assertThat(health("/actuator/health/freshness").path("status").asText()).isEqualTo("WARN")
    }

    @Test
    fun `neither probe includes it`() {
        listOf("/actuator/health/readiness", "/actuator/health/liveness").forEach { probe ->
            // The probes show no components as the docker profile configures them, so what proves the
            // component is left out is the status: UP, and 200, while the component warns.
            val group = health(probe)
            assertThat(group.path("status").asText()).describedAs(probe).isEqualTo("UP")
            assertThat(group.path("components").has("freshness")).describedAs(probe).isFalse()
        }
    }
}
