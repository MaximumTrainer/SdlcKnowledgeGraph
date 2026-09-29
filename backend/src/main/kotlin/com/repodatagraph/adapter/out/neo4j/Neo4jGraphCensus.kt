package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.port.out.GraphCensus
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository

/**
 * Counts by label and by relationship type, one statement each and with no parameters.
 *
 * The label or type is interpolated, as Cypher requires, but only after [CypherBuilder] has checked
 * the ontology declares it, so nothing a caller spells can reach the query. A bare count of one label
 * or one relationship type is answered from Neo4j's count store without touching the data, which is
 * what makes counting every type every few minutes cheap.
 */
@Repository
class Neo4jGraphCensus(
    private val neo4jClient: Neo4jClient,
    private val cypher: CypherBuilder,
) : GraphCensus {
    override fun countNodes(type: String): Long = count("MATCH (n:${cypher.nodeLabel(type)}) RETURN count(n) AS c")

    override fun countEdges(type: String): Long = count("MATCH ()-[r:${cypher.edgeType(type)}]->() RETURN count(r) AS c")

    private fun count(statement: String): Long =
        neo4jClient
            .query(statement)
            .fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)
}
