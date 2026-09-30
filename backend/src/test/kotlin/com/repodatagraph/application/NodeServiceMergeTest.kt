package com.repodatagraph.application

import com.repodatagraph.domain.exception.NodeAlreadyMergedException
import com.repodatagraph.domain.exception.NodeExistsException
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * A key a merge or a rename left still finds the node that took it over (#98, FR-4): a read follows
 * the merge, a create of it is told where the node went, and an update addressed at it is refused
 * rather than bringing the retired node back. A write of an Artifact is offered to the folding
 * that folds a digest-less twin into it (FR-2).
 */
class NodeServiceMergeTest {
    private val graphStore: GraphStore = mock()
    private val folded = mutableListOf<GraphNode>()
    private val registry =
        OntologyRegistry(
            version = "1.5.0",
            nodeTypes =
                listOf(
                    NodeTypeDef("Team", null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, true))),
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
            IdentityFolding { folded += it },
        )

    private val earlier = Instant.parse("2026-01-01T00:00:00Z")
    private val merged = NodeKey("Team", "platform")
    private val target = NodeKey("Team", "platform-engineering")

    private fun team(
        key: NodeKey,
        validTo: Instant? = null,
        previousKeys: List<String> = emptyList(),
    ) = GraphNode(key, mapOf("name" to key.key), Provenance.manual(earlier).copy(validTo = validTo, previousKeys = previousKeys))

    @Test
    fun `reading a merged node's key answers with the node it was merged into`() {
        whenever(graphStore.findNode(merged)).thenReturn(team(merged, validTo = earlier.plusSeconds(60)))
        whenever(graphStore.mergedInto(merged)).thenReturn(target)
        whenever(graphStore.findNode(target)).thenReturn(team(target))

        assertThat(service.get("Team", merged.key)?.key).isEqualTo(target)
    }

    @Test
    fun `a node that is merely retired still reads as itself`() {
        whenever(graphStore.findNode(merged)).thenReturn(team(merged, validTo = earlier.plusSeconds(60)))

        assertThat(service.get("Team", merged.key)?.key).isEqualTo(merged)
    }

    @Test
    fun `a key nothing holds now finds the node that held it before, as a rename's does`() {
        whenever(graphStore.findNodeByPreviousKey(merged)).thenReturn(team(target, previousKeys = listOf(merged.key)))

        assertThat(service.get("Team", merged.key)?.key).isEqualTo(target)
    }

    @Test
    fun `as of an instant after the merge, the merged key reads as the node it went into, then`() {
        val after = earlier.plusSeconds(120)
        whenever(graphStore.findNode(merged, after)).thenReturn(null)
        whenever(graphStore.mergedInto(merged)).thenReturn(target)
        whenever(graphStore.findNode(target, after)).thenReturn(team(target))

        assertThat(service.get("Team", merged.key, after)?.key).isEqualTo(target)
    }

    @Test
    fun `creating a merged node's key again is refused, naming the node it went into`() {
        whenever(graphStore.findNode(merged)).thenReturn(team(merged, validTo = earlier.plusSeconds(60)))
        whenever(graphStore.mergedInto(merged)).thenReturn(target)

        assertThatThrownBy { service.create("Team", mapOf("name" to "platform")) }
            .isInstanceOf(NodeExistsException::class.java)
            .satisfies({ assertThat((it as NodeExistsException).existingId).isEqualTo(target.id) })
        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `an update addressed at a merged node is refused rather than bringing it back`() {
        whenever(graphStore.findNode(merged)).thenReturn(team(merged, validTo = earlier.plusSeconds(60)))
        whenever(graphStore.mergedInto(merged)).thenReturn(target)

        assertThatThrownBy { service.update("Team", merged.key, mapOf("name" to "platform")) }
            .isInstanceOf(NodeAlreadyMergedException::class.java)
        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `every node written is offered to the folding, as it was stored`() {
        whenever(graphStore.upsertNode(any())).thenAnswer { it.getArgument<GraphNode>(0) }

        service.create("Team", mapOf("name" to "platform"))

        assertThat(folded.map { it.key }).containsExactly(merged)
    }
}
