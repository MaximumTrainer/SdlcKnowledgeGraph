package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.SyncRun
import com.repodatagraph.domain.model.SyncRunPage
import com.repodatagraph.domain.port.out.SyncRunStore
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * What a run wrote, left as it was and could not read, and which version of the connector ran it
 * (#86, FR-6), as the run history serves them. Additive: a client reading only the counts it knew
 * before keeps working, and a run recorded before these existed reads them as null rather than 0 - a
 * zero would claim the run wrote nothing, which nobody counted.
 */
@WebMvcTest(SyncRunController::class)
class SyncRunAccountingControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var store: SyncRunStore

    private val started = Instant.parse("2026-09-29T09:00:00Z")

    private fun run(
        written: Int? = 0,
        unchanged: Int? = 17,
        failed: Int? = 1,
        connectorVersion: String? = "2.1.0",
    ) = SyncRun(
        id = "run-1",
        connector = "github",
        sourceSystem = "github",
        mode = "FULL",
        status = "PARTIAL",
        startedAt = started,
        finishedAt = started.plusSeconds(90),
        nodesUpserted = 9,
        edgesUpserted = 8,
        tombstones = 0,
        watermark = null,
        sourceId = null,
        error = "could not read 1 repositories",
        written = written,
        unchanged = unchanged,
        failed = failed,
        connectorVersion = connectorVersion,
    )

    @Test
    fun `one run says what it wrote, left unchanged and could not read, and which connector version ran`() {
        whenever(store.findById("run-1")).thenReturn(run())

        mockMvc
            .perform(get("/api/v1/sync-runs/run-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.written").value(0))
            .andExpect(jsonPath("$.unchanged").value(17))
            .andExpect(jsonPath("$.failed").value(1))
            .andExpect(jsonPath("$.connectorVersion").value("2.1.0"))
    }

    @Test
    fun `the list carries the same counts`() {
        whenever(store.find(any())).thenReturn(SyncRunPage(listOf(run()), totalElements = 1))

        mockMvc
            .perform(get("/api/v1/sync-runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].written").value(0))
            .andExpect(jsonPath("$.items[0].unchanged").value(17))
            .andExpect(jsonPath("$.items[0].failed").value(1))
            .andExpect(jsonPath("$.items[0].connectorVersion").value("2.1.0"))
    }

    @Test
    fun `a run recorded before these were counted reads them as unknown, not as zero`() {
        whenever(store.findById("run-1")).thenReturn(run(written = null, unchanged = null, failed = null, connectorVersion = null))

        mockMvc
            .perform(get("/api/v1/sync-runs/run-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.written").isEmpty)
            .andExpect(jsonPath("$.unchanged").isEmpty)
            .andExpect(jsonPath("$.failed").isEmpty)
            .andExpect(jsonPath("$.connectorVersion").isEmpty)
    }
}
