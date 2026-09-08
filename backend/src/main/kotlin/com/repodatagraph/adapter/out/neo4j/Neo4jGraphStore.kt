package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NeighbourhoodSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Subgraph
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository

/**
 * The one adapter that talks to Neo4j.
 *
 * Every query is built from registry-validated labels and parameterised values, so the shape of the
 * data and the safety of the query come from the same place.
 */
@Repository
class Neo4jGraphStore(
    private val neo4jClient: Neo4jClient,
    private val cypher: CypherBuilder,
) : GraphStore {
    override fun upsertNode(node: GraphNode): GraphNode {
        val label = cypher.nodeLabel(node.type)
        val properties =
            storable(cypher.declaredProperties(node.type, node.props)) +
                ProvenanceMapper.toProperties(node.provenance)

        neo4jClient
            .query(
                """
                MERGE (n:$label { key: ${'$'}key })
                SET n += ${'$'}props, n.id = ${'$'}id
                """.trimIndent(),
            ).bindAll(mapOf("key" to node.key.key, "id" to node.id, "props" to properties))
            .run()

        return node
    }

    override fun upsertEdge(edge: GraphEdge): GraphEdge {
        cypher.requireEdgeAllowed(edge.type, edge.from.type, edge.to.type)
        val relationship = cypher.edgeType(edge.type)
        val fromLabel = cypher.nodeLabel(edge.from.type)
        val toLabel = cypher.nodeLabel(edge.to.type)
        val properties =
            storable(cypher.declaredEdgeProperties(edge.type, edge.props)) +
                ProvenanceMapper.toProperties(edge.provenance)

        val written =
            neo4jClient
                .query(
                    """
                    MATCH (a:$fromLabel { key: ${'$'}fromKey })
                    MATCH (b:$toLabel { key: ${'$'}toKey })
                    MERGE (a)-[r:$relationship]->(b)
                    SET r += ${'$'}props
                    RETURN count(r) AS written
                    """.trimIndent(),
                ).bindAll(mapOf("fromKey" to edge.from.key, "toKey" to edge.to.key, "props" to properties))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)

        // A MATCH that finds nothing makes the whole statement a no-op. Reporting which side is
        // missing is the difference between a caught mistake and a silently absent relationship.
        if (written == 0L) throw NodeNotFoundException(missingOf(edge.from, edge.to))

        return edge
    }

    override fun findNode(key: NodeKey): GraphNode? {
        val label = cypher.nodeLabel(key.type)
        return neo4jClient
            .query("MATCH (n:$label { key: ${'$'}key }) RETURN n { .* } AS n")
            .bindAll(mapOf("key" to key.key))
            .fetch()
            .one()
            .map { GraphRowMapper.toNode(key.type, it["n"]) }
            .orElse(null)
    }

    override fun findNodes(
        type: String,
        filter: Map<String, Any?>,
        afterKey: String?,
        limit: Int?,
    ): List<GraphNode> {
        val label = cypher.nodeLabel(type)
        val declaredFilter = cypher.declaredProperties(type, filter)
        val conditions =
            declaredFilter.keys.map { "n.$it = ${'$'}filter_$it" } +
                listOfNotNull("n.key > ${'$'}afterKey".takeIf { afterKey != null })
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        // The key is the only ordering the caller can reason about, and paging by it rather than by
        // offset is what keeps a page stable while other nodes are being written.
        val paging = "ORDER BY n.key" + if (limit != null) " LIMIT ${'$'}limit" else ""
        val parameters =
            declaredFilter.mapKeys { "filter_${it.key}" } +
                listOfNotNull(
                    afterKey?.let { "afterKey" to it },
                    limit?.let { "limit" to it.toLong() },
                )

        return neo4jClient
            .query("MATCH (n:$label) $where RETURN n { .* } AS n $paging")
            .bindAll(parameters)
            .fetch()
            .all()
            .map { GraphRowMapper.toNode(type, it["n"]) }
    }

    override fun countEdges(key: NodeKey): Long {
        val label = cypher.nodeLabel(key.type)
        return neo4jClient
            .query("MATCH (n:$label { key: ${'$'}key })-[r]-() RETURN count(r) AS c")
            .bindAll(mapOf("key" to key.key))
            .fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)
    }

    override fun deleteNode(
        key: NodeKey,
        cascade: Boolean,
    ): Boolean {
        val label = cypher.nodeLabel(key.type)

        if (!cascade) {
            val attached =
                neo4jClient
                    .query("MATCH (n:$label { key: ${'$'}key })-[r]-() RETURN count(r) AS c")
                    .bindAll(mapOf("key" to key.key))
                    .fetchAs(Long::class.javaObjectType)
                    .one()
                    .orElse(0L)
            if (attached > 0L) return false
        }

        val deleted =
            neo4jClient
                .query(
                    """
                    MATCH (n:$label { key: ${'$'}key })
                    WITH n, count(n) AS found
                    DETACH DELETE n
                    RETURN found
                    """.trimIndent(),
                ).bindAll(mapOf("key" to key.key))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)

        return deleted > 0L
    }

    override fun deleteEdge(
        type: String,
        from: NodeKey,
        to: NodeKey,
    ): Boolean {
        val relationship = cypher.edgeType(type)
        val fromLabel = cypher.nodeLabel(from.type)
        val toLabel = cypher.nodeLabel(to.type)

        val deleted =
            neo4jClient
                .query(
                    """
                    MATCH (a:$fromLabel { key: ${'$'}fromKey })-[r:$relationship]->(b:$toLabel { key: ${'$'}toKey })
                    WITH r, count(r) AS found
                    DELETE r
                    RETURN found
                    """.trimIndent(),
                ).bindAll(mapOf("fromKey" to from.key, "toKey" to to.key))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)

        return deleted > 0L
    }

    override fun neighbourhood(
        key: NodeKey,
        spec: NeighbourhoodSpec,
    ): Subgraph {
        val label = cypher.nodeLabel(key.type)
        val relationshipFilter = spec.edgeTypes.joinToString("|") { cypher.edgeType(it) }
        val pattern =
            when (spec.direction) {
                Direction.OUTGOING -> "-[r:$relationshipFilter*1..${spec.effectiveDepth}]->"
                Direction.INCOMING -> "<-[r:$relationshipFilter*1..${spec.effectiveDepth}]-"
                Direction.BOTH -> "-[r:$relationshipFilter*1..${spec.effectiveDepth}]-"
            }.replace("[r:*", "[r*")

        // One extra row tells us whether the limit truncated the answer.
        val rows =
            neo4jClient
                .query(
                    """
                    MATCH path = (start:$label { key: ${'$'}key })$pattern(other)
                    WITH other, relationships(path) AS rels
                    RETURN DISTINCT other { .* } AS other, rels
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                ).bindAll(mapOf("key" to key.key, "limit" to spec.limit + 1))
                .fetch()
                .all()
                .toList()

        val truncated = rows.size > spec.limit
        val kept = rows.take(spec.limit)

        val nodes =
            kept
                .mapNotNull { row -> GraphRowMapper.toNodeOrNull(row["other"]) }
                .filter { spec.nodeTypes.isEmpty() || it.type in spec.nodeTypes }
                .distinctBy { it.id }

        return Subgraph(nodes = nodes, edges = emptyList(), truncated = truncated)
    }

    /**
     * The driver rejects a [java.time.Instant] outright, so any temporal property is converted the
     * same way provenance timestamps are. Without this, storing a Deployment fails at query time.
     */
    private fun storable(props: Map<String, Any?>): Map<String, Any?> =
        props.mapValues { (_, value) ->
            when (value) {
                is java.time.Instant -> ProvenanceMapper.storable(value)
                else -> value
            }
        }

    private fun missingOf(
        from: NodeKey,
        to: NodeKey,
    ): List<NodeKey> = listOf(from, to).filter { findNode(it) == null }
}

/**
 * Turns a returned property map into a [GraphNode]. Queries use map projections (`n { .* }`) rather
 * than returning the node itself, because a bare node comes back as a driver type rather than a map
 * and every property silently reads as absent.
 */
internal object GraphRowMapper {
    fun toNode(
        type: String,
        value: Any?,
    ): GraphNode {
        val properties = propertiesOf(value)
        return GraphNode(
            key = NodeKey(type, properties["key"]?.toString().orEmpty()),
            props = domainProperties(properties),
            provenance = ProvenanceMapper.fromProperties(properties),
        )
    }

    /** Null when the row carries no usable node, so a partial traversal result is skipped. */
    fun toNodeOrNull(value: Any?): GraphNode? {
        val properties = propertiesOf(value)
        val nodeKey = properties["id"]?.toString()?.let { id -> runCatching { NodeKey.parse(id) }.getOrNull() }
        return nodeKey?.let {
            GraphNode(
                key = it,
                props = domainProperties(properties),
                provenance = ProvenanceMapper.fromProperties(properties),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun propertiesOf(value: Any?): Map<String, Any?> = (value as? Map<String, Any?>).orEmpty()

    private fun domainProperties(properties: Map<String, Any?>): Map<String, Any?> =
        properties.filterKeys { !ProvenanceMapper.isProvenanceProperty(it) && it != "key" && it != "id" }
}
