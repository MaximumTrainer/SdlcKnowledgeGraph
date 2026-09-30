package com.repodatagraph.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.repodatagraph.domain.exception.ImmutableIdentityException
import com.repodatagraph.domain.exception.NodeExistsException
import com.repodatagraph.domain.exception.NodeNotFoundException
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
import com.repodatagraph.observability.EventLog
import com.repodatagraph.observability.GraphWriteMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * A provider id is an alias beside a Repository's key (#88): unique where present, consulted before
 * the key, and the one thing that lets a changed remote rename a node rather than describe another.
 *
 * Without an alias nothing here changes: identity is derived from the remote, a second node may not
 * take it, and an update that would move it is refused.
 */
class NodeServiceAliasTest {
    private val graphStore: GraphStore = mock()
    private val registry =
        OntologyRegistry(
            version = "1.2.0",
            nodeTypes =
                listOf(
                    NodeTypeDef(
                        name = "Repository",
                        description = null,
                        identity = listOf("host", "org", "name"),
                        properties =
                            listOf(
                                PropertyDef("url", PropertyType.STRING, required = true),
                                PropertyDef("host", PropertyType.STRING),
                                PropertyDef("org", PropertyType.STRING),
                                PropertyDef("name", PropertyType.STRING),
                                PropertyDef("description", PropertyType.STRING),
                                PropertyDef("provider", PropertyType.STRING, enum = listOf("github", "gitlab", "other")),
                                PropertyDef("providerId", PropertyType.STRING),
                            ),
                        alias = listOf("provider", "providerId"),
                    ),
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

    private val earlier = Instant.parse("2026-01-01T00:00:00Z")
    private val oldKey = NodeKey("Repository", "github.com/acme/payments")
    private val newKey = NodeKey("Repository", "github.com/acme-platform/payments-service")
    private val alias = mapOf("provider" to "github", "providerId" to "123456")

    private fun stored(
        key: NodeKey = oldKey,
        withAlias: Boolean = true,
        previousKeys: List<String> = emptyList(),
    ): GraphNode {
        val (host, org, name) = key.key.split('/')
        return GraphNode(
            key,
            mapOf("url" to "https://${key.key}", "host" to host, "org" to org, "name" to name) + if (withAlias) alias else emptyMap(),
            Provenance.manual(earlier).copy(previousKeys = previousKeys),
        )
    }

    private fun props(
        url: String,
        providerId: String? = "123456",
    ) = mapOf("url" to url) + if (providerId != null) mapOf("provider" to "github", "providerId" to providerId) else emptyMap()

    @Test
    fun `a create whose provider id another node holds is refused naming that node and the alias`() {
        whenever(graphStore.findNodeByAlias("Repository", alias)).thenReturn(stored())

        assertThatThrownBy { service.create("Repository", props("https://github.com/other/thing")) }
            .isInstanceOf(NodeExistsException::class.java)
            .hasFieldOrPropertyWithValue("existingId", oldKey.id)
            .hasFieldOrPropertyWithValue("alias", alias)

        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `a create with a provider id nothing holds is written with it`() {
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }

        val created = service.create("Repository", props("acme/payments"))

        assertThat(created.key).isEqualTo(oldKey)
        assertThat(created.props).containsEntry("providerId", "123456").containsEntry("provider", "github")
    }

    @Test
    fun `an update carrying the node's own provider id and a new remote renames it in place`() {
        whenever(graphStore.findNode(oldKey)).thenReturn(stored(previousKeys = listOf("github.com/acme/billing")))
        whenever(graphStore.findNodeByAlias("Repository", alias)).thenReturn(stored())
        whenever(graphStore.renameNode(eq(oldKey), any())).thenAnswer { it.arguments[1] }

        val renamed = service.update("Repository", oldKey.key, props("https://github.com/acme-platform/payments-service"))

        val written = argumentCaptor<GraphNode>()
        verify(graphStore).renameNode(eq(oldKey), written.capture())
        assertThat(written.firstValue.key).isEqualTo(newKey)
        assertThat(written.firstValue.props).containsEntry("org", "acme-platform").containsEntry("providerId", "123456")
        assertThat(written.firstValue.provenance.previousKeys).containsExactly("github.com/acme/billing", "github.com/acme/payments")
        assertThat(written.firstValue.provenance.writtenBy).isEqualTo("dan")
        assertThat(renamed.key).isEqualTo(newKey)
        verify(graphStore, never()).upsertNode(any())
    }

    /** A rename is the one write that moves a node, so it is logged naming both keys (#88). */
    @Test
    fun `a rename is logged naming the key the node left and the key it took`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("${EventLog.LOGGER_PREFIX}.node.renamed") as Logger
        logger.addAppender(appender)
        whenever(graphStore.findNode(oldKey)).thenReturn(stored())
        whenever(graphStore.findNodeByAlias("Repository", alias)).thenReturn(stored())
        whenever(graphStore.renameNode(eq(oldKey), any())).thenAnswer { it.arguments[1] }

        try {
            service.update("Repository", oldKey.key, props("https://github.com/acme-platform/payments-service"))
        } finally {
            logger.detachAppender(appender)
        }

        assertThat(
            appender.list
                .single()
                .keyValuePairs
                .associate { it.key to it.value },
        ).containsEntry("event", "node.renamed")
            .containsEntry("from", oldKey.key)
            .containsEntry("to", newKey.key)
    }

    /** The provider id is consulted first: a writer holding the new remote finds the node by its id. */
    @Test
    fun `an update addressed to a key nothing holds renames the node its provider id resolves to`() {
        whenever(graphStore.findNode(newKey)).thenReturn(null)
        whenever(graphStore.findNodeByAlias("Repository", alias)).thenReturn(stored())
        whenever(graphStore.renameNode(eq(oldKey), any())).thenAnswer { it.arguments[1] }

        val renamed = service.update("Repository", newKey.key, props("https://github.com/acme-platform/payments-service"))

        assertThat(renamed.key).isEqualTo(newKey)
        assertThat(renamed.provenance.previousKeys).containsExactly(oldKey.key)
    }

    @Test
    fun `a rename onto a key another node already holds is refused naming that node`() {
        whenever(graphStore.findNode(oldKey)).thenReturn(stored())
        whenever(graphStore.findNodeByAlias("Repository", alias)).thenReturn(stored())
        whenever(graphStore.findNode(newKey)).thenReturn(stored(newKey, withAlias = false))

        assertThatThrownBy { service.update("Repository", oldKey.key, props("https://github.com/acme-platform/payments-service")) }
            .isInstanceOf(NodeExistsException::class.java)
            .hasFieldOrPropertyWithValue("existingId", newKey.id)

        verify(graphStore, never()).renameNode(any(), any())
    }

    @Test
    fun `an update whose provider id another node holds is refused naming that node`() {
        val other = NodeKey("Repository", "github.com/acme/other")
        whenever(graphStore.findNode(other)).thenReturn(stored(other, withAlias = false))
        whenever(graphStore.findNodeByAlias("Repository", alias)).thenReturn(stored())

        assertThatThrownBy { service.update("Repository", other.key, props("https://github.com/acme/other")) }
            .isInstanceOf(NodeExistsException::class.java)
            .hasFieldOrPropertyWithValue("existingId", oldKey.id)

        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `without a provider id a changed remote is still refused as a change of identity`() {
        whenever(graphStore.findNode(oldKey)).thenReturn(stored(withAlias = false))

        assertThatThrownBy {
            service.update("Repository", oldKey.key, props("https://github.com/acme-platform/payments-service", providerId = null))
        }.isInstanceOf(ImmutableIdentityException::class.java)

        verify(graphStore, never()).renameNode(any(), any())
    }

    /** Claiming an id and moving in one write would let any writer take any node: the id must already be held. */
    @Test
    fun `a provider id the node does not yet hold does not rename it`() {
        whenever(graphStore.findNode(oldKey)).thenReturn(stored(withAlias = false))

        assertThatThrownBy { service.update("Repository", oldKey.key, props("https://github.com/acme-platform/payments-service")) }
            .isInstanceOf(ImmutableIdentityException::class.java)

        verify(graphStore, never()).renameNode(any(), any())
    }

    @Test
    fun `a node without a provider id is given one by an update that keeps its remote`() {
        whenever(graphStore.findNode(oldKey)).thenReturn(stored(withAlias = false))
        whenever(graphStore.upsertNode(any())).thenAnswer { it.arguments[0] }

        val updated = service.update("Repository", oldKey.key, props("https://github.com/acme/payments"))

        assertThat(updated.props).containsEntry("providerId", "123456")
        verify(graphStore, never()).renameNode(any(), any())
    }

    @Test
    fun `a provider id once held is not replaced by another`() {
        whenever(graphStore.findNode(oldKey)).thenReturn(stored())

        assertThatThrownBy { service.update("Repository", oldKey.key, props("https://github.com/acme/payments", providerId = "999")) }
            .isInstanceOf(ImmutableIdentityException::class.java)
            .hasFieldOrPropertyWithValue("fields", listOf("providerId"))

        verify(graphStore, never()).upsertNode(any())
    }

    @Test
    fun `an update of a node that is not there and that no provider id resolves to is not found`() {
        assertThatThrownBy { service.update("Repository", newKey.key, props("https://github.com/acme-platform/payments-service")) }
            .isInstanceOf(NodeNotFoundException::class.java)
    }
}
