package com.repodatagraph.application.connector

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.lifecycle.MissingFromFullSync
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.lifecycle.TombstoneRules
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.FactLifecycle
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * How a connector's facts are retired (#33, FR3-FR4): a tombstone retires the node with the reason
 * the source gave, and what a full sync stopped reporting is retired by the connector's own rules.
 */
class GraphDeltaWriterLifecycleTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val runStarted = Instant.parse("2026-09-30T11:00:00Z")
    private val graphStore: GraphStore = mock()
    private val factLifecycle: FactLifecycle = mock()
    private val writer =
        GraphDeltaWriter(
            graphStore,
            factLifecycle,
            IdentityResolver(),
            DerivedProperties(GitRemoteParser()),
            Clock.fixed(now, ZoneOffset.UTC),
            YamlOntologyLoader(DefaultResourceLoader()).load(),
        )
    private val itsm = ConnectorDescriptor("itsm", "servicenow", setOf("Team"), emptySet(), setOf(Capability.FULL))

    @Test
    fun `a tombstone retires the node, and its current edges, as deleted at the source`() {
        val key = NodeKey("Repository", "github.com/acme/legacy")
        whenever(factLifecycle.retire(key, now, RetiredReason.SOURCE_DELETED)).thenReturn(true)

        val result = writer.apply(GraphDelta(tombstones = listOf(key)), itsm, "run-1")

        verify(factLifecycle).retire(key, now, RetiredReason.SOURCE_DELETED)
        assertThat(result.tombstones).isEqualTo(1)
    }

    @Test
    fun `a tombstone for a node the graph never held retires nothing`() {
        val key = NodeKey("Repository", "github.com/acme/never")
        whenever(factLifecycle.retire(any(), any(), any())).thenReturn(false)

        assertThat(writer.apply(GraphDelta(tombstones = listOf(key)), itsm, "run-1").tombstones).isZero()
    }

    @Test
    fun `reconciling with no rules retires what was stated before the run began`() {
        whenever(factLifecycle.closeNodesNotReasserted("servicenow", runStarted, now, RetiredReason.MISSING_FROM_SYNC)).thenReturn(2)

        assertThat(writer.reconcile(itsm, runStarted)).isEqualTo(2)
    }

    @Test
    fun `a grace period spares what the source stated within it`() {
        val rules = TombstoneRules(gracePeriod = Duration.ofDays(7))

        writer.reconcile(itsm, runStarted, rules)

        verify(factLifecycle).closeNodesNotReasserted(
            "servicenow",
            runStarted.minus(Duration.ofDays(7)),
            now,
            RetiredReason.MISSING_FROM_SYNC,
        )
    }

    @Test
    fun `a connector that ignores what it stops reporting retires nothing`() {
        val rules = TombstoneRules(missingFromFullSync = MissingFromFullSync.IGNORE)

        assertThat(writer.reconcile(itsm, runStarted, rules)).isZero()
        verifyNoInteractions(factLifecycle)
        verify(graphStore, never()).upsertNode(any())
    }
}
