package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.port.out.FactLifecycle
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Closes facts by what their provenance says, in one statement.
 *
 * Label-less on purpose: the question is "what did this source assert", whatever type it asserted,
 * and provenance is stored the same way on every node (see [ProvenanceMapper]).
 */
@Repository
class Neo4jFactLifecycle(
    private val neo4jClient: Neo4jClient,
) : FactLifecycle {
    override fun closeNodesNotReasserted(
        sourceSystem: String,
        assertedBefore: Instant,
        closedAt: Instant,
    ): Int =
        neo4jClient
            .query(
                """
                MATCH (n)
                WHERE n.prov_sourceSystem = ${'$'}sourceSystem
                  AND n.prov_validTo IS NULL
                  AND n.prov_ingestedAt < ${'$'}assertedBefore
                SET n.prov_validTo = ${'$'}closedAt
                RETURN count(n) AS closed
                """.trimIndent(),
            ).bindAll(
                mapOf(
                    "sourceSystem" to sourceSystem,
                    "assertedBefore" to ProvenanceMapper.storable(assertedBefore),
                    "closedAt" to ProvenanceMapper.storable(closedAt),
                ),
            ).fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)
            .toInt()
}
