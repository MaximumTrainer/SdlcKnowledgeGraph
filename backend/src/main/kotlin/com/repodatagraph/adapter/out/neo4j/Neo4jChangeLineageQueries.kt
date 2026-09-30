package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.DeployedChange
import com.repodatagraph.domain.model.LineageTraversal
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.WorkItemCarrier
import com.repodatagraph.domain.port.out.ChangeLineagePort
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository

/**
 * The change lineage reads in Cypher (#85), each one statement and no APOC.
 *
 * The chain is fixed - an artifact is deployed, contains changes, each of which implements work
 * items - but the edges that make it up come from the registry's [LineageTraversal], each passed
 * through [CypherBuilder] so only registry-declared names reach a query. Every edge is followed in
 * its stored direction. Closed facts (a `prov_validTo`) are history and are not followed.
 */
@Repository
class Neo4jChangeLineageQueries(
    private val neo4jClient: Neo4jClient,
    private val cypher: CypherBuilder,
) : ChangeLineagePort {
    override fun carriersOf(
        workItem: NodeKey,
        lineage: LineageTraversal,
    ): List<WorkItemCarrier> {
        if (!lineage.isComplete) return emptyList()
        return neo4jClient
            .query(
                """
                MATCH (w:${label(WORK_ITEM)} { key: ${'$'}key })<-[i:${types(lineage.implements)}]-(c:${label(CHANGE)})
                      <-[k:${types(lineage.contains)}]-(a:${label(ARTIFACT)})-[x:${types(lineage.deployedAs)}]->(d:${label(DEPLOYMENT)})
                WHERE ${open("i", "c", "k", "a", "x", "d")}
                RETURN DISTINCT d { .* } AS deployment, a { .* } AS artifact, c { .* } AS change
                """.trimIndent(),
            ).bindAll(mapOf("key" to workItem.key))
            .fetch()
            .all()
            .map { row ->
                WorkItemCarrier(
                    deployment = GraphRowMapper.toNode(DEPLOYMENT, row["deployment"]),
                    artifact = GraphRowMapper.toNode(ARTIFACT, row["artifact"]),
                    change = GraphRowMapper.toNode(CHANGE, row["change"]),
                )
            }
    }

    override fun contentsOf(
        deployment: NodeKey,
        lineage: LineageTraversal,
    ): List<DeployedChange> {
        if (lineage.deployedAs.isEmpty() || lineage.contains.isEmpty()) return emptyList()
        val implemented =
            if (lineage.implements.isEmpty()) {
                "[] AS workItems"
            } else {
                """
                [(c)-[i:${types(lineage.implements)}]->(w:${label(WORK_ITEM)})
                  WHERE ${open("i", "w")} | w { .* }] AS workItems
                """.trimIndent()
            }
        return neo4jClient
            .query(
                """
                MATCH (d:${label(DEPLOYMENT)} { key: ${'$'}key })<-[x:${types(lineage.deployedAs)}]-(a:${label(ARTIFACT)})
                      -[k:${types(lineage.contains)}]->(c:${label(CHANGE)})
                WHERE ${open("x", "a", "k", "c")}
                WITH DISTINCT a, c
                RETURN a { .* } AS artifact, c { .* } AS change, $implemented
                """.trimIndent(),
            ).bindAll(mapOf("key" to deployment.key))
            .fetch()
            .all()
            .map { row ->
                DeployedChange(
                    artifact = GraphRowMapper.toNode(ARTIFACT, row["artifact"]),
                    change = GraphRowMapper.toNode(CHANGE, row["change"]),
                    workItems =
                        (row["workItems"] as? List<*>)
                            .orEmpty()
                            .filterNotNull()
                            .map { GraphRowMapper.toNode(WORK_ITEM, it) }
                            .distinctBy { it.id },
                )
            }
    }

    /**
     * A sha the caller gave may abbreviate a stored one, or a stored one may abbreviate it; a stored
     * sha shorter than four characters abbreviates too much to name anything. A Change keeps the
     * repository key it was written with, so the repository's keys from before a rename count too
     * (#88).
     */
    override fun changesMatching(
        repository: NodeKey,
        sha: String,
    ): List<NodeKey> =
        neo4jClient
            .query(
                """
                OPTIONAL MATCH (r:${label(REPOSITORY)} { key: ${'$'}repository })
                WITH [${'$'}repository] + coalesce(r.${ProvenanceMapper.PREVIOUS_KEYS}, []) AS keys
                MATCH (c:${label(CHANGE)})
                WHERE c.repositoryKey IN keys AND c.prov_validTo IS NULL
                  AND (c.sha STARTS WITH ${'$'}sha OR (${'$'}sha STARTS WITH c.sha AND size(c.sha) >= $MIN_SHA))
                RETURN c.key AS key ORDER BY key
                """.trimIndent(),
            ).bindAll(mapOf("repository" to repository.key, "sha" to sha))
            .fetch()
            .all()
            .map { NodeKey(CHANGE, it["key"].toString()) }

    override fun deploymentsCarrying(
        changes: Collection<NodeKey>,
        lineage: LineageTraversal,
    ): Set<NodeKey> {
        if (changes.isEmpty() || lineage.deployedAs.isEmpty() || lineage.contains.isEmpty()) return emptySet()
        return neo4jClient
            .query(
                """
                UNWIND ${'$'}keys AS key
                MATCH (c:${label(CHANGE)} { key: key })<-[k:${types(lineage.contains)}]-(a:${label(ARTIFACT)})
                      -[x:${types(lineage.deployedAs)}]->(d:${label(DEPLOYMENT)})
                WHERE ${open("c", "k", "a", "x", "d")}
                RETURN DISTINCT d.key AS key
                """.trimIndent(),
            ).bindAll(mapOf("keys" to changes.map { it.key }.distinct()))
            .fetch()
            .all()
            .mapTo(linkedSetOf()) { NodeKey(DEPLOYMENT, it["key"].toString()) }
    }

    private fun label(type: String): String = cypher.nodeLabel(type)

    private fun types(edges: Set<String>): String = edges.joinToString("|") { cypher.edgeType(it) }

    private fun open(vararg variables: String): String = variables.joinToString(" AND ") { "$it.prov_validTo IS NULL" }

    private companion object {
        const val ARTIFACT = "Artifact"
        const val DEPLOYMENT = "Deployment"
        const val CHANGE = "Change"
        const val REPOSITORY = "Repository"
        const val WORK_ITEM = "ExternalWorkItem"
        const val MIN_SHA = 4
    }
}
