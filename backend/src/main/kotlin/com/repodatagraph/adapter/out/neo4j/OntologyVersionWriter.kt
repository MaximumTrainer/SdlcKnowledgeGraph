package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.ontology.OntologyRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Records which ontology version built the graph.
 *
 * Data outlives the code that wrote it. Without a version stamped on the graph itself, there is no
 * way to tell whether the running application understands the data it is reading, and a renamed
 * property becomes silent data loss. Refusing to start against a graph written by a newer ontology
 * turns that into an explicit failure.
 */
@Component
@Order(SCHEMA_INITIALIZER_ORDER + 1)
class OntologyVersionWriter(
    private val neo4jClient: Neo4jClient,
    private val registry: OntologyRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun recordVersion() {
        val stored =
            neo4jClient
                .query("MATCH (o:Ontology) RETURN o.version AS version")
                .fetchAs(String::class.java)
                .all()
                .toList()

        val newer = stored.filter { isNewerThanRegistry(it) }
        check(newer.isEmpty()) {
            "the graph was written with ontology version ${newer.joinToString()} but this build only understands " +
                "${registry.version}; upgrade the application or run the migration for that version"
        }

        neo4jClient
            .query(
                """
                MERGE (o:Ontology { version: ${'$'}version })
                SET o.loadedAt = ${'$'}loadedAt
                """.trimIndent(),
            ).bindAll(mapOf("version" to registry.version, "loadedAt" to ProvenanceMapper.storable(Instant.now())))
            .run()

        // Older versions are removed so exactly one node states which ontology the graph is on.
        neo4jClient
            .query("MATCH (o:Ontology) WHERE o.version <> ${'$'}version DETACH DELETE o")
            .bindAll(mapOf("version" to registry.version))
            .run()

        log.info("Ontology version {} recorded", registry.version)
    }

    private fun isNewerThanRegistry(storedVersion: String): Boolean = compareVersions(storedVersion, registry.version) > 0

    private fun compareVersions(
        left: String,
        right: String,
    ): Int {
        val leftParts = left.split(".").map { it.toIntOrNull() ?: 0 }
        val rightParts = right.split(".").map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }
}
