package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.lifecycle.AppliedMigration
import com.repodatagraph.domain.lifecycle.CompiledStatement
import com.repodatagraph.domain.lifecycle.MigrationFile
import com.repodatagraph.domain.port.out.OntologyStateStore
import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * The Ontology node that states which version the graph is on (#85), and an OntologyMigration node
 * per migration the graph has had (#33, FR7).
 *
 * A migration runs in an explicit transaction of the driver's own, so its statements, its record and
 * the version it moves the graph to commit together or not at all: a failure leaves the graph exactly
 * as it was, which is what lets the application stop on one without having to repair anything.
 */
@Repository
class Neo4jOntologyStateStore(
    private val neo4jClient: Neo4jClient,
    private val driver: Driver,
) : OntologyStateStore {
    override fun storedVersions(): List<String> =
        neo4jClient
            .query("MATCH (o:Ontology) RETURN o.version AS version")
            .fetchAs(String::class.java)
            .all()
            .toList()

    override fun applied(): List<AppliedMigration> =
        neo4jClient
            .query("MATCH (m:OntologyMigration) RETURN m { .* } AS m ORDER BY m.version")
            .fetch()
            .all()
            .map { row ->
                @Suppress("UNCHECKED_CAST")
                val m = row["m"] as Map<String, Any?>
                AppliedMigration(
                    version = m["version"].toString(),
                    name = m["name"]?.toString().orEmpty(),
                    checksum = m["checksum"]?.toString().orEmpty(),
                    appliedAt = ProvenanceMapper.instant(m["appliedAt"]) ?: Instant.EPOCH,
                    durationMs = (m["durationMs"] as? Number)?.toLong() ?: 0L,
                    baseline = m["baseline"] == true,
                )
            }

    override fun apply(
        migration: MigrationFile,
        statements: List<CompiledStatement>,
        appliedAt: Instant,
    ): AppliedMigration {
        val started = System.nanoTime()
        driver.session().use { session ->
            session.beginTransaction().use { tx ->
                statements.forEach { tx.run(it.cypher, Values.value(storable(it.parameters))).consume() }
                val durationMs = (System.nanoTime() - started) / NANOS_PER_MILLI
                tx.run(RECORD, Values.value(record(migration, appliedAt, durationMs, baseline = false))).consume()
                tx
                    .run(
                        VERSION,
                        Values.value(mapOf("version" to migration.version.toString(), "at" to ProvenanceMapper.storable(appliedAt))),
                    ).consume()
                tx.commit()
                return AppliedMigration(migration.version.toString(), migration.name, migration.checksum, appliedAt, durationMs, false)
            }
        }
    }

    override fun recordBaseline(
        migration: MigrationFile,
        at: Instant,
    ) {
        neo4jClient.query(RECORD).bindAll(record(migration, at, 0, baseline = true)).run()
    }

    override fun recordVersion(
        version: String,
        at: Instant,
    ) {
        // Older versions are removed so exactly one node states which ontology the graph is on.
        neo4jClient.query(VERSION).bindAll(mapOf("version" to version, "at" to ProvenanceMapper.storable(at))).run()
    }

    private fun record(
        migration: MigrationFile,
        at: Instant,
        durationMs: Long,
        baseline: Boolean,
    ): Map<String, Any?> =
        mapOf(
            "version" to migration.version.toString(),
            "name" to migration.name,
            "checksum" to migration.checksum,
            "at" to ProvenanceMapper.storable(at),
            "durationMs" to durationMs,
            "baseline" to baseline,
        )

    private fun storable(parameters: Map<String, Any?>): Map<String, Any?> =
        parameters.mapValues { (_, value) -> if (value is Instant) ProvenanceMapper.storable(value) else value }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        val RECORD =
            """
            MERGE (m:OntologyMigration { key: ${'$'}version })
            SET m.id = 'OntologyMigration:' + ${'$'}version, m.version = ${'$'}version, m.name = ${'$'}name,
                m.checksum = ${'$'}checksum, m.appliedAt = ${'$'}at, m.durationMs = ${'$'}durationMs,
                m.baseline = ${'$'}baseline,
                m.prov_sourceSystem = 'sdlc-knowledge-graph', m.prov_ingestedAt = ${'$'}at, m.prov_observedAt = ${'$'}at,
                m.prov_validFrom = ${'$'}at, m.prov_confidence = 1.0, m.prov_inferred = false
            """.trimIndent()

        val VERSION =
            """
            MERGE (o:Ontology { version: ${'$'}version })
            SET o.loadedAt = ${'$'}at
            WITH o
            OPTIONAL MATCH (older:Ontology) WHERE older.version <> ${'$'}version
            DETACH DELETE older
            """.trimIndent()
    }
}
