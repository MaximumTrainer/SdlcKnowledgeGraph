package com.repodatagraph.observability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import

/**
 * An operator who sets `observability.freshness-affects-readiness` gets the `connectors` component in
 * the readiness group, and not in liveness (#29, FR4). The default, with the flag off, is proved by
 * connector-freshness.feature; that the public probe hides its components is FreshnessReadinessTest.
 *
 * The groups are asked to show their components here only so membership can be seen from outside.
 * Configuring the liveness group at all replaces Boot's default one, which includes everything, so
 * its default membership is spelt out as well.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "observability.freshness-affects-readiness=true",
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.group.readiness.show-components=always",
        "management.endpoint.health.group.liveness.include=livenessState",
        "management.endpoint.health.group.liveness.show-components=always",
    ],
)
@Import(Neo4jTestcontainersConfig::class)
class FreshnessReadinessIT {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun probe(path: String): JsonNode {
        val response = restTemplate.getForEntity(path, String::class.java)
        // No connector is enabled here, so there is nothing to be stale and both probes are UP.
        assertThat(response.statusCode.value()).describedAs(path).isEqualTo(200)
        return ObjectMapper().readTree(response.body)
    }

    @Test
    fun `the readiness probe includes the connectors component`() {
        val readiness = probe("/actuator/health/readiness")

        assertThat(
            readiness
                .path("components")
                .fieldNames()
                .asSequence()
                .toList(),
        ).containsExactlyInAnyOrder("readinessState", "connectors")
        assertThat(
            readiness
                .path("components")
                .path("connectors")
                .path("status")
                .asText(),
        ).isEqualTo("UP")
    }

    @Test
    fun `the liveness probe does not`() {
        val liveness = probe("/actuator/health/liveness")

        assertThat(liveness.path("components").has("connectors")).isFalse()
    }
}
