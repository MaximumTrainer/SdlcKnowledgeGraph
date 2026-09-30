package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.SyncRun
import com.repodatagraph.domain.model.SyncRunPage
import com.repodatagraph.domain.model.SyncRunQuery
import com.repodatagraph.domain.port.out.SyncRunStore
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * The run history in Neo4j (#29, FR5 and FR6).
 *
 * The label is a constant and every value a caller sends - connector, status, the window, the page -
 * is a parameter, so nothing a request carries is ever spliced into Cypher. An absent filter is a
 * null parameter the WHERE clause lets through, which keeps one query text rather than one per
 * combination of filters.
 */
@Repository
class Neo4jSyncRunStore(
    private val neo4jClient: Neo4jClient,
) : SyncRunStore {
    override fun find(query: SyncRunQuery): SyncRunPage {
        val parameters =
            mapOf(
                "connector" to query.connector,
                "status" to query.status,
                "from" to ProvenanceMapper.storable(query.startedFrom),
                "before" to ProvenanceMapper.storable(query.startedBefore),
            )
        val total =
            neo4jClient
                .query("MATCH (n:$LABEL) $FILTER RETURN count(n) AS total")
                .bindAll(parameters)
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        // Ties on startedAt are broken by key, so a page boundary between two runs that started in the
        // same instant is the same boundary every time it is asked for.
        val items =
            neo4jClient
                .query(
                    """
                    MATCH (n:$LABEL) $FILTER
                    RETURN n { .* } AS n
                    ORDER BY n.startedAt DESC, n.key DESC
                    SKIP ${'$'}skip LIMIT ${'$'}limit
                    """.trimIndent(),
                ).bindAll(parameters + mapOf("skip" to query.offset, "limit" to query.size.toLong()))
                .fetch()
                .all()
                .map { toRun(GraphRowMapper.toNode(LABEL, it["n"])) }
        return SyncRunPage(items, total)
    }

    override fun findById(id: String): SyncRun? =
        neo4jClient
            .query("MATCH (n:$LABEL { key: ${'$'}key }) RETURN n { .* } AS n")
            .bindAll(mapOf("key" to id))
            .fetch()
            .one()
            .map { toRun(GraphRowMapper.toNode(LABEL, it["n"])) }
            .orElse(null)

    override fun lastSuccessBySource(): Map<String, Instant> =
        neo4jClient
            .query(
                """
                MATCH (n:$LABEL)
                WHERE n.status = ${'$'}success AND n.finishedAt IS NOT NULL AND n.sourceSystem IS NOT NULL
                RETURN n.sourceSystem AS source, max(n.finishedAt) AS finishedAt
                """.trimIndent(),
            ).bindAll(mapOf("success" to SUCCESS))
            .fetch()
            .all()
            .mapNotNull { row ->
                val finished = ProvenanceMapper.instant(row["finishedAt"]) ?: return@mapNotNull null
                row["source"].toString() to finished
            }.toMap()

    /**
     * Picks the batch first, then deletes its relationships a bounded batch at a time, then the runs.
     *
     * A single DETACH DELETE of a thousand runs would also delete every PRODUCED edge they have in the
     * same transaction, and a full sync of a large estate can produce tens of thousands of nodes. That
     * transaction is what would outgrow the heap of a small instance, so no statement here touches
     * more than [batchSize] runs or [batchSize] relationships. Deleting relationships never deletes
     * the node at the other end; only SyncRun nodes are ever deleted.
     */
    override fun deleteFinishedBefore(
        cutoff: Instant,
        batchSize: Int,
    ): Int {
        val keys =
            neo4jClient
                .query(
                    """
                    MATCH (n:$LABEL)
                    WHERE n.finishedAt IS NOT NULL AND n.finishedAt < ${'$'}cutoff
                      AND coalesce(n.status, '') <> ${'$'}running
                    RETURN n.key AS key
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                ).bindAll(
                    mapOf("cutoff" to ProvenanceMapper.storable(cutoff), "running" to RUNNING, "limit" to batchSize.toLong()),
                ).fetchAs(String::class.java)
                .all()
                .toList()
        if (keys.isEmpty()) return 0

        do {
            val detached =
                neo4jClient
                    .query(
                        """
                        MATCH (n:$LABEL)-[r]-() WHERE n.key IN ${'$'}keys
                        WITH DISTINCT r LIMIT ${'$'}limit
                        DELETE r
                        RETURN count(*) AS deleted
                        """.trimIndent(),
                    ).bindAll(mapOf("keys" to keys, "limit" to batchSize.toLong()))
                    .fetchAs(Long::class.javaObjectType)
                    .one()
                    .orElse(0L)
        } while (detached >= batchSize)

        // DETACH in case a relationship arrived since the loop ended; there is nothing left to take.
        return neo4jClient
            .query(
                """
                MATCH (n:$LABEL) WHERE n.key IN ${'$'}keys
                DETACH DELETE n
                RETURN count(*) AS deleted
                """.trimIndent(),
            ).bindAll(mapOf("keys" to keys))
            .fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)
            .toInt()
    }

    private fun toRun(node: GraphNode): SyncRun {
        val props = node.props
        return SyncRun(
            id = node.key.key,
            connector = props["connector"]?.toString().orEmpty(),
            sourceSystem = props["sourceSystem"]?.toString(),
            mode = props["mode"]?.toString(),
            status = props["status"]?.toString(),
            startedAt = ProvenanceMapper.instant(props["startedAt"]),
            finishedAt = ProvenanceMapper.instant(props["finishedAt"]),
            nodesUpserted = count(props["nodesUpserted"]),
            edgesUpserted = count(props["edgesUpserted"]),
            tombstones = count(props["tombstones"]),
            watermark = ProvenanceMapper.instant(props["watermark"]),
            sourceId = props["sourceId"]?.toString(),
            error = props["error"]?.toString(),
        )
    }

    /** Neo4j hands integers back as Long; anything unreadable counts as none. */
    private fun count(value: Any?): Int = (value as? Number)?.toInt() ?: 0

    private companion object {
        const val LABEL = "SyncRun"
        const val RUNNING = "RUNNING"
        const val SUCCESS = "SUCCESS"
        const val FILTER =
            "WHERE (\$connector IS NULL OR n.connector = \$connector)" +
                " AND (\$status IS NULL OR n.status = \$status)" +
                " AND (\$from IS NULL OR n.startedAt >= \$from)" +
                " AND (\$before IS NULL OR n.startedAt < \$before)"
    }
}
