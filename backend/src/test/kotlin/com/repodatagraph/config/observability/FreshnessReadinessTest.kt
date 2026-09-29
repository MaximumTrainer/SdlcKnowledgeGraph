package com.repodatagraph.config.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

/**
 * Staleness reaches the readiness probe only when an operator asks for it (#29, FR4): an instance
 * with an old graph still answers correctly about what it has, and taking it out of the load
 * balancer would turn "stale" into "down".
 */
class FreshnessReadinessTest {
    private fun readinessAfter(environment: MockEnvironment): String? {
        FreshnessReadiness().postProcessEnvironment(environment, SpringApplication())
        return environment.getProperty(INCLUDE)
    }

    @Test
    fun `leaves readiness alone by default`() {
        assertThat(readinessAfter(MockEnvironment())).isNull()
    }

    @Test
    fun `leaves readiness alone when the flag is false`() {
        assertThat(readinessAfter(MockEnvironment().withProperty(FLAG, "false"))).isNull()
    }

    @Test
    fun `adds the connectors component to the readiness group when the flag is set`() {
        val environment = MockEnvironment().withProperty(FLAG, "true")

        assertThat(readinessAfter(environment)).isEqualTo("readinessState,connectors")
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.show-components"))
            .describedAs("the public readiness probe answers a status, not which component failed")
            .isEqualTo("never")
    }

    @Test
    fun `adds to a readiness group someone already configured rather than replacing it`() {
        val environment =
            MockEnvironment()
                .withProperty(FLAG, "true")
                .withProperty(INCLUDE, "readinessState,neo4j")
                .withProperty("management.endpoint.health.group.readiness.show-components", "always")

        assertThat(readinessAfter(environment)).isEqualTo("readinessState,neo4j,connectors")
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.show-components"))
            .isEqualTo("always")
    }

    private companion object {
        const val FLAG = "observability.freshness-affects-readiness"
        const val INCLUDE = "management.endpoint.health.group.readiness.include"
    }
}
