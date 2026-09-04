package com.repodatagraph.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName

/**
 * Starts a throw-away Neo4j in Docker and wires it into Spring via [ServiceConnection], so no
 * `spring.neo4j.*` properties are needed in tests. Import it with `@Import(Neo4jTestcontainersConfig::class)`.
 *
 * `withReuse(true)` keeps the container alive between test runs when `testcontainers.reuse.enable=true`
 * is set in `~/.testcontainers.properties` (see docs/TESTING.md); otherwise it is simply recreated.
 */
@TestConfiguration(proxyBeanMethods = false)
class Neo4jTestcontainersConfig {
    @Bean
    @ServiceConnection
    fun neo4jContainer(): Neo4jContainer<*> =
        Neo4jContainer<Nothing>(DockerImageName.parse(NEO4J_IMAGE)).apply {
            withReuse(true)
        }

    companion object {
        const val NEO4J_IMAGE = "neo4j:5.26"
    }
}
