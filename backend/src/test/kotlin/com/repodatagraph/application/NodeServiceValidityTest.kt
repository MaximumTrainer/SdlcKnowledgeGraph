package com.repodatagraph.application

import com.repodatagraph.domain.exception.NodeValidationException
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Principal
import com.repodatagraph.domain.model.PrincipalType
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import com.repodatagraph.domain.ontology.SourceSystemDef
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.SourceWriteAuthorization
import com.repodatagraph.observability.GraphWriteMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * A node's validity (#93, FR-4 and FR-5): a read as of an instant sees the node only while it held
 * then, and a write keeps the instant the fact began while it goes on holding, closes it through
 * validTo, and refuses to end it before it began.
 */
class NodeServiceValidityTest {
    private val graphStore: GraphStore = mock()
    private val registry =
        OntologyRegistry(
            version = "1.0.0",
            nodeTypes =
                listOf(
                    NodeTypeDef("Team", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, required = true))),
                ),
            edgeTypes = emptyList(),
            sources = listOf(SourceSystemDef("manual")),
        )
    private val service =
        NodeService(
            registry,
            IdentityResolver(),
            DerivedProperties(GitRemoteParser()),
            PropertyValidator(),
            graphStore,
            GraphWriteMetrics(SimpleMeterRegistry()),
            StatedProvenance(registry, SourceWriteAuthorization { }) { Principal("dan", PrincipalType.USER) },
        )

    private val key = NodeKey("Team", "platform")
    private val began = Instant.parse("2026-09-01T00:00:00Z")
    private val current = GraphNode(key, mapOf("name" to "platform"), Provenance.manual(began))
    private val closed =
        current.copy(provenance = current.provenance.copy(validTo = Instant.parse("2026-09-10T00:00:00Z")))

    private fun written(): GraphNode {
        val node = argumentCaptor<GraphNode>()
        verify(graphStore).upsertNode(node.capture())
        return node.firstValue
    }

    @Test
    fun `a read as of an instant asks the store for the node valid then`() {
        val asOf = Instant.parse("2026-09-05T00:00:00Z")
        whenever(graphStore.findNode(key, asOf)).thenReturn(current)

        assertThat(service.get("Team", "platform", asOf)).isEqualTo(current)
        verify(graphStore, never()).findNode(key)
    }

    @Test
    fun `a read without one is the current read it always was`() {
        whenever(graphStore.findNode(key)).thenReturn(closed)

        assertThat(service.get("Team", "platform")).isEqualTo(closed)
    }

    @Test
    fun `an update of a current node keeps the validFrom it began with`() {
        whenever(graphStore.findNode(key)).thenReturn(current)
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }

        val updated = service.update("Team", "platform", mapOf("name" to "platform"))

        assertThat(written().provenance.validFrom).isEqualTo(began)
        assertThat(updated.provenance.validFrom).isEqualTo(began)
        assertThat(updated.provenance.validTo).isNull()
        assertThat(updated.provenance.ingestedAt).isAfter(began)
    }

    @Test
    fun `an update naming validTo closes the node, from when it began`() {
        val ended = Instant.parse("2026-09-15T00:00:00Z")
        whenever(graphStore.findNode(key)).thenReturn(current)
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }

        service.update("Team", "platform", mapOf("name" to "platform"), validTo = ended)

        assertThat(written().provenance.validFrom).isEqualTo(began)
        assertThat(written().provenance.validTo).isEqualTo(ended)
    }

    @Test
    fun `a validTo before the node began is refused naming provenance validTo, and nothing is written`() {
        whenever(graphStore.findNode(key)).thenReturn(current)

        assertThatThrownBy {
            service.update("Team", "platform", mapOf("name" to "platform"), validTo = Instant.parse("2026-08-01T00:00:00Z"))
        }.isInstanceOfSatisfying(NodeValidationException::class.java) { refused ->
            assertThat(refused.errors.map { it.field }).containsExactly("provenance.validTo")
        }
        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `an update without validTo reopens a closed node, beginning now`() {
        whenever(graphStore.findNode(key)).thenReturn(closed)
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }

        service.update("Team", "platform", mapOf("name" to "platform"))

        assertThat(written().provenance.validTo).isNull()
        assertThat(written().provenance.validFrom).isAfter(closed.provenance.validTo)
    }

    @Test
    fun `an update keeping a closed node closed keeps when it began`() {
        whenever(graphStore.findNode(key)).thenReturn(closed)
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }

        service.update("Team", "platform", mapOf("name" to "platform"), validTo = Instant.parse("2026-09-12T00:00:00Z"))

        assertThat(written().provenance.validFrom).isEqualTo(began)
    }
}
