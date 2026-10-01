package com.repodatagraph.application.connector

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.domain.port.out.connector.SyncMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * A SyncRun records what it wrote, left unchanged and could not read, and which version of its
 * connector ran (#86, FR-6), so a run's record can be read against the code that produced it.
 */
class SyncRunRecorderAccountingTest {
    private val store: GraphStore = mock()
    private val recorder = SyncRunRecorder(store, Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC))

    @Test
    fun `a run records its counts and the version of the connector that ran it`() {
        val connector: SourceConnector = mock()
        whenever(connector.descriptor()).thenReturn(
            ConnectorDescriptor("github", "github", emptySet(), emptySet(), setOf(Capability.FULL), version = "2.0.0"),
        )

        recorder.recordRun(
            "run-1",
            RegisteredConnector(connector, enabled = true),
            SyncMode.FULL,
            RunStatus.PARTIAL,
            DeltaResult(nodesUpserted = 4, edgesUpserted = 3, written = 2, unchanged = 5, failed = 1),
            null,
            "could not read 1 repositories",
        )

        val node = argumentCaptor<GraphNode>()
        verify(store).upsertNode(node.capture())
        assertThat(node.firstValue.props)
            .containsEntry("written", 2)
            .containsEntry("unchanged", 5)
            .containsEntry("failed", 1)
            .containsEntry("connectorVersion", "2.0.0")
            .containsEntry("nodesUpserted", 4)
    }
}
