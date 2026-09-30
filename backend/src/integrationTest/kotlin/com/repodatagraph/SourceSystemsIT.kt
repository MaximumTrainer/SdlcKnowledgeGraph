package com.repodatagraph

import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Every source system this application stamps on what it writes is one the registry declares
 * (#117), so the list the ontology publishes is the whole list, and a connector added without
 * declaring its source fails here rather than writing facts no scope can be granted for.
 *
 * Connector syncs run in-process and are not held to scopes; this is what keeps their sources honest.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class SourceSystemsIT {
    @Autowired
    private lateinit var registry: OntologyRegistry

    @Autowired
    private lateinit var connectors: List<SourceConnector>

    @Test
    fun `every connector the application runs names a declared source`() {
        assertTrue(connectors.isNotEmpty(), "no connectors were found")
        connectors.map { it.descriptor() }.forEach {
            assertTrue(registry.isKnownSource(it.sourceSystem), "${it.name} stamps ${it.sourceSystem}, not in ${registry.knownSources()}")
        }
    }
}
