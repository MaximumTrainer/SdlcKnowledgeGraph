package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.SyncRun
import com.repodatagraph.domain.model.SyncRunPage
import com.repodatagraph.domain.model.SyncRunQuery
import com.repodatagraph.domain.port.out.SyncRunStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
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
 * The sync run history over HTTP (#29, FR5): a page of summaries newest first with how many there are
 * in all, one run in full, and a 400 for a filter that cannot mean anything - before the store is
 * asked, so a malformed request costs nothing.
 */
@WebMvcTest(SyncRunController::class)
class SyncRunControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var store: SyncRunStore

    private val started = Instant.parse("2026-09-29T09:00:00Z")

    private fun run(
        id: String = "run-1",
        error: String? = null,
        finishedAt: Instant? = started.plusSeconds(90),
        status: String = "SUCCESS",
    ) = SyncRun(
        id = id,
        connector = "fake",
        sourceSystem = "fake",
        mode = "FULL",
        status = status,
        startedAt = started,
        finishedAt = finishedAt,
        nodesUpserted = 3,
        edgesUpserted = 2,
        tombstones = 1,
        watermark = Instant.parse("2026-09-01T10:00:00Z"),
        sourceId = null,
        error = error,
    )

    @Test
    fun `lists a page of run summaries with the total`() {
        whenever(store.find(SyncRunQuery(page = 0, size = 20))).thenReturn(SyncRunPage(listOf(run()), totalElements = 41))

        mockMvc
            .perform(get("/api/v1/sync-runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value("run-1"))
            .andExpect(jsonPath("$.items[0].connector").value("fake"))
            .andExpect(jsonPath("$.items[0].sourceSystem").value("fake"))
            .andExpect(jsonPath("$.items[0].mode").value("FULL"))
            .andExpect(jsonPath("$.items[0].status").value("SUCCESS"))
            .andExpect(jsonPath("$.items[0].startedAt").value("2026-09-29T09:00:00Z"))
            .andExpect(jsonPath("$.items[0].finishedAt").value("2026-09-29T09:01:30Z"))
            .andExpect(jsonPath("$.items[0].durationMs").value(90_000))
            .andExpect(jsonPath("$.items[0].nodesUpserted").value(3))
            .andExpect(jsonPath("$.items[0].edgesUpserted").value(2))
            .andExpect(jsonPath("$.items[0].tombstones").value(1))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(41))
            .andExpect(jsonPath("$.totalPages").value(3))
    }

    @Test
    fun `passes every filter to the store`() {
        val query =
            SyncRunQuery(
                connector = "fake",
                status = "PARTIAL",
                startedFrom = Instant.parse("2026-09-01T00:00:00Z"),
                startedBefore = Instant.parse("2026-09-30T00:00:00Z"),
                page = 1,
                size = 2,
            )
        whenever(store.find(query)).thenReturn(SyncRunPage(emptyList(), totalElements = 2))

        mockMvc
            .perform(
                get("/api/v1/sync-runs")
                    .param("connector", "fake")
                    // Case does not matter; the store is always asked in the stored spelling.
                    .param("status", "partial")
                    .param("from", "2026-09-01T00:00:00Z")
                    .param("to", "2026-09-30T00:00:00Z")
                    .param("page", "1")
                    .param("size", "2"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.totalElements").value(2))

        verify(store).find(query)
    }

    @Test
    fun `a summary cuts a long error short and a running run has no duration`() {
        whenever(store.find(any())).thenReturn(
            SyncRunPage(listOf(run(error = "e".repeat(500), finishedAt = null, status = "RUNNING")), totalElements = 1),
        )

        mockMvc
            .perform(get("/api/v1/sync-runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].error").value("e".repeat(199) + "…"))
            .andExpect(jsonPath("$.items[0].finishedAt").isEmpty)
            .andExpect(jsonPath("$.items[0].durationMs").isEmpty)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "status=SOMETIMES",
            "size=101",
            "size=0",
            "page=-1",
            "from=yesterday",
            "to=2026-13-01",
            "from=2026-09-02T00:00:00Z&to=2026-09-01T00:00:00Z",
        ],
    )
    fun `a filter that cannot mean anything is refused before the store is asked`(query: String) {
        mockMvc
            .perform(get("/api/v1/sync-runs?$query"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid request"))

        verifyNoInteractions(store)
    }

    @Test
    fun `one run in full, with its whole error and its details`() {
        whenever(store.findById("run-1")).thenReturn(run(error = "e".repeat(500)))

        mockMvc
            .perform(get("/api/v1/sync-runs/run-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("run-1"))
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.durationMs").value(90_000))
            .andExpect(jsonPath("$.watermark").value("2026-09-01T10:00:00Z"))
            .andExpect(jsonPath("$.error").value("e".repeat(500)))
            // Present and empty: no connector records per-run details yet.
            .andExpect(jsonPath("$.details").exists())
            .andExpect(jsonPath("$.details").isEmpty)
    }

    @Test
    fun `an unknown run is not found`() {
        mockMvc
            .perform(get("/api/v1/sync-runs/no-such-run"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("sync run not found"))
            .andExpect(jsonPath("$.id").value("no-such-run"))
    }
}

/** Reading the history is a read, so a read-only deployment (#48, D5) still serves it. */
@WebMvcTest(SyncRunController::class, properties = ["sdlc.read-only=true"])
class ReadOnlySyncRunControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var store: SyncRunStore

    @Test
    fun `the list is still served`() {
        whenever(store.find(any())).thenReturn(SyncRunPage(emptyList(), totalElements = 0))

        mockMvc
            .perform(get("/api/v1/sync-runs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(0))
    }
}
