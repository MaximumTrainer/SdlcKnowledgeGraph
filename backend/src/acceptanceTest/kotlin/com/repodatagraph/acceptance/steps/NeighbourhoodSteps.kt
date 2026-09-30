package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The neighbourhood the graph view draws (#9). Seeded straight through [GraphStore], as the impact
 * steps are, so an edge carries exactly the provenance a scenario states; the rest of the background
 * ("owned by", "OWNS_RESOURCE ... inferred") is [ImpactSteps]'s.
 */
class NeighbourhoodSteps(
    private val world: ApiWorld,
    private val graphStore: GraphStore,
) {
    @Given("Repository {string} DEPENDS_ON Repository {string} with kind {string}")
    fun repositoryDependsOnWithKind(
        from: String,
        to: String,
        kind: String,
    ) {
        graphStore.upsertEdge(
            GraphEdge(
                type = "DEPENDS_ON",
                from = repository(from),
                to = repository(to),
                props = mapOf("kind" to kind),
                provenance = Provenance.manual(),
            ),
        )
    }

    @Given("Repository {string} DEPENDS_ON {int} distinct repositories")
    fun repositoryDependsOnMany(
        hub: String,
        count: Int,
    ) {
        val hubKey = repository(hub)
        repeat(count) { index ->
            graphStore.upsertEdge(
                GraphEdge(
                    type = "DEPENDS_ON",
                    from = hubKey,
                    to = repository("github.com/acme/dependency-${index.toString().padStart(3, '0')}"),
                    props = mapOf("kind" to "library"),
                    provenance = Provenance.manual(),
                ),
            )
        }
    }

    @Then("the neighbourhood root is {string}")
    fun theNeighbourhoodRootIs(id: String) {
        assertEquals(id, world.lastBody().path("root").asText(), "root in ${world.lastResponse().body}")
    }

    @Then("nodes has {int} entries and edges has {int} entries")
    fun nodesAndEdges(
        nodes: Int,
        edges: Int,
    ) {
        assertEquals(nodes, nodes().size, "nodes in ${world.lastResponse().body}")
        assertEquals(edges, edges().size, "edges in ${world.lastResponse().body}")
    }

    @Then("nodes has {int} entries and edges has {int} entry of type {string}")
    fun nodesAndEdgesOfType(
        nodes: Int,
        edges: Int,
        type: String,
    ) {
        nodesAndEdges(nodes, edges)
        assertTrue(edges().all { it.path("type").asText() == type }) { "edges in ${world.lastResponse().body}" }
    }

    @Then("nodes has {int} entries and truncated is {word}")
    fun nodesAndTruncated(
        nodes: Int,
        truncated: String,
    ) {
        assertEquals(nodes, nodes().size, "number of nodes")
        truncatedIs(truncated)
    }

    @Then("truncated is {word}")
    fun truncatedIs(truncated: String) {
        assertEquals(truncated.toBoolean(), world.lastBody().path("truncated").asBoolean(), "truncated")
        assertTrue(world.lastBody().path("truncated").isBoolean) { "truncated in ${world.lastResponse().body}" }
    }

    @Then("the {word} edge has inferred {word} and confidence {double}")
    fun theEdgeHasInferred(
        type: String,
        inferred: String,
        confidence: Double,
    ) {
        val edge =
            edges().singleOrNull { it.path("type").asText() == type }
                ?: error("no single $type edge in ${world.lastResponse().body}")
        assertEquals(inferred.toBoolean(), edge.path("inferred").asBoolean(), "inferred of $type")
        assertEquals(confidence, edge.path("confidence").asDouble(), TOLERANCE, "confidence of $type")
    }

    @Then("every node has a non-empty label")
    fun everyNodeHasALabel() {
        assertTrue(nodes().isNotEmpty())
        nodes().forEach { node ->
            assertTrue(node.path("label").isTextual && node.path("label").asText().isNotBlank()) { "no label on $node" }
        }
    }

    @Then("the node {string} is labelled {string}")
    fun theNodeIsLabelled(
        id: String,
        label: String,
    ) {
        assertEquals(label, node(id).path("label").asText())
    }

    @Then("the nodes include {string}")
    fun theNodesInclude(id: String) {
        node(id)
    }

    @Then("the edge {string} goes from {string} to {string}")
    fun theEdgeGoesFromTo(
        id: String,
        from: String,
        to: String,
    ) {
        val edge = edges().firstOrNull { it.path("id").asText() == id } ?: error("no edge $id in ${world.lastResponse().body}")
        assertEquals(from, edge.path("from").asText())
        assertEquals(to, edge.path("to").asText())
    }

    private fun nodes(): List<JsonNode> = world.lastBody().path("nodes").toList()

    private fun edges(): List<JsonNode> = world.lastBody().path("edges").toList()

    private fun node(id: String): JsonNode =
        nodes().firstOrNull { it.path("id").asText() == id } ?: error("$id is not in the neighbourhood: ${world.lastResponse().body}")

    /** Creates the repository when it is not there yet, with every property the registry requires. */
    private fun repository(key: String): NodeKey {
        val nodeKey = NodeKey("Repository", key)
        if (graphStore.findNode(nodeKey) == null) {
            val (host, org, name) = key.split('/')
            graphStore.upsertNode(
                GraphNode(
                    key = nodeKey,
                    props =
                        mapOf(
                            "url" to "https://$key",
                            "host" to host,
                            "org" to org,
                            "name" to name,
                            "defaultBranch" to "main",
                            "topics" to emptyList<String>(),
                            "codeowners" to emptyList<String>(),
                        ),
                    provenance = Provenance.manual(),
                ),
            )
        }
        return nodeKey
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
