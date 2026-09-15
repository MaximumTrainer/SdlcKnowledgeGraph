package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.support.Neo4jTestcontainersConfig
import com.repodatagraph.support.connector.FakeConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID

/**
 * Recognising a redelivery is what stops one event being applied twice, and it is answered from the
 * graph rather than from memory - so it has to be proved against a real Neo4j. A unit test with an
 * in-memory store would prove the intention and not the query.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class SyncRunRecorderIT {
    @Autowired
    private lateinit var recorder: SyncRunRecorder

    private val connector =
        RegisteredConnector(
            connector = FakeConnector(name = "recorder-it"),
            enabled = true,
        )

    @Test
    fun `finds the run a delivery already produced`() {
        val delivery = UUID.randomUUID().toString()
        val runId = UUID.randomUUID().toString()

        recorder.recordRun(
            runId = runId,
            registered = connector,
            mode = SyncMode.WEBHOOK,
            status = RunStatus.SUCCESS,
            totals = DeltaResult(),
            watermark = null,
            error = null,
            sourceId = delivery,
        )

        assertThat(recorder.runForDelivery(connector.name, delivery)).isEqualTo(runId)
    }

    @Test
    fun `finds nothing for a delivery it has not seen`() {
        assertThat(recorder.runForDelivery(connector.name, UUID.randomUUID().toString())).isNull()
    }

    @Test
    fun `does not confuse two connectors sending the same delivery id`() {
        val delivery = UUID.randomUUID().toString()
        val runId = UUID.randomUUID().toString()

        recorder.recordRun(
            runId = runId,
            registered = connector,
            mode = SyncMode.WEBHOOK,
            status = RunStatus.SUCCESS,
            totals = DeltaResult(),
            watermark = null,
            error = null,
            sourceId = delivery,
        )

        // Delivery ids are the provider's, not ours. Two providers can number an event the same way.
        assertThat(recorder.runForDelivery("somebody-else", delivery)).isNull()
    }
}
