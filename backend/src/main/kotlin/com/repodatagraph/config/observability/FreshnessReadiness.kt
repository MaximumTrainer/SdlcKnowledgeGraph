package com.repodatagraph.config.observability

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Puts the `connectors` health component into the readiness group when, and only when,
 * `observability.freshness-affects-readiness` is true (#29, FR4). Off by default: a stale graph is
 * still a working one, and a readiness probe that failed on it would take the instance out of service
 * for something restarting cannot fix.
 *
 * An environment post-processor because group membership is configuration, read once when the health
 * endpoint is built, and a boolean flag cannot be spelt as a list in YAML. It adds to whatever the
 * readiness group already includes rather than replacing it, and hides the group's components unless
 * someone configured otherwise: the readiness probe is public (docs/DEPLOYMENT.md, D1) and should
 * answer a status, not which connector is behind.
 */
class FreshnessReadiness : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (!environment.getProperty(FLAG, Boolean::class.java, false)) return
        val include = environment.getProperty(INCLUDE)?.takeIf { it.isNotBlank() } ?: READINESS_STATE
        val overrides = mutableMapOf<String, Any>(INCLUDE to "$include,$COMPONENT")
        if (environment.getProperty(SHOW_COMPONENTS) == null) overrides[SHOW_COMPONENTS] = "never"
        environment.propertySources.addFirst(MapPropertySource(SOURCE_NAME, overrides))
    }

    private companion object {
        const val FLAG = "observability.freshness-affects-readiness"
        const val INCLUDE = "management.endpoint.health.group.readiness.include"
        const val SHOW_COMPONENTS = "management.endpoint.health.group.readiness.show-components"
        const val READINESS_STATE = "readinessState"
        const val COMPONENT = "connectors"
        const val SOURCE_NAME = "freshnessReadiness"
    }
}
