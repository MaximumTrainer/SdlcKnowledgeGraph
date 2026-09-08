package com.repodatagraph.application

import com.repodatagraph.domain.exception.EdgeNotAllowedException
import com.repodatagraph.domain.exception.EdgeValidationException
import com.repodatagraph.domain.exception.SelfEdgeException
import com.repodatagraph.domain.exception.UnknownEdgeTypeException
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.EdgeRequest
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.IncidentEdge
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.EdgeTypeDef
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The rules the edge endpoints delegate.
 *
 * The one worth reading twice is [an edge is read under the name that end sees]: a relationship is
 * stored once, so what changes between the two ends is only the name it is read under. If that were
 * two stored edges they could disagree, which is the failure the declared inverse exists to prevent.
 */
class EdgeServiceTest {
    private val graphStore: GraphStore = mock()

    private val registry =
        OntologyRegistry(
            version = "1.0.0",
            nodeTypes =
                listOf(
                    NodeTypeDef("Repository", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, true))),
                    NodeTypeDef("Team", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, true))),
                    NodeTypeDef("Environment", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, true))),
                ),
            edgeTypes =
                listOf(
                    EdgeTypeDef(
                        name = "DEPENDS_ON",
                        description = null,
                        from = listOf("Repository"),
                        to = listOf("Repository"),
                        inverse = "DEPENDED_ON_BY",
                        properties =
                            listOf(
                                PropertyDef(
                                    "kind",
                                    PropertyType.STRING,
                                    required = true,
                                    enum = listOf("library", "api", "event", "data"),
                                ),
                                PropertyDef("manifest", PropertyType.STRING),
                            ),
                    ),
                    EdgeTypeDef("OWNED_BY", null, listOf("Repository"), listOf("Team"), "OWNS"),
                ),
        )

    private val service = EdgeService(registry, PropertyValidator(), graphStore)

    private val payments = NodeKey("Repository", "acme/payments")
    private val sharedLib = NodeKey("Repository", "acme/shared-lib")
    private val library = mapOf<String, Any?>("kind" to "library")

    private fun request(
        type: String = "DEPENDS_ON",
        from: NodeKey = payments,
        to: NodeKey = sharedLib,
        props: Map<String, Any?> = library,
    ) = EdgeRequest(type, from.id, to.id, props)

    @Test
    fun `an undeclared edge type is refused before anything is written`() {
        assertThatThrownBy { service.create(request(type = "SMELLS_LIKE")) }
            .isInstanceOf(UnknownEdgeTypeException::class.java)

        verify(graphStore, never()).upsertEdge(any())
    }

    @Test
    fun `a pair the ontology does not allow is refused, with the pairs that are allowed`() {
        assertThatThrownBy {
            service.create(request(type = "OWNED_BY", from = NodeKey("Environment", "production"), to = NodeKey("Team", "platform")))
        }.isInstanceOf(EdgeNotAllowedException::class.java)
            .hasFieldOrPropertyWithValue("allowed", listOf("Repository" to "Team"))

        verify(graphStore, never()).upsertEdge(any())
    }

    @Test
    fun `a node cannot be related to itself`() {
        assertThatThrownBy { service.create(request(to = payments)) }
            .isInstanceOf(SelfEdgeException::class.java)

        verify(graphStore, never()).upsertEdge(any())
    }

    @Test
    fun `a property outside the declared enum is refused`() {
        assertThatThrownBy { service.create(request(props = mapOf("kind" to "magic"))) }
            .isInstanceOf(EdgeValidationException::class.java)

        verify(graphStore, never()).upsertEdge(any())
    }

    @Test
    fun `a required edge property must be given`() {
        assertThatThrownBy { service.create(request(props = emptyMap())) }
            .isInstanceOf(EdgeValidationException::class.java)

        verify(graphStore, never()).upsertEdge(any())
    }

    @Test
    fun `a new edge is created, carries manual provenance and reports its inverse`() {
        whenever(graphStore.findEdge(any(), any(), any())).thenReturn(null)
        whenever(graphStore.upsertEdge(any())).thenAnswer { it.arguments[0] }

        val written = service.create(request())

        assertThat(written.created).isTrue()
        assertThat(written.inverse).isEqualTo("DEPENDED_ON_BY")
        assertThat(written.edge.provenance.sourceSystem).isEqualTo("manual")
        assertThat(written.edge.provenance.confidence).isEqualTo(1.0)
        assertThat(written.edge.provenance.inferred).isFalse()
    }

    @Test
    fun `stating the same relationship twice does not make a second one`() {
        val existing = GraphEdge("DEPENDS_ON", payments, sharedLib, library, Provenance.manual())
        whenever(graphStore.findEdge("DEPENDS_ON", payments, sharedLib)).thenReturn(existing)
        whenever(graphStore.upsertEdge(any())).thenAnswer { it.arguments[0] }

        val written = service.create(request(props = mapOf("kind" to "library", "manifest" to "build.gradle.kts")))

        assertThat(written.created).isFalse()
        assertThat(written.edge.props["manifest"]).isEqualTo("build.gradle.kts")
    }

    @Test
    fun `an edge is read under the name that end sees`() {
        val edge = GraphEdge("DEPENDS_ON", payments, sharedLib, library, Provenance.manual())
        val other = GraphNode(payments, emptyMap(), Provenance.manual())
        whenever(graphStore.findEdges(eq(sharedLib), any(), anyOrNull()))
            .thenReturn(listOf(IncidentEdge(edge, Direction.INCOMING, other)))

        val views = service.forNode("Repository", "acme/shared-lib", Direction.BOTH, null)

        assertThat(views).hasSize(1)
        assertThat(views[0].type).isEqualTo("DEPENDS_ON")
        assertThat(views[0].displayName).isEqualTo("DEPENDED_ON_BY")
        assertThat(views[0].inverse).isEqualTo("DEPENDED_ON_BY")
        assertThat(views[0].other.key).isEqualTo(payments)
    }

    @Test
    fun `an outgoing edge keeps its own name`() {
        val edge = GraphEdge("DEPENDS_ON", payments, sharedLib, library, Provenance.manual())
        val other = GraphNode(sharedLib, emptyMap(), Provenance.manual())
        whenever(graphStore.findEdges(eq(payments), any(), anyOrNull()))
            .thenReturn(listOf(IncidentEdge(edge, Direction.OUTGOING, other)))

        val views = service.forNode("Repository", "acme/payments", Direction.BOTH, null)

        assertThat(views[0].displayName).isEqualTo("DEPENDS_ON")
    }

    @Test
    fun `edges are ordered by type then by the other end, so a panel is stable`() {
        val owns = GraphEdge("OWNED_BY", payments, NodeKey("Team", "platform"), emptyMap(), Provenance.manual())
        val dependsB = GraphEdge("DEPENDS_ON", payments, NodeKey("Repository", "b"), library, Provenance.manual())
        val dependsA = GraphEdge("DEPENDS_ON", payments, NodeKey("Repository", "a"), library, Provenance.manual())
        whenever(graphStore.findEdges(any(), any(), anyOrNull())).thenReturn(
            listOf(
                IncidentEdge(owns, Direction.OUTGOING, GraphNode(NodeKey("Team", "platform"), emptyMap(), Provenance.manual())),
                IncidentEdge(dependsB, Direction.OUTGOING, GraphNode(NodeKey("Repository", "b"), emptyMap(), Provenance.manual())),
                IncidentEdge(dependsA, Direction.OUTGOING, GraphNode(NodeKey("Repository", "a"), emptyMap(), Provenance.manual())),
            ),
        )

        val views = service.forNode("Repository", "acme/payments", Direction.BOTH, null)

        assertThat(views.map { it.type to it.other.key.key })
            .containsExactly("DEPENDS_ON" to "a", "DEPENDS_ON" to "b", "OWNED_BY" to "platform")
    }

    @Test
    fun `deleting reports whether there was anything to delete`() {
        whenever(graphStore.deleteEdge("DEPENDS_ON", payments, sharedLib)).thenReturn(true)

        assertThat(service.delete("DEPENDS_ON", payments.id, sharedLib.id)).isTrue()
    }

    @Test
    fun `deleting an undeclared edge type is refused rather than reported as absent`() {
        assertThatThrownBy { service.delete("SMELLS_LIKE", payments.id, sharedLib.id) }
            .isInstanceOf(UnknownEdgeTypeException::class.java)
    }
}
