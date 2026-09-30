package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.lifecycle.ArchiveCounts
import com.repodatagraph.domain.lifecycle.ArchiveRecord
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.ArchiveSink
import com.repodatagraph.domain.port.out.ArchiveStore
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * The closed facts old enough to archive (#33, FR6): every node of a domain type whose validity
 * ended before the cutoff, with every relationship it has, and every relationship whose own validity
 * ended before it. The graph's own bookkeeping - sync runs, the ontology, versions - is never a
 * candidate: the versions of a node go with it, and the rest has retention of its own.
 */
@Repository
class Neo4jArchiveStore(
    private val neo4jClient: Neo4jClient,
    private val registry: OntologyRegistry,
) : ArchiveStore {
    private val metaLabels: List<String> by lazy { registry.allNodeTypes().filter { it.meta }.map { it.name } }

    override fun count(cutoff: Instant): ArchiveCounts {
        val row =
            neo4jClient
                .query(
                    """
                    CALL () { MATCH (n) WHERE $NODE RETURN count(n) AS nodes }
                    CALL () { MATCH (a)-[r]->(b) WHERE $EDGE RETURN count(r) AS edges }
                    RETURN nodes, edges
                    """.trimIndent(),
                ).bindAll(parameters(cutoff))
                .fetch()
                .one()
                .orElse(emptyMap())
        return ArchiveCounts((row["nodes"] as? Number)?.toLong() ?: 0L, (row["edges"] as? Number)?.toLong() ?: 0L)
    }

    override fun export(
        cutoff: Instant,
        sink: ArchiveSink,
    ): ArchiveCounts {
        var nodes = 0L
        neo4jClient
            .query(
                """
                MATCH (n) WHERE $NODE
                OPTIONAL MATCH (v:${NodeVersions.LABEL} { versionOf: n.id })
                WITH n, v ORDER BY v.since DESC
                RETURN labels(n)[0] AS type, n { .* } AS n, collect(v { .* }) AS versions
                """.trimIndent(),
            ).bindAll(parameters(cutoff))
            .fetch()
            .all()
            .forEach { row ->
                @Suppress("UNCHECKED_CAST")
                val node = row["n"] as Map<String, Any?>

                @Suppress("UNCHECKED_CAST")
                val versions = (row["versions"] as? List<Map<String, Any?>>).orEmpty()
                sink.write(
                    ArchiveRecord(
                        "node",
                        mapOf("id" to node["id"], "type" to row["type"], "key" to node["key"]) +
                            split(node) +
                            ("versions" to versions.map { split(it - NodeVersions.BOOKKEEPING) }),
                    ),
                )
                nodes++
            }

        var edges = 0L
        neo4jClient
            .query(
                """
                MATCH (a)-[r]->(b) WHERE $EDGE
                RETURN type(r) AS type, a.id AS from, b.id AS to, r { .* } AS r
                """.trimIndent(),
            ).bindAll(parameters(cutoff))
            .fetch()
            .all()
            .forEach { row ->
                @Suppress("UNCHECKED_CAST")
                val edge = row["r"] as Map<String, Any?>
                sink.write(ArchiveRecord("edge", mapOf("type" to row["type"], "from" to row["from"], "to" to row["to"]) + split(edge)))
                edges++
            }
        return ArchiveCounts(nodes, edges)
    }

    override fun purge(cutoff: Instant): ArchiveCounts {
        val edges =
            neo4jClient
                .query("MATCH (a)-[r]->(b) WHERE $EDGE DELETE r RETURN count(r) AS edges")
                .bindAll(parameters(cutoff))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        val nodes =
            neo4jClient
                .query(
                    """
                    MATCH (n) WHERE $NODE
                    CALL (n) {
                      MATCH (v:${NodeVersions.LABEL} { versionOf: n.id })
                      DETACH DELETE v
                    }
                    DETACH DELETE n
                    RETURN count(n) AS nodes
                    """.trimIndent(),
                ).bindAll(parameters(cutoff))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        return ArchiveCounts(nodes, edges)
    }

    private fun parameters(cutoff: Instant) = mapOf("cutoff" to ProvenanceMapper.storable(cutoff), "meta" to metaLabels)

    /** A stored property map as the archive writes it: the values, and the provenance without its prefix. */
    private fun split(stored: Map<String, Any?>): Map<String, Any?> =
        mapOf(
            "props" to
                stored.filterKeys { !ProvenanceMapper.isProvenanceProperty(it) && it != "key" && it != "id" }.mapValues { plain(it.value) },
            "provenance" to
                stored
                    .filterKeys { ProvenanceMapper.isProvenanceProperty(it) }
                    .mapKeys { it.key.removePrefix(PREFIX) }
                    .mapValues { plain(it.value) },
        )

    /** Temporal values as ISO-8601 instants, so the file reads the same whoever loads it. */
    private fun plain(value: Any?): Any? =
        when (value) {
            is java.time.temporal.TemporalAccessor -> ProvenanceMapper.instant(value)?.toString() ?: value.toString()
            is List<*> -> value.map(::plain)
            else -> value
        }

    private companion object {
        const val PREFIX = "prov_"

        /** A node of a domain type closed before the cutoff. */
        const val NODE = "n.prov_validTo < \$cutoff AND NOT labels(n)[0] IN \$meta"

        /** A relationship closed before the cutoff, or one with an end that is archived. */
        const val EDGE =
            "(r.prov_validTo < \$cutoff" +
                " OR (a.prov_validTo < \$cutoff AND NOT labels(a)[0] IN \$meta)" +
                " OR (b.prov_validTo < \$cutoff AND NOT labels(b)[0] IN \$meta))"
    }
}
