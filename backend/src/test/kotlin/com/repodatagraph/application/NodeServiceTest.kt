package com.repodatagraph.application

import com.repodatagraph.domain.exception.ImmutableIdentityException
import com.repodatagraph.domain.exception.NodeExistsException
import com.repodatagraph.domain.exception.NodeHasEdgesException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.exception.NodeTypeNotFoundException
import com.repodatagraph.domain.exception.NodeValidationException
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import com.repodatagraph.domain.port.out.GraphStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The rules the endpoints delegate: identity is derived here and never accepted from the client,
 * a second node cannot take a key that is already held, and an update may not move a node to a
 * different identity.
 */
class NodeServiceTest {
    private val graphStore: GraphStore = mock()
    private val registry =
        OntologyRegistry(
            version = "1.0.0",
            nodeTypes =
                listOf(
                    NodeTypeDef(
                        name = "Team",
                        description = null,
                        identity = listOf("name"),
                        properties =
                            listOf(
                                PropertyDef("name", PropertyType.STRING, required = true),
                                PropertyDef("email", PropertyType.STRING),
                            ),
                    ),
                ),
            edgeTypes =
                listOf(EdgeTypeDef("OWNED_BY", null, listOf("Team"), listOf("Team"), "OWNS")),
        )
    private val service = NodeService(registry, IdentityResolver(), PropertyValidator(), graphStore)

    private val platformKey = NodeKey("Team", "platform")
    private val platform = GraphNode(platformKey, mapOf("name" to "platform"), Provenance.manual())

    @Test
    fun `a type the registry does not declare is not found`() {
        assertThatThrownBy { service.create("Widget", mapOf("name" to "x")) }
            .isInstanceOf(NodeTypeNotFoundException::class.java)

        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `an invalid node is refused before it reaches the store`() {
        assertThatThrownBy { service.create("Team", emptyMap()) }
            .isInstanceOf(NodeValidationException::class.java)

        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `the key is derived from the properties, not supplied`() {
        whenever(graphStore.findNode(platformKey)).thenReturn(null)
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }

        val created = service.create("Team", mapOf("name" to "Platform"))

        assertThat(created.key).isEqualTo(platformKey)
        assertThat(created.id).isEqualTo("Team:platform")
    }

    @Test
    fun `a manual write carries manual provenance`() {
        whenever(graphStore.findNode(platformKey)).thenReturn(null)
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }

        val created = service.create("Team", mapOf("name" to "platform"))

        assertThat(created.provenance.sourceSystem).isEqualTo("manual")
        assertThat(created.provenance.confidence).isEqualTo(1.0)
        assertThat(created.provenance.inferred).isFalse()
    }

    @Test
    fun `creating a node whose key is already held reports the id that holds it`() {
        whenever(graphStore.findNode(platformKey)).thenReturn(platform)

        assertThatThrownBy { service.create("Team", mapOf("name" to "platform")) }
            .isInstanceOf(NodeExistsException::class.java)
            .hasFieldOrPropertyWithValue("existingId", "Team:platform")

        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `an update keeps the same key and refreshes provenance`() {
        whenever(graphStore.findNode(platformKey)).thenReturn(platform)
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }

        val updated = service.update("Team", "platform", mapOf("name" to "platform", "email" to "p@acme.example"))

        assertThat(updated.key).isEqualTo(platformKey)
        assertThat(updated.props["email"]).isEqualTo("p@acme.example")
        assertThat(updated.provenance.sourceSystem).isEqualTo("manual")
    }

    @Test
    fun `an update that would change the identity names the property responsible`() {
        whenever(graphStore.findNode(platformKey)).thenReturn(platform)

        assertThatThrownBy { service.update("Team", "platform", mapOf("name" to "core")) }
            .isInstanceOf(ImmutableIdentityException::class.java)
            .hasFieldOrPropertyWithValue("fields", listOf("name"))

        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `updating a node that does not exist is not found`() {
        whenever(graphStore.findNode(platformKey)).thenReturn(null)

        assertThatThrownBy { service.update("Team", "platform", mapOf("name" to "platform")) }
            .isInstanceOf(NodeNotFoundException::class.java)
    }

    @Test
    fun `a full node id is accepted where a key is expected`() {
        whenever(graphStore.findNode(platformKey)).thenReturn(platform)

        assertThat(service.get("Team", "Team:platform")).isEqualTo(platform)
    }

    @Test
    fun `deleting a node that still has edges is refused, and says how many`() {
        whenever(graphStore.findNode(platformKey)).thenReturn(platform)
        whenever(graphStore.countEdges(platformKey)).thenReturn(2L)

        assertThatThrownBy { service.delete("Team", "platform", cascade = false) }
            .isInstanceOf(NodeHasEdgesException::class.java)
            .hasFieldOrPropertyWithValue("edgeCount", 2)

        verify(graphStore, never()).deleteNode(any(), any())
    }

    @Test
    fun `cascade deletes without counting first`() {
        whenever(graphStore.findNode(platformKey)).thenReturn(platform)

        service.delete("Team", "platform", cascade = true)

        verify(graphStore).deleteNode(platformKey, true)
        verify(graphStore, never()).countEdges(any())
    }

    @Test
    fun `a page asks the store for one more than it needs, and reports a cursor only when there is more`() {
        val teams = (1..3).map { GraphNode(NodeKey("Team", "team$it"), mapOf("name" to "team$it"), Provenance.manual()) }
        whenever(graphStore.findNodes(eq("Team"), any(), anyOrNull(), eq(3))).thenReturn(teams)

        val page = service.list("Team", limit = 2, cursor = null)

        assertThat(page.items).hasSize(2)
        assertThat(page.nextCursor).isEqualTo("team2")
    }

    @Test
    fun `the last page has no cursor`() {
        val teams = listOf(GraphNode(NodeKey("Team", "team1"), mapOf("name" to "team1"), Provenance.manual()))
        whenever(graphStore.findNodes(eq("Team"), any(), anyOrNull(), eq(3))).thenReturn(teams)

        val page = service.list("Team", limit = 2, cursor = null)

        assertThat(page.items).hasSize(1)
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `a cursor is passed to the store as the key to start after`() {
        whenever(graphStore.findNodes(eq("Team"), any(), anyOrNull(), any())).thenReturn(emptyList())

        service.list("Team", limit = 2, cursor = "team1")

        val afterKey = argumentCaptor<String>()
        verify(graphStore).findNodes(eq("Team"), any(), afterKey.capture(), any())
        assertThat(afterKey.firstValue).isEqualTo("team1")
    }
}
