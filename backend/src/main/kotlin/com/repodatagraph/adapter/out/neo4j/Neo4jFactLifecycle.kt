package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.lifecycle.CurrentValidity
import com.repodatagraph.domain.lifecycle.NodeHistory
import com.repodatagraph.domain.lifecycle.NodeVersionView
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.FactLifecycle
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Retires facts by what their provenance says, or one by its key, each in one statement, and reads a
 * node's history back.
 *
 * Retiring a node closes its current relationships too (#33, FR4): an edge to something that no
 * longer exists is not a current fact either. The graph's bookkeeping - the PRODUCED edge from the run
 * that wrote the node, and any other edge to a meta type - is history about the graph itself and is
 * left as it is.
 */
@Repository
class Neo4jFactLifecycle(
    private val neo4jClient: Neo4jClient,
    private val cypher: CypherBuilder,
    private val registry: OntologyRegistry,
) : FactLifecycle {
    private val metaLabels: List<String> by lazy { registry.allNodeTypes().filter { it.meta }.map { it.name } }

    override fun closeNodesNotReasserted(
        sourceSystem: String,
        assertedBefore: Instant,
        closedAt: Instant,
        reason: RetiredReason,
    ): Int =
        neo4jClient
            .query(
                """
                MATCH (n)
                WHERE n.prov_sourceSystem = ${'$'}sourceSystem
                  AND n.prov_validTo IS NULL
                  AND n.prov_ingestedAt < ${'$'}assertedBefore
                $RETIRE
                RETURN count(n) AS closed
                """.trimIndent(),
            ).bindAll(
                mapOf(
                    "sourceSystem" to sourceSystem,
                    "assertedBefore" to ProvenanceMapper.storable(assertedBefore),
                    "closedAt" to ProvenanceMapper.storable(closedAt),
                    "reason" to reason.wireName,
                    "meta" to metaLabels,
                ),
            ).fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)
            .toInt()

    override fun retire(
        key: NodeKey,
        at: Instant,
        reason: RetiredReason,
    ): Boolean {
        val label = cypher.nodeLabel(key.type)
        return neo4jClient
            .query(
                """
                MATCH (n:$label { key: ${'$'}key })
                CALL (n) {
                  WITH n WHERE n.prov_validTo IS NULL
                  $RETIRE
                }
                RETURN count(n) AS found
                """.trimIndent(),
            ).bindAll(
                mapOf(
                    "key" to key.key,
                    "closedAt" to ProvenanceMapper.storable(at),
                    "reason" to reason.wireName,
                    "meta" to metaLabels,
                ),
            ).fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L) > 0L
    }

    override fun history(key: NodeKey): NodeHistory? {
        val label = cypher.nodeLabel(key.type)
        return neo4jClient
            .query(
                """
                MATCH (n:$label { key: ${'$'}key })
                OPTIONAL MATCH (v:${NodeVersions.LABEL} { versionOf: n.id })
                WITH n, v ORDER BY v.since DESC
                RETURN n { .* } AS n, collect(v { .* }) AS versions
                """.trimIndent(),
            ).bindAll(mapOf("key" to key.key))
            .fetch()
            .one()
            .map { row -> historyOf(key, row) }
            .orElse(null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun historyOf(
        key: NodeKey,
        row: Map<String, Any?>,
    ): NodeHistory {
        val node = GraphRowMapper.toNode(key.type, row["n"])
        val stored = row["n"] as Map<String, Any?>
        val versions = (row["versions"] as? List<Map<String, Any?>>).orEmpty()
        return NodeHistory(
            key = key,
            current =
                CurrentValidity(
                    validFrom = node.provenance.validFrom,
                    validTo = node.provenance.validTo,
                    propsFrom = ProvenanceMapper.instant(stored[PROPS_FROM]) ?: node.provenance.validFrom,
                    props = node.props,
                    retiredReason = RetiredReason.fromWire(stored[RETIRED_REASON]?.toString()),
                    resurrectedAt = ProvenanceMapper.instant(stored[RESURRECTED_AT]),
                ),
            versions = versions.map { version -> versionOf(key, version) },
        )
    }

    private fun versionOf(
        key: NodeKey,
        stored: Map<String, Any?>,
    ): NodeVersionView {
        val values = GraphRowMapper.toNode(key.type, stored + ("key" to key.key))
        return NodeVersionView(
            validFrom = values.provenance.validFrom,
            validTo = values.provenance.validTo ?: values.provenance.validFrom,
            retired = stored["retired"] == true,
            retiredReason = RetiredReason.fromWire(stored["retiredReason"]?.toString()),
            props = values.props - NodeVersions.BOOKKEEPING,
            provenance = values.provenance,
        )
    }

    private companion object {
        const val PROPS_FROM = "prov_propsFrom"
        const val RETIRED_REASON = "prov_retiredReason"
        const val RESURRECTED_AT = "prov_resurrectedAt"

        /**
         * Retires `n` at `$closedAt` for `$reason`, and its current relationships to anything but a
         * meta type. Neither is closed before it began, so a validity never ends before it starts.
         */
        val RETIRE =
            """
            SET n.prov_validTo = CASE WHEN n.prov_validFrom > ${'$'}closedAt THEN n.prov_validFrom ELSE ${'$'}closedAt END,
                n.prov_retiredReason = ${'$'}reason
            WITH n
            CALL (n) {
              MATCH (n)-[r]-(o)
              WHERE r.prov_validTo IS NULL AND NOT labels(o)[0] IN ${'$'}meta
              SET r.prov_validTo = CASE WHEN r.prov_validFrom > ${'$'}closedAt THEN r.prov_validFrom ELSE ${'$'}closedAt END
            }
            """.trimIndent()
    }
}
