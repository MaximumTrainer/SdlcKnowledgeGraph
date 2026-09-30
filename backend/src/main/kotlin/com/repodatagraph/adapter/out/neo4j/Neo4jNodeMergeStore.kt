package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.identity.EdgeMoves
import com.repodatagraph.domain.identity.MergePlan
import com.repodatagraph.domain.identity.MergeResult
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.lifecycle.VersioningPolicy
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.NodeMergeStore
import org.neo4j.driver.Driver
import org.neo4j.driver.Transaction
import org.neo4j.driver.Values
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Carries out a merge in one explicit transaction of the driver's own (#98, FR-4), as a migration
 * runs (#33): every edge moved, the source retired and the target written commit together or not
 * at all, and a dry run is the same transaction rolled back, so a preview is exactly the merge.
 *
 * The source is retired with a pointer rather than deleted. It keeps its key, so a later write
 * addressed at that key finds a node that says where it went: [Neo4jGraphStore] absorbs a node write
 * to it and lands an edge to it on the target, and a read follows the pointer. A deleted node would
 * let the next sync re-create the duplicate the merge removed.
 *
 * Relationships to the graph's own records, such as the PRODUCED edge from the sync run that wrote
 * the source, stay with the source: they are history about which node a run wrote, as retiring
 * leaves them (#33).
 */
@Repository
class Neo4jNodeMergeStore(
    private val driver: Driver,
    private val cypher: CypherBuilder,
    private val registry: OntologyRegistry,
    private val versioning: VersioningPolicy,
) : NodeMergeStore {
    private val metaLabels: List<String> by lazy { registry.allNodeTypes().filter { it.meta }.map { it.name } }

    override fun merge(
        plan: MergePlan,
        dryRun: Boolean,
    ): MergeResult {
        val type = plan.target.type
        require(plan.source.type == type) { "a merge keeps one type: ${plan.source.type} is not $type" }
        val label = cypher.nodeLabel(type)
        driver.session().use { session ->
            session.beginTransaction().use { tx ->
                val keys = mapOf("from" to plan.source.key.key, "into" to plan.target.key.key)
                val found = tx.run("MATCH (n:$label) WHERE n.key IN [\$from, \$into] RETURN collect(n.key) AS found", Values.value(keys))
                val present = found.single().get("found").asList { it.asString() }
                val missing = listOf(plan.source.key, plan.target.key).filter { it.key !in present }
                if (missing.isNotEmpty()) throw NodeNotFoundException(missing)

                val edges = moveEdges(tx, type, keys)
                val redirected =
                    tx
                        .run(
                            "MATCH (e:$label) WHERE e.$MERGED_INTO = \$from SET e.$MERGED_INTO = \$into RETURN count(e) AS c",
                            Values.value(keys),
                        ).single()
                        .get("c")
                        .asInt()
                // The source gives up its alias before the target takes it: the constraint allows one holder.
                retireSource(tx, label, plan)
                val node = writeTarget(tx, label, plan)

                if (dryRun) tx.rollback() else tx.commit()
                return MergeResult(edges, redirected, node)
            }
        }
    }

    /**
     * Every relationship of the source, type by type and in both directions: dropped when its other end
     * is the target, collapsed into the target's own when it has one of that type to that end already,
     * and otherwise re-created on the target with the same properties and provenance, then removed.
     */
    private fun moveEdges(
        tx: Transaction,
        type: String,
        keys: Map<String, Any?>,
    ): EdgeMoves {
        val label = cypher.nodeLabel(type)
        val parameters = Values.value(keys + ("meta" to metaLabels))
        var moves = EdgeMoves(0, 0, 0)
        registry.allEdgeTypes().forEach { edgeType ->
            val relationship = cypher.edgeType(edgeType.name)
            val directions =
                listOfNotNull(
                    Pair("(s)-[r:$relationship]->(o)", "(t)-[:$relationship]->(o)").takeIf { type in edgeType.from },
                    Pair("(s)<-[r:$relationship]-(o)", "(t)<-[:$relationship]-(o)").takeIf { type in edgeType.to },
                )
            directions.forEach { (pattern, onTarget) ->
                val create = onTarget.replace("[:", "[n:")
                val row =
                    tx
                        .run(
                            """
                            MATCH (s:$label { key: ${'$'}from }), (t:$label { key: ${'$'}into })
                            MATCH $pattern
                            WHERE o = t OR NOT labels(o)[0] IN ${'$'}meta
                            WITH t, r, o, o = t AS between
                            WITH t, r, o, between, NOT between AND EXISTS { MATCH $onTarget } AS duplicate
                            CALL (t, r, o, between, duplicate) {
                              WITH t, r, o WHERE NOT between AND NOT duplicate
                              CREATE $create
                              SET n = properties(r)
                            }
                            DELETE r
                            RETURN sum(CASE WHEN between THEN 1 ELSE 0 END) AS dropped,
                                   sum(CASE WHEN duplicate THEN 1 ELSE 0 END) AS collapsed,
                                   sum(CASE WHEN NOT between AND NOT duplicate THEN 1 ELSE 0 END) AS moved
                            """.trimIndent(),
                            parameters,
                        ).single()
                moves =
                    EdgeMoves(
                        moved = moves.moved + row.get("moved").asInt(),
                        collapsed = moves.collapsed + row.get("collapsed").asInt(),
                        dropped = moves.dropped + row.get("dropped").asInt(),
                    )
            }
        }
        return moves
    }

    /**
     * Retires the source as merged, at the merge unless it was retired earlier, pointing at the
     * target and recorded as whoever merged it (FR-5), without the alias properties it released.
     */
    private fun retireSource(
        tx: Transaction,
        label: String,
        plan: MergePlan,
    ) {
        val released = plan.released.joinToString("") { ", s.${cypher.propertyName(plan.source.type, it)} = null" }
        tx
            .run(
                """
                MATCH (s:$label { key: ${'$'}from })
                SET s.prov_validTo = CASE WHEN s.prov_validTo IS NOT NULL THEN s.prov_validTo
                                          WHEN s.prov_validFrom > ${'$'}at THEN s.prov_validFrom
                                          ELSE ${'$'}at END,
                    s.prov_retiredReason = '${RetiredReason.MERGED.wireName}',
                    s.$MERGED_INTO = ${'$'}into, s.$MERGED_BY = ${'$'}mergedBy,
                    s.prov_writtenBy = ${'$'}writtenBy, s.prov_principalType = ${'$'}principalType,
                    s.prov_onBehalfOfTeam = ${'$'}onBehalfOfTeam$released
                """.trimIndent(),
                Values.value(
                    mapOf(
                        "from" to plan.source.key.key,
                        "into" to plan.target.key.key,
                        "at" to ProvenanceMapper.storable(plan.by.ingestedAt),
                        "mergedBy" to (plan.by.writtenBy ?: plan.by.sourceSystem),
                        "writtenBy" to plan.by.writtenBy,
                        "principalType" to plan.by.principalType,
                        "onBehalfOfTeam" to plan.by.onBehalfOfTeam,
                    ),
                ),
            ).consume()
    }

    /**
     * Writes what the target gained and the keys it absorbed. Values it gained replace none it held,
     * but they are a change of its values all the same, so what it held until the merge is kept as a
     * version (#33), as any other write that changes them keeps one.
     */
    private fun writeTarget(
        tx: Transaction,
        label: String,
        plan: MergePlan,
    ): com.repodatagraph.domain.model.GraphNode {
        val gained = storable(cypher.declaredProperties(plan.target.type, plan.gained))
        val row =
            tx
                .run(
                    """
                    MATCH (t:$label { key: ${'$'}into })
                    WITH t, t { .* } AS before, coalesce(t.prov_propsFrom, t.prov_validFrom, ${NodeVersions.EPOCH}) AS propsFrom
                    WITH t, before, propsFrom, ${'$'}versioned AND size(keys(${'$'}gained)) > 0 AND ${'$'}at > propsFrom AS cut
                    FOREACH (_ IN CASE WHEN cut THEN [1] ELSE [] END |
                      MERGE (v:${NodeVersions.LABEL} { key: t.id + '@' + toString(propsFrom) })
                      SET v = before
                      SET v.key = t.id + '@' + toString(propsFrom), v.id = '${NodeVersions.LABEL}:' + t.id + '@' + toString(propsFrom),
                          v.versionOf = t.id, v.since = propsFrom, v.until = ${'$'}at, v.retired = false,
                          v.prov_validFrom = propsFrom, v.prov_validTo = ${'$'}at
                    )
                    SET t += ${'$'}gained, t.${ProvenanceMapper.PREVIOUS_KEYS} = ${'$'}previousKeys
                    SET t.prov_propsFrom = CASE WHEN cut THEN ${'$'}at ELSE t.prov_propsFrom END
                    WITH t, cut
                    CALL (t, cut) {
                      WITH t WHERE cut
                      MATCH (v:${NodeVersions.LABEL} { versionOf: t.id })
                      WITH v ORDER BY v.since DESC SKIP ${'$'}maxVersions
                      DETACH DELETE v
                    }
                    RETURN t { .* } AS t
                    """.trimIndent(),
                    Values.value(
                        mapOf(
                            "into" to plan.target.key.key,
                            "gained" to gained,
                            "previousKeys" to plan.previousKeys,
                            "at" to ProvenanceMapper.storable(plan.by.ingestedAt),
                            "versioned" to versioning.isVersioned(plan.target.type),
                            "maxVersions" to versioning.maxVersions.toLong(),
                        ),
                    ),
                ).single()
        return GraphRowMapper.toNode(plan.target.type, row.get("t").asMap())
    }

    private fun storable(props: Map<String, Any?>): Map<String, Any?> =
        props.mapValues { (_, value) -> if (value is Instant) ProvenanceMapper.storable(value) else value }

    companion object {
        /** The key of the node a merged node went into; hidden, as all provenance is, from its properties. */
        const val MERGED_INTO = "prov_mergedInto"

        /** Who merged it: the subject of the principal that asked, or the source whose write folded it. */
        const val MERGED_BY = "prov_mergedBy"
    }
}
