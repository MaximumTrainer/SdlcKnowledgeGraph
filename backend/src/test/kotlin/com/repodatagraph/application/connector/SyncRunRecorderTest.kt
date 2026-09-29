package com.repodatagraph.application.connector

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * What the graph remembers about a connector between runs (#29, FR1 and FR3). When it last succeeded
 * seeds the freshness gauge after a restart, so it has to survive the same round trip through Neo4j
 * as the watermark does; and every finished run is written down, so failures are counted rather than
 * invisible, while the watermark and the last success only ever move on a success.
 */
class SyncRunRecorderTest {
    private val store: GraphStore = mock()
    private val now = Instant.parse("2026-09-29T12:00:00Z")
    private val recorder = SyncRunRecorder(store, Clock.fixed(now, ZoneOffset.UTC))
    private val finished = Instant.parse("2026-09-29T11:00:00Z")
    private val watermark = Instant.parse("2026-09-01T10:00:00Z")

    private fun state(vararg props: Pair<String, Any?>) =
        whenever(store.findNode(NodeKey("ConnectorState", "fake")))
            .thenReturn(GraphNode(NodeKey("ConnectorState", "fake"), mapOf(*props), Provenance.manual()))

    @Test
    fun `reads the last success as Neo4j hands it back`() {
        state("lastSuccessAt" to ZonedDateTime.ofInstant(finished, ZoneOffset.UTC))

        assertThat(recorder.lastSuccessAt("fake")).isEqualTo(finished)
    }

    @Test
    fun `is null for a connector that has never succeeded`() {
        assertThat(recorder.lastSuccessAt("fake")).isNull()
    }

    @Test
    fun `reads a state written before lastSuccessAt existed from its last finished success`() {
        state("lastStatus" to "SUCCESS", "lastFinishedAt" to ZonedDateTime.ofInstant(finished, ZoneOffset.UTC))

        assertThat(recorder.lastSuccessAt("fake")).isEqualTo(finished)
    }

    @Test
    fun `does not mistake a finished failure for a success`() {
        state("lastStatus" to "FAILED", "lastFinishedAt" to ZonedDateTime.ofInstant(finished, ZoneOffset.UTC))

        assertThat(recorder.lastSuccessAt("fake")).isNull()
    }

    @Test
    fun `a success moves the watermark and the last success, and clears the failures`() {
        state("watermark" to finished, "lastSuccessAt" to finished, "consecutiveFailures" to 3L)

        recorder.recordState("fake", "run-1", RunStatus.SUCCESS, watermark)

        assertThat(written())
            .containsEntry("lastRunId", "run-1")
            .containsEntry("lastRunStatus", "SUCCESS")
            .containsEntry("lastStatus", "SUCCESS")
            .containsEntry("lastFinishedAt", now)
            .containsEntry("lastSuccessAt", now)
            .containsEntry("watermark", watermark)
            .containsEntry("consecutiveFailures", 0)
    }

    @Test
    fun `a partial run is a failure and moves neither the watermark nor the last success`() {
        state("watermark" to watermark, "lastSuccessAt" to finished, "consecutiveFailures" to 1L)

        recorder.recordState("fake", "run-2", RunStatus.PARTIAL, Instant.parse("2026-09-29T11:30:00Z"))

        assertThat(written())
            .containsEntry("lastRunStatus", "PARTIAL")
            .containsEntry("lastFinishedAt", now)
            .containsEntry("lastSuccessAt", finished)
            .containsEntry("watermark", watermark)
            .containsEntry("consecutiveFailures", 2)
    }

    @Test
    fun `a first failure counts one and records no success`() {
        recorder.recordState("fake", "run-1", RunStatus.FAILED, null)

        assertThat(written())
            .containsEntry("lastRunStatus", "FAILED")
            .containsEntry("consecutiveFailures", 1)
            .doesNotContainKey("lastSuccessAt")
            .doesNotContainKey("watermark")
    }

    @Test
    fun `a failure after a state written before lastSuccessAt existed keeps that success`() {
        state("lastStatus" to "SUCCESS", "lastFinishedAt" to finished, "watermark" to watermark)

        recorder.recordState("fake", "run-2", RunStatus.FAILED, null)

        assertThat(written())
            .containsEntry("lastSuccessAt", finished)
            .containsEntry("lastFinishedAt", now)
            .containsEntry("consecutiveFailures", 1)
    }

    private fun written(): Map<String, Any?> {
        val captor = argumentCaptor<GraphNode>()
        verify(store).upsertNode(captor.capture())
        return captor.firstValue.props
    }
}
