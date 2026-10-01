package com.repodatagraph.observability

import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import

/**
 * The scrape is a contract with whatever runs Prometheus (#44, FR7): the alert rules in ops/alerts
 * query these names and labels, so a renamed label or a missing bucket breaks alerting without
 * breaking anything else. This pins the parts the rules depend on.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["sdlc.deployment.commit=0123abc"],
)
@Import(Neo4jTestcontainersConfig::class)
// Spring Boot turns metric export off in tests unless asked; the scrape is what is under test here.
@AutoConfigureObservability
class PrometheusScrapeIT {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun scrape(): String {
        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.headers.contentType.toString()).startsWith("text/plain")
        return response.body.orEmpty()
    }

    @Test
    fun `build info is a constant 1 labelled with the version, the ontology version and the commit`() {
        val line = scrape().lines().single { it.startsWith("sdlc_build_info{") }

        assertThat(line).contains("""commit="0123abc"""")
        assertThat(line).contains("""ontology_version="1.8.0"""")
        assertThat(line).containsPattern("""version="[^"]+"""")
        assertThat(line).containsPattern(""" 1(\.0)?$""")
    }

    @Test
    fun `request durations are a histogram with a bucket at the latency objective`() {
        restTemplate.getForEntity("/api/v1/ontology", String::class.java)

        val buckets =
            scrape().lines().filter {
                it.startsWith(
                    "http_server_requests_seconds_bucket{",
                ) &&
                    """uri="/api/v1/ontology"""" in it
            }

        assertThat(buckets).anyMatch { """le="0.5"""" in it }
        assertThat(buckets).anyMatch { """le="+Inf"""" in it }
        assertThat(buckets).allMatch { """outcome="SUCCESS"""" in it }
    }

    @Test
    fun `store errors are reported for every operation from the start, so an alert has a series to read`() {
        val errors = scrape().lines().filter { it.startsWith("sdlc_graph_store_errors_total{") }

        assertThat(errors.map { Regex("""operation="(\w+)"""").find(it)?.groupValues?.get(1) })
            .contains("upsertNode", "upsertEdge", "findNode", "findNodes", "deleteNode", "neighbourhood")
    }

    @Test
    fun `whether Neo4j is reachable is a series the Neo4jUnreachable alert can read`() {
        val line = scrape().lines().single { it.startsWith("sdlc_dependency_up{") }

        assertThat(line).contains("""dependency="neo4j"""")
        assertThat(line).containsPattern(""" 1(\.0)?$""")
    }
}
