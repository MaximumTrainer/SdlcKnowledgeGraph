package com.repodatagraph

import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * Walking-skeleton integration test: the full Spring context starts against a real Neo4j
 * (Testcontainers) and a round-trip query succeeds.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jSmokeIT {
    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    @Test
    fun `context loads and Neo4j answers RETURN 1`() {
        val value =
            neo4jClient
                .query("RETURN 1 AS value")
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElseThrow()

        assertEquals(1L, value)
    }
}
