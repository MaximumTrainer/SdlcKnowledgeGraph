package com.repodatagraph.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Profiles

/**
 * Refuses to start a container that was not told where its database is (#6, FR4).
 *
 * The `docker` profile is how every container runs, and it takes the database from `NEO4J_URI`.
 * Without this, a missing setting starts an API that is live, fails every request, and says why only
 * in a driver error on the first one. This runs before any bean is created, so the failure is the
 * first and only thing in the log.
 *
 * Other profiles are left alone: local runs and tests have their own connection settings.
 */
class Neo4jUriGuard : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (!environment.acceptsProfiles(Profiles.of(CONTAINER_PROFILE))) return
        check(!environment.getProperty(URI_VARIABLE).isNullOrBlank()) {
            "$URI_VARIABLE must be set: the $CONTAINER_PROFILE profile takes the Neo4j connection from it " +
                "(for example bolt://neo4j:7687)"
        }
    }

    private companion object {
        const val CONTAINER_PROFILE = "docker"
        const val URI_VARIABLE = "NEO4J_URI"
    }
}
