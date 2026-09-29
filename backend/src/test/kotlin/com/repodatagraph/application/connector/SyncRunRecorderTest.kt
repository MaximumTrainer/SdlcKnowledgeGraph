package com.repodatagraph.application.connector

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * When a connector last succeeded, as the graph remembers it (#29, FR1). It seeds the freshness gauge
 * after a restart, so it has to survive the same round trip through Neo4j as the watermark does.
 */
class SyncRunRecorderTest {
    private val store: GraphStore = mock()
    private val recorder = SyncRunRecorder(store, Clock.systemUTC())
    private val finished = Instant.parse("2026-09-29T11:00:00Z")

    private fun state(vararg props: Pair<String, Any?>) =
        whenever(store.findNode(NodeKey("ConnectorState", "fake")))
            .thenReturn(GraphNode(NodeKey("ConnectorState", "fake"), mapOf(*props), Provenance.manual()))

    @Test
    fun `reads the last success as Neo4j hands it back`() {
        state("lastFinishedAt" to ZonedDateTime.ofInstant(finished, ZoneOffset.UTC))

        assertThat(recorder.lastSuccessAt("fake")).isEqualTo(finished)
    }

    @Test
    fun `is null for a connector that has never succeeded`() {
        assertThat(recorder.lastSuccessAt("fake")).isNull()
    }
}
