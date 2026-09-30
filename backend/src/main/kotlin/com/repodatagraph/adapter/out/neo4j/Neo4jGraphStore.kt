package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.lifecycle.VersioningPolicy
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.IncidentEdge
import com.repodatagraph.domain.model.NeighbourStep
import com.repodatagraph.domain.model.Neighbours
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * The one adapter that talks to Neo4j.
 *
 * Every query is built from registry-validated labels and parameterised values, so the shape of the
 * data and the safety of the query come from the same place.
 *
 * It carries more functions than detekt's threshold likes, and deliberately: it implements
 * [GraphStore], which is one port on purpose (see ADR-0003 and #19) so that identity, provenance and
 * endpoint checking are properties of the system rather than of whichever query remembered them.
 * Delegating to collaborators would not change the count, since the overrides would remain. If the
 * port keeps growing, the answer is to split the port into node and edge halves, not to hide the
 * signal here.
 */
@Repository
@Suppress("TooManyFunctions")
class Neo4jGraphStore(
    private val neo4jClient: Neo4jClient,
    private val cypher: CypherBuilder,
    private val versioning: VersioningPolicy,
) : GraphStore {
    /**
     * One statement, so a version is cut from exactly the values this write replaces (#33, FR1). A
     * version is kept when a current node's declared values change, or a retired node is stated as
     * current again; stating the same values again keeps nothing. The versions of a node are linked by
     * the id they are a version of rather than by a relationship, so no traversal of the current graph
     * ever meets one.
     */
    override fun upsertNode(node: GraphNode): GraphNode {
        val label = cypher.nodeLabel(node.type)
        val domain = storable(cypher.declaredProperties(node.type, node.props))
        val properties = domain + ProvenanceMapper.toProperties(node.provenance)

        neo4jClient
            .query(
                """
                MERGE (n:$label { key: ${'$'}key })
                WITH n, n { .* } AS before, ${ValidityCypher.began("n")}, n.prov_validTo AS endedAt,
                     coalesce(n.prov_propsFrom, n.prov_validFrom, ${NodeVersions.EPOCH}) AS propsFrom
                WITH n, before, began, wasCurrent, endedAt, propsFrom, size(keys(before)) > 1 AS existed,
                     ${'$'}props.prov_validTo IS NULL AS writesCurrent,
                     [k IN keys(${'$'}domain) WHERE (before[k] IS NULL) <> (${'$'}domain[k] IS NULL) OR before[k] <> ${'$'}domain[k]] AS changed
                WITH n, before, began, wasCurrent, endedAt, propsFrom, existed,
                     existed AND NOT wasCurrent AND writesCurrent AS resurrected,
                     CASE WHEN wasCurrent THEN ${'$'}at ELSE endedAt END AS cutAt, changed
                WITH n, before, began, wasCurrent, propsFrom, existed, resurrected, cutAt,
                     ${'$'}versioned AND existed AND ((wasCurrent AND size(changed) > 0) OR resurrected)
                       AND cutAt > propsFrom AS cut
                FOREACH (_ IN CASE WHEN cut THEN [1] ELSE [] END |
                  MERGE (v:${NodeVersions.LABEL} { key: n.id + '@' + toString(propsFrom) })
                  SET v = before
                  SET v.key = n.id + '@' + toString(propsFrom), v.id = '${NodeVersions.LABEL}:' + n.id + '@' + toString(propsFrom),
                      v.versionOf = n.id, v.since = propsFrom, v.until = cutAt,
                      v.retired = NOT wasCurrent, v.retiredReason = before.prov_retiredReason,
                      v.prov_validFrom = propsFrom, v.prov_validTo = cutAt
                )
                SET n += ${'$'}props, n.id = ${'$'}id
                ${ValidityCypher.keepBegan("n")}
                SET n.prov_propsFrom = CASE WHEN NOT existed THEN ${'$'}props.prov_validFrom
                                            WHEN cut OR resurrected THEN ${'$'}at
                                            ELSE n.prov_propsFrom END,
                    n.prov_resurrectedAt = CASE WHEN resurrected THEN ${'$'}at ELSE n.prov_resurrectedAt END,
                    n.prov_retiredReason = CASE WHEN ${'$'}props.prov_validTo IS NULL THEN null
                                                WHEN wasCurrent THEN '${RetiredReason.MANUAL.wireName}'
                                                ELSE n.prov_retiredReason END
                WITH n, cut
                CALL (n, cut) {
                  WITH n WHERE cut
                  MATCH (v:${NodeVersions.LABEL} { versionOf: n.id })
                  WITH v ORDER BY v.since DESC SKIP ${'$'}maxVersions
                  DETACH DELETE v
                }
                """.trimIndent(),
            ).bindAll(
                mapOf(
                    "key" to node.key.key,
                    "id" to node.id,
                    "props" to properties,
                    "domain" to domain,
                    "at" to ProvenanceMapper.storable(node.provenance.ingestedAt),
                    "versioned" to versioning.isVersioned(node.type),
                    "maxVersions" to versioning.maxVersions.toLong(),
                ),
            ).run()

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
                    WITH r, ${ValidityCypher.began("r")}
                    SET r += ${'$'}props
                    ${ValidityCypher.keepBegan("r")}
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

    override fun findNode(
        key: NodeKey,
        asOf: Instant,
    ): GraphNode? {
        val label = cypher.nodeLabel(key.type)
        // The values a node held at an instant are a version's when one held then (#33), and
        // otherwise its own, when it held then.
        return neo4jClient
            .query(
                """
                MATCH (n:$label { key: ${'$'}key })
                ${NodeVersions.heldAt("n", "o")}
                WHERE o IS NOT NULL
                RETURN o AS n
                """.trimIndent(),
            ).bindAll(mapOf("key" to key.key, ValidityCypher.AS_OF to ProvenanceMapper.storable(asOf)))
            .fetch()
            .one()
            .map { GraphRowMapper.toNode(key.type, it["n"]) }
            .orElse(null)
    }

    override fun findNodeByAlias(
        type: String,
        alias: Map<String, Any?>,
    ): GraphNode? {
        val declared = cypher.declaredProperties(type, alias)
        require(declared.isNotEmpty() && declared.size == alias.size) { "an alias names declared properties of $type: ${alias.keys}" }
        return findNodes(type, declared, limit = 1).firstOrNull()
    }

    override fun findNodeByPreviousKey(key: NodeKey): GraphNode? {
        val label = cypher.nodeLabel(key.type)
        return neo4jClient
            .query(
                """
                MATCH (n:$label) WHERE ${'$'}key IN n.${ProvenanceMapper.PREVIOUS_KEYS}
                RETURN n { .* } AS n ORDER BY n.key LIMIT 1
                """.trimIndent(),
            ).bindAll(mapOf("key" to key.key))
            .fetch()
            .one()
            .map { GraphRowMapper.toNode(key.type, it["n"]) }
            .orElse(null)
    }

    /**
     * One statement, so the node is never visible under neither key nor under both. The key's
     * uniqueness constraint refuses a move onto a key another node holds, whatever raced to take it.
     */
    override fun renameNode(
        from: NodeKey,
        node: GraphNode,
    ): GraphNode {
        require(from.type == node.type) { "a rename keeps the node's type: ${from.type} is not ${node.type}" }
        val label = cypher.nodeLabel(node.type)
        val properties =
            storable(cypher.declaredProperties(node.type, node.props)) +
                ProvenanceMapper.toProperties(node.provenance) +
                (ProvenanceMapper.PREVIOUS_KEYS to node.provenance.previousKeys)

        return neo4jClient
            .query(
                """
                MATCH (n:$label { key: ${'$'}from })
                WITH n, n.id AS previousId
                SET n += ${'$'}props, n.key = ${'$'}key, n.id = ${'$'}id
                WITH n, previousId
                CALL (n, previousId) {
                  MATCH (v:${NodeVersions.LABEL} { versionOf: previousId })
                  SET v.versionOf = n.id
                }
                RETURN n { .* } AS n
                """.trimIndent(),
            ).bindAll(mapOf("from" to from.key, "key" to node.key.key, "id" to node.id, "props" to properties))
            .fetch()
            .one()
            .map { GraphRowMapper.toNode(node.type, it["n"]) }
            .orElseThrow { NodeNotFoundException(listOf(from)) }
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
                    CALL (n) {
                      MATCH (v:${NodeVersions.LABEL} { versionOf: n.id })
                      DETACH DELETE v
                    }
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

    override fun findEdge(
        type: String,
        from: NodeKey,
        to: NodeKey,
    ): GraphEdge? {
        val relationship = cypher.edgeType(type)
        val fromLabel = cypher.nodeLabel(from.type)
        val toLabel = cypher.nodeLabel(to.type)

        return neo4jClient
            .query(
                """
                MATCH (a:$fromLabel { key: ${'$'}fromKey })-[r:$relationship]->(b:$toLabel { key: ${'$'}toKey })
                RETURN r { .* } AS r
                """.trimIndent(),
            ).bindAll(mapOf("fromKey" to from.key, "toKey" to to.key))
            .fetch()
            .one()
            .map { GraphRowMapper.toEdge(type, from, to, it["r"]) }
            .orElse(null)
    }

    /**
     * Run as two queries rather than one, because the direction a row was found in is the thing the
     * caller needs and recovering it from a single undirected match costs more than asking twice.
     */
    override fun findEdges(
        key: NodeKey,
        direction: Direction,
        edgeType: String?,
    ): List<IncidentEdge> = incidentEdges(key, direction, edgeType, asOf = null)

    override fun findEdges(
        key: NodeKey,
        direction: Direction,
        edgeType: String?,
        asOf: Instant,
    ): List<IncidentEdge> = incidentEdges(key, direction, edgeType, asOf)

    private fun incidentEdges(
        key: NodeKey,
        direction: Direction,
        edgeType: String?,
        asOf: Instant?,
    ): List<IncidentEdge> {
        val label = cypher.nodeLabel(key.type)
        val filter = edgeType?.let { ":" + cypher.edgeType(it) }.orEmpty()
        // Both the edge and the node at its far end, for the reason findEdges(asOf) gives; the far
        // end with the values it held then, a version's when one held (#33).
        val valid =
            if (asOf == null) {
                "WITH r, o { .* } AS other, labels(o)[0] AS otherType"
            } else {
                "WHERE ${ValidityCypher.holds("r")} WITH r, o, labels(o)[0] AS otherType\n" +
                    NodeVersions.heldAt("o", "other", carry = "r, otherType") + "\nWHERE other IS NOT NULL"
            }

        val outgoing =
            if (direction == Direction.INCOMING) {
                emptyList()
            } else {
                incident(key, "MATCH (n:$label { key: ${'$'}key })-[r$filter]->(o) $valid", Direction.OUTGOING, asOf)
            }
        val incoming =
            if (direction == Direction.OUTGOING) {
                emptyList()
            } else {
                incident(key, "MATCH (n:$label { key: ${'$'}key })<-[r$filter]-(o) $valid", Direction.INCOMING, asOf)
            }

        return outgoing + incoming
    }

    private fun incident(
        key: NodeKey,
        match: String,
        direction: Direction,
        asOf: Instant?,
    ): List<IncidentEdge> =
        neo4jClient
            .query(
                """
                $match
                RETURN type(r) AS type, r { .* } AS edge, otherType, other
                """.trimIndent(),
            ).bindAll(mapOf("key" to key.key, ValidityCypher.AS_OF to ProvenanceMapper.storable(asOf)))
            .fetch()
            .all()
            .mapNotNull { row ->
                val type = row["type"]?.toString() ?: return@mapNotNull null
                val otherType = row["otherType"]?.toString() ?: return@mapNotNull null
                val other = GraphRowMapper.toNode(otherType, row["other"])
                val (from, to) = if (direction == Direction.OUTGOING) key to other.key else other.key to key
                IncidentEdge(GraphRowMapper.toEdge(type, from, to, row["edge"]), direction, other)
            }

    /**
     * One statement for the whole frontier. A label cannot be a parameter, so the frontier's nodes
     * are found per type in a union and walked together, which is what lets one ORDER BY and one
     * LIMIT bound the step rather than one per type. One extra row says whether the limit cut it.
     */
    override fun neighbourhood(
        frontier: Collection<NodeKey>,
        step: NeighbourStep,
    ): Neighbours {
        if (frontier.isEmpty()) return Neighbours(emptyList(), truncated = false)
        val byType =
            frontier
                .distinct()
                .groupBy { it.type }
                .entries
                .toList()
        val starts =
            byType.indices.joinToString("\n  UNION ALL\n") { index ->
                "  UNWIND ${'$'}keys$index AS key MATCH (n:${cypher.nodeLabel(byType[index].key)} { key: key }) RETURN n"
            }
        val types = step.edgeTypes.joinToString("|") { cypher.edgeType(it) }.let { if (it.isEmpty()) "" else ":$it" }
        val pattern =
            when (step.direction) {
                Direction.OUTGOING -> "(n)-[r$types]->(o)"
                Direction.INCOMING -> "(n)<-[r$types]-(o)"
                Direction.BOTH -> "(n)-[r$types]-(o)"
            }
        val parameters =
            byType.mapIndexed { index, (_, keys) -> "keys$index" to keys.map { it.key } }.toMap() +
                mapOf(
                    "nodeTypes" to step.nodeTypes.map { cypher.nodeLabel(it) },
                    "restricted" to (step.within != null),
                    "within" to step.within.orEmpty().toList(),
                    "limit" to step.limit.toLong() + 1,
                )

        val rows =
            neo4jClient
                .query(
                    """
                    CALL () {
                    $starts
                    }
                    MATCH $pattern
                    WHERE r.prov_validTo IS NULL AND o.prov_validTo IS NULL
                      AND (size(${'$'}nodeTypes) = 0 OR labels(o)[0] IN ${'$'}nodeTypes)
                      AND (NOT ${'$'}restricted OR o.id IN ${'$'}within)
                    RETURN labels(n)[0] AS startType, n.key AS startKey, type(r) AS type, startNode(r) = n AS outgoing,
                           r { .* } AS edge, labels(o)[0] AS otherType, o { .* } AS other
                    ORDER BY o.id, type, n.id
                    LIMIT ${'$'}limit
                    """.trimIndent(),
                ).bindAll(parameters)
                .fetch()
                .all()
                .toList()

        val hops = rows.take(step.limit).map(::hop)
        return Neighbours(hops, truncated = rows.size > step.limit)
    }

    private fun hop(row: Map<String, Any?>): IncidentEdge {
        val start = NodeKey(row["startType"].toString(), row["startKey"].toString())
        val other = GraphRowMapper.toNode(row["otherType"].toString(), row["other"])
        val outgoing = row["outgoing"] == true
        val (from, to) = if (outgoing) start to other.key else other.key to start
        val edge = GraphRowMapper.toEdge(row["type"].toString(), from, to, row["edge"])
        return IncidentEdge(edge, if (outgoing) Direction.OUTGOING else Direction.INCOMING, other)
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

    fun toEdge(
        type: String,
        from: NodeKey,
        to: NodeKey,
        value: Any?,
    ): GraphEdge {
        val properties = propertiesOf(value)
        return GraphEdge(
            type = type,
            from = from,
            to = to,
            props = domainProperties(properties),
            provenance = ProvenanceMapper.fromProperties(properties),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun propertiesOf(value: Any?): Map<String, Any?> = (value as? Map<String, Any?>).orEmpty()

    /**
     * A stored node never holds a null, so a null here is a version's bookkeeping projected away by
     * [NodeVersions.heldAt], and skipped.
     */
    private fun domainProperties(properties: Map<String, Any?>): Map<String, Any?> =
        properties.filter { (name, value) ->
            value != null && !ProvenanceMapper.isProvenanceProperty(name) && name != "key" && name != "id"
        }
}
