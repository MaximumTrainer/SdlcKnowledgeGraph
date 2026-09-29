package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.exception.UnknownEdgeTypeException
import com.repodatagraph.domain.exception.UnknownNodeTypeException
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.data.neo4j.core.Neo4jClient

/**
 * A count interpolates a label or relationship type into its Cypher, which has no parameter form for
 * either, so a name the ontology does not declare must never reach Neo4j. Counting what it does
 * declare is covered against a real Neo4j by metrics.feature.
 */
class Neo4jGraphCensusTest {
    private val client: Neo4jClient = mock()
    private val registry =
        OntologyRegistry(
            version = "1.0.0",
            nodeTypes =
                listOf(
                    NodeTypeDef("Team", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, required = true))),
                ),
            edgeTypes = emptyList(),
        )
    private val census = Neo4jGraphCensus(client, CypherBuilder(registry))

    @Test
    fun `an undeclared node type is refused before any query is sent`() {
        assertThatThrownBy { census.countNodes("Team) DETACH DELETE (n") }.isInstanceOf(UnknownNodeTypeException::class.java)
        verifyNoInteractions(client)
    }

    @Test
    fun `an undeclared edge type is refused before any query is sent`() {
        assertThatThrownBy { census.countEdges("OWNED_BY]->() DETACH DELETE (") }.isInstanceOf(UnknownEdgeTypeException::class.java)
        verifyNoInteractions(client)
    }
}
