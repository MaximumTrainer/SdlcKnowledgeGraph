package com.repodatagraph.application

import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.EdgeRequest
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Principal
import com.repodatagraph.domain.model.PrincipalType
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.observability.GraphWriteMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Every graph write through the API is counted by type and outcome (#44, FR7): what was created,
 * updated, deleted, and what the ontology refused. A refusal counts even though nothing reached the
 * store, because a rising refusal rate is what says a client or a connector has gone wrong.
 */
class GraphWriteCountingTest {
    private val meters = SimpleMeterRegistry()
    private val metrics = GraphWriteMetrics(meters)
    private val graphStore: GraphStore = mock()
    private val registry =
        OntologyRegistry(
            version = "1.0.0",
            nodeTypes =
                listOf(
                    NodeTypeDef("Team", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, required = true))),
                    NodeTypeDef("Repository", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, required = true))),
                ),
            edgeTypes = listOf(EdgeTypeDef("OWNED_BY", null, listOf("Repository"), listOf("Team"), "OWNS")),
        )
    private val nodes =
        NodeService(
            registry,
            IdentityResolver(),
            DerivedProperties(GitRemoteParser()),
            PropertyValidator(),
            graphStore,
            metrics,
            StatedProvenance(registry, {}) { WRITER },
        )
    private val edges =
        EdgeService(registry, PropertyValidator(), graphStore, metrics, StatedProvenance(registry, {}) { WRITER })

    private val platform = NodeKey("Team", "platform")
    private val web = NodeKey("Repository", "web")

    private fun count(
        name: String,
        type: String,
        outcome: String,
    ) = meters
        .find(name)
        .tags("type", type, "outcome", outcome)
        .counter()
        ?.count() ?: 0.0

    init {
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }
        whenever(graphStore.upsertEdge(any())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `a created node counts as created`() {
        nodes.create("Team", mapOf("name" to "platform"))

        assertThat(count("sdlc.node.writes", "Team", "created")).isEqualTo(1.0)
    }

    @Test
    fun `an updated node counts as updated`() {
        whenever(graphStore.findNode(platform)).thenReturn(GraphNode(platform, mapOf("name" to "platform"), Provenance.manual()))

        nodes.update("Team", "platform", mapOf("name" to "platform"))

        assertThat(count("sdlc.node.writes", "Team", "updated")).isEqualTo(1.0)
    }

    @Test
    fun `a deleted node counts as deleted`() {
        whenever(graphStore.findNode(platform)).thenReturn(GraphNode(platform, mapOf("name" to "platform"), Provenance.manual()))
        whenever(graphStore.deleteNode(eq(platform), any())).thenReturn(true)

        nodes.delete("Team", "platform", cascade = true)

        assertThat(count("sdlc.node.writes", "Team", "deleted")).isEqualTo(1.0)
    }

    @Test
    fun `a node the ontology refuses counts as rejected`() {
        assertThatThrownBy { nodes.create("Team", mapOf("name" to "")) }

        assertThat(count("sdlc.node.writes", "Team", "rejected")).isEqualTo(1.0)
        assertThat(count("sdlc.node.writes", "Team", "created")).isEqualTo(0.0)
    }

    @Test
    fun `a new edge counts as created, and restating it as updated`() {
        val request = EdgeRequest("OWNED_BY", web.id, platform.id, emptyMap())

        edges.create(request)
        whenever(graphStore.findEdge(eq("OWNED_BY"), eq(web), eq(platform)))
            .thenReturn(GraphEdge("OWNED_BY", web, platform, emptyMap(), Provenance.manual()))
        edges.create(request)

        assertThat(count("sdlc.edge.writes", "OWNED_BY", "created")).isEqualTo(1.0)
        assertThat(count("sdlc.edge.writes", "OWNED_BY", "updated")).isEqualTo(1.0)
    }

    @Test
    fun `an edge the ontology refuses counts as rejected`() {
        assertThatThrownBy { edges.create(EdgeRequest("OWNED_BY", platform.id, web.id, emptyMap())) }

        assertThat(count("sdlc.edge.writes", "OWNED_BY", "rejected")).isEqualTo(1.0)
    }

    @Test
    fun `a deleted edge counts as deleted, and one that was not there does not`() {
        whenever(graphStore.deleteEdge(eq("OWNED_BY"), eq(web), eq(platform))).thenReturn(true, false)

        edges.delete("OWNED_BY", web.id, platform.id)
        edges.delete("OWNED_BY", web.id, platform.id)

        assertThat(count("sdlc.edge.writes", "OWNED_BY", "deleted")).isEqualTo(1.0)
    }

    private companion object {
        val WRITER = Principal("dan", PrincipalType.USER)
    }
}
