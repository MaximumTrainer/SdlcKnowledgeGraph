package com.repodatagraph.application.neighbourhood

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ReachedNode
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant

/**
 * How a walked neighbourhood becomes what the graph view draws (#9, FR1 to FR3): a label from the
 * registry's displayProperty, an edge id the client can merge by, and the cap applied nearest first.
 */
class SubgraphMapperTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val mapper = SubgraphMapper(registry)

    private val at = Instant.parse("2026-09-01T10:00:00Z")
    private val stated = Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at)

    private fun node(
        id: String,
        props: Map<String, Any?> = emptyMap(),
    ) = GraphNode(NodeKey.parse(id), props, stated)

    private val payments = node("Repository:github.com/acme/payments", mapOf("name" to "payments"))
    private val sharedLib = node("Repository:github.com/acme/shared-lib", mapOf("name" to "shared-lib"))
    private val platform = node("Team:platform", mapOf("name" to "platform"))
    private val bucket = node("CloudResource:aws:arn:aws:s3:::acme-logs", mapOf("name" to "acme-logs"))

    private fun edge(
        type: String,
        from: GraphNode,
        to: GraphNode,
        confidence: Double = 1.0,
        inferred: Boolean = false,
    ) = GraphEdge(type, from.key, to.key, emptyMap(), stated.copy(confidence = confidence, inferred = inferred))

    @Test
    fun `a node is labelled with its type's display property`() {
        assertEquals("payments", mapper.label(payments))
        assertEquals("platform", mapper.label(platform))
        assertEquals("acme-logs", mapper.label(bucket))
    }

    @Test
    fun `a node without its display property is labelled with its key, so no label is ever empty`() {
        assertEquals("github.com/acme/unnamed", mapper.label(node("Repository:github.com/acme/unnamed")))
        assertEquals("github.com/acme/blank", mapper.label(node("Repository:github.com/acme/blank", mapOf("name" to "  "))))
    }

    @Test
    fun `a type that declares no display property is labelled with its key`() {
        val bare =
            OntologyRegistry(
                "1.0.0",
                listOf(NodeTypeDef("Thing", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING)))),
                emptyList(),
            )

        assertEquals("one", SubgraphMapper(bare).label(node("Thing:one", mapOf("name" to "The One"))))
    }

    @Test
    fun `a list-valued display property reads as its values`() {
        val lists =
            OntologyRegistry(
                "1.0.0",
                listOf(
                    NodeTypeDef(
                        "Thing",
                        null,
                        listOf("id"),
                        listOf(PropertyDef("id", PropertyType.STRING), PropertyDef("tags", PropertyType.STRING_ARRAY)),
                        displayProperty = "tags",
                    ),
                ),
                emptyList(),
            )

        assertEquals("a, b", SubgraphMapper(lists).label(node("Thing:one", mapOf("tags" to listOf("a", "b")))))
    }

    @Test
    fun `an edge id is its type and both ends, the same every time`() {
        val dependsOn = edge("DEPENDS_ON", payments, sharedLib)

        assertEquals(
            "DEPENDS_ON:Repository:github.com/acme/payments>Repository:github.com/acme/shared-lib",
            SubgraphMapper.edgeId(dependsOn),
        )
        assertEquals(SubgraphMapper.edgeId(dependsOn), SubgraphMapper.edgeId(dependsOn.copy(props = mapOf("kind" to "api"))))
    }

    @Test
    fun `an edge carries its inverse and the confidence and inferred flag of its provenance`() {
        val view =
            mapper.map(
                root = payments,
                reached = listOf(ReachedNode(payments, 0), ReachedNode(bucket, 1)),
                edges = listOf(edge("OWNS_RESOURCE", payments, bucket, confidence = 0.7, inferred = true)),
                limit = 500,
                truncated = false,
            )

        val owns = view.edges.single()
        assertEquals("OWNS_RESOURCE", owns.type)
        assertEquals("OWNED_BY_REPO", owns.inverse)
        assertEquals(payments.id, owns.from)
        assertEquals(bucket.id, owns.to)
        assertEquals(0.7, owns.confidence)
        assertTrue(owns.inferred)
    }

    @Test
    fun `nodes keep the order they were reached in, nearest first, each once`() {
        val view =
            mapper.map(
                root = payments,
                reached = listOf(ReachedNode(payments, 0), ReachedNode(platform, 2), ReachedNode(sharedLib, 1), ReachedNode(sharedLib, 1)),
                edges = emptyList(),
                limit = 500,
                truncated = false,
            )

        assertEquals(listOf(payments.id, sharedLib.id, platform.id), view.nodes.map { it.node.id })
        assertEquals(listOf(0, 1, 2), view.nodes.map { it.distance })
        assertEquals(listOf("payments", "shared-lib", "platform"), view.nodes.map { it.label })
        assertEquals(payments, view.root)
    }

    @Test
    fun `the cap keeps the nodes closest to the root and drops the edges it cut`() {
        val view =
            mapper.map(
                root = payments,
                reached = listOf(ReachedNode(payments, 0), ReachedNode(platform, 2), ReachedNode(sharedLib, 1)),
                edges = listOf(edge("DEPENDS_ON", payments, sharedLib), edge("OWNED_BY", sharedLib, platform)),
                limit = 2,
                truncated = false,
            )

        assertEquals(listOf(payments.id, sharedLib.id), view.nodes.map { it.node.id })
        assertEquals(listOf("DEPENDS_ON"), view.edges.map { it.type })
        assertTrue(view.truncated)
    }

    @Test
    fun `under the cap nothing is cut, unless the walk itself stopped short`() {
        val reached = listOf(ReachedNode(payments, 0), ReachedNode(sharedLib, 1))

        assertFalse(mapper.map(payments, reached, emptyList(), limit = 2, truncated = false).truncated)
        assertTrue(mapper.map(payments, reached, emptyList(), limit = 2, truncated = true).truncated)
    }

    @Test
    fun `an edge found twice is listed once, and edges are in id order`() {
        val dependsOn = edge("DEPENDS_ON", payments, sharedLib)
        val owned = edge("OWNED_BY", payments, platform)

        val view =
            mapper.map(
                root = payments,
                reached = listOf(ReachedNode(payments, 0), ReachedNode(sharedLib, 1), ReachedNode(platform, 1)),
                edges = listOf(owned, dependsOn, dependsOn),
                limit = 500,
                truncated = false,
            )

        assertEquals(listOf(SubgraphMapper.edgeId(dependsOn), SubgraphMapper.edgeId(owned)), view.edges.map { it.id })
    }
}
