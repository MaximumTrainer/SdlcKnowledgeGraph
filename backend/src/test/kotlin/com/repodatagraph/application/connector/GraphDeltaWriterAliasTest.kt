package com.repodatagraph.application.connector

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.FactLifecycle
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * A connector reporting a repository with its provider id lands on the node that already holds the
 * id (#88), so a rename or a transfer seen by a sync moves the node rather than leaving a second one
 * beside it with every edge still on the first.
 */
class GraphDeltaWriterAliasTest {
    private val graphStore: GraphStore = mock()
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val writer =
        GraphDeltaWriter(
            graphStore,
            mock<FactLifecycle>(),
            IdentityResolver(),
            DerivedProperties(GitRemoteParser()),
            Clock.fixed(now, ZoneOffset.UTC),
            YamlOntologyLoader(DefaultResourceLoader()).load(),
        )
    private val github = ConnectorDescriptor("github", "github", setOf("Repository"), emptySet(), setOf(Capability.FULL))

    private val oldKey = NodeKey("Repository", "github.com/acme/payments")
    private val newKey = NodeKey("Repository", "github.com/acme-platform/payments-service")
    private val alias = mapOf("provider" to "github", "providerId" to "123456")

    private fun reported(url: String) =
        GraphDelta(
            nodes =
                listOf(
                    NodeUpsert(
                        "Repository",
                        mapOf(
                            "url" to url,
                            "defaultBranch" to "main",
                            "topics" to emptyList<String>(),
                            "codeowners" to emptyList<String>(),
                        ) +
                            alias,
                    ),
                ),
        )

    private fun stored(key: NodeKey) = GraphNode(key, alias + ("url" to "https://${key.key}"), Provenance.manual(Instant.EPOCH))

    @Test
    fun `a repository reported under a new remote with the id another node holds renames that node`() {
        whenever(graphStore.findNodeByAlias("Repository", alias)).thenReturn(stored(oldKey))
        whenever(graphStore.renameNode(eq(oldKey), any())).thenAnswer { it.arguments[1] }

        writer.apply(reported("https://github.com/acme-platform/payments-service"), github, "run-1")

        val renamed = argumentCaptor<GraphNode>()
        verify(graphStore).renameNode(eq(oldKey), renamed.capture())
        assertThat(renamed.firstValue.key).isEqualTo(newKey)
        assertThat(renamed.firstValue.provenance.previousKeys).containsExactly(oldKey.key)
        assertThat(renamed.firstValue.provenance.sourceSystem).isEqualTo("github")
        assertThat(renamed.firstValue.provenance.syncRunId).isEqualTo("run-1")
        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `a repository reported under the remote it already has is written as before`() {
        whenever(graphStore.findNodeByAlias("Repository", alias)).thenReturn(stored(oldKey))

        writer.apply(reported("https://github.com/acme/payments"), github, "run-1")

        verify(graphStore, never()).renameNode(any(), any())
        verify(graphStore).upsertNode(org.mockito.kotlin.argThat { key == oldKey })
    }

    /**
     * Two nodes for one repository is the merge #74's review queue is for, not something a sync decides.
     * The reported node is written without the id, so the constraint is not broken and the sync is not
     * failed, and the node that holds the id keeps it until the two are merged.
     */
    @Test
    fun `a rename onto a remote another node holds writes that node without the id`() {
        whenever(graphStore.findNodeByAlias("Repository", alias)).thenReturn(stored(oldKey))
        whenever(graphStore.findNode(newKey)).thenReturn(GraphNode(newKey, emptyMap(), Provenance.manual(Instant.EPOCH)))

        writer.apply(reported("https://github.com/acme-platform/payments-service"), github, "run-1")

        val written = argumentCaptor<GraphNode>()
        verify(graphStore).upsertNode(written.capture())
        assertThat(written.firstValue.key).isEqualTo(newKey)
        assertThat(written.firstValue.props).doesNotContainKeys("provider", "providerId")
        verify(graphStore, never()).renameNode(any(), any())
    }
}
