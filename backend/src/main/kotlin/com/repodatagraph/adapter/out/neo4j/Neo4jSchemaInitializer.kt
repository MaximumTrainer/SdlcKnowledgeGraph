package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.ontology.OntologyRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Creates one uniqueness constraint per declared node type.
 *
 * Identity is only worth deriving if the database enforces it. Without the constraint, a race
 * between two connectors reporting the same repository produces two nodes and every traversal
 * afterwards is subtly wrong. The constraint turns that into a write failure instead.
 *
 * Runs before the ontology version is recorded, so the Ontology node itself is written under the
 * same guarantee.
 */
@Component
@Order(SCHEMA_INITIALIZER_ORDER)
class Neo4jSchemaInitializer(
    private val neo4jClient: Neo4jClient,
    private val registry: OntologyRegistry,
    private val cypher: CypherBuilder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun createConstraints() {
        registry.allNodeTypes().forEach { nodeType ->
            val label = cypher.nodeLabel(nodeType.name)
            val lowered = label.lowercase()

            // IF NOT EXISTS makes this safe to run on every start.
            neo4jClient
                .query("CREATE CONSTRAINT ${lowered}_key IF NOT EXISTS FOR (n:$label) REQUIRE n.key IS UNIQUE")
                .run()

            // Provenance is queried when tracing where facts came from, and when reversing a bad sync.
            neo4jClient
                .query("CREATE INDEX ${lowered}_prov_source IF NOT EXISTS FOR (n:$label) ON (n.prov_sourceSystem)")
                .run()
        }

        log.info("Ensured key constraints for {} node types", registry.allNodeTypes().size)
    }
}

const val SCHEMA_INITIALIZER_ORDER = 10
