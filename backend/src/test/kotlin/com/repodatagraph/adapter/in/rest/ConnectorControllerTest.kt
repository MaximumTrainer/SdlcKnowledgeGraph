package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.application.connector.AdapterRegistry
import com.repodatagraph.application.connector.ConnectorFreshness
import com.repodatagraph.application.connector.FreshnessCalculator
import com.repodatagraph.application.connector.RegisteredConnector
import com.repodatagraph.application.connector.SyncService
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.HealthStatus
import com.repodatagraph.domain.port.out.connector.SourceConnector
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.scheduling.TaskScheduler
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant

/**
 * The freshness block on the connectors API (#29, FR3): when a connector last succeeded, how long ago
 * that was against its threshold, and whether that makes it stale. Additive, so a client that reads
 * only the fields it knew about before keeps working.
 */
@WebMvcTest(ConnectorController::class)
class ConnectorControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var registry: AdapterRegistry

    @MockitoBean
    private lateinit var syncService: SyncService

    @MockitoBean
    private lateinit var graphStore: GraphStore

    // The controller needs one to exist; nothing here asks for a sync, so nothing is scheduled on it.
    @Suppress("UnusedPrivateProperty")
    @MockitoBean
    private lateinit var taskScheduler: TaskScheduler

    @MockitoBean
    private lateinit var freshness: FreshnessCalculator

    private val connector: SourceConnector = mock()
    private val succeeded = Instant.parse("2026-09-29T09:00:00Z")

    private fun registered(): RegisteredConnector {
        whenever(connector.descriptor()).thenReturn(
            ConnectorDescriptor(
                name = "fake",
                sourceSystem = "fake",
                nodeTypes = emptySet(),
                edgeTypes = emptySet(),
                capabilities = setOf(Capability.FULL),
            ),
        )
        whenever(connector.healthCheck()).thenReturn(HealthStatus.up("scripted"))
        val registered = RegisteredConnector(connector, enabled = true)
        whenever(registry.all()).thenReturn(listOf(registered))
        whenever(registry.find("fake")).thenReturn(registered)
        whenever(syncService.isRunning("fake")).thenReturn(false)
        return registered
    }

    @Test
    fun `lists each connector with how fresh it is`() {
        val registered = registered()
        whenever(freshness.of(registered)).thenReturn(
            ConnectorFreshness(
                lastSuccessAt = succeeded,
                age = Duration.ofHours(3),
                threshold = Duration.ofHours(1),
                stale = true,
            ),
        )

        mockMvc
            .perform(get("/api/v1/connectors"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].freshness.lastSuccessAt").value("2026-09-29T09:00:00Z"))
            .andExpect(jsonPath("$[0].freshness.ageSeconds").value(10800))
            .andExpect(jsonPath("$[0].freshness.thresholdSeconds").value(3600))
            .andExpect(jsonPath("$[0].freshness.stale").value(true))
    }

    @Test
    fun `a connector that never succeeded has no age rather than a made-up one`() {
        val registered = registered()
        whenever(freshness.of(registered)).thenReturn(
            ConnectorFreshness(lastSuccessAt = null, age = null, threshold = Duration.ofHours(2), stale = false),
        )

        mockMvc
            .perform(get("/api/v1/connectors"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].freshness.lastSuccessAt").isEmpty)
            .andExpect(jsonPath("$[0].freshness.ageSeconds").isEmpty)
            .andExpect(jsonPath("$[0].freshness.thresholdSeconds").value(7200))
            .andExpect(jsonPath("$[0].freshness.stale").value(false))
    }

    @Test
    fun `one connector shows its failure count and the run status it remembers`() {
        val registered = registered()
        whenever(freshness.of(registered)).thenReturn(
            ConnectorFreshness(lastSuccessAt = succeeded, age = Duration.ZERO, threshold = Duration.ofHours(2), stale = false),
        )
        whenever(graphStore.findNode(NodeKey("ConnectorState", "fake"))).thenReturn(
            GraphNode(
                NodeKey("ConnectorState", "fake"),
                mapOf(
                    "connector" to "fake",
                    "lastRunId" to "run-2",
                    "lastStatus" to "FAILED",
                    "lastRunStatus" to "FAILED",
                    "lastFinishedAt" to Instant.parse("2026-09-29T11:00:00Z"),
                    "lastSuccessAt" to succeeded,
                    "consecutiveFailures" to 2L,
                ),
                Provenance.manual(),
            ),
        )

        mockMvc
            .perform(get("/api/v1/connectors/fake"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state.lastRunStatus").value("FAILED"))
            .andExpect(jsonPath("$.state.lastSuccessAt").value("2026-09-29T09:00:00Z"))
            .andExpect(jsonPath("$.state.consecutiveFailures").value(2))
            .andExpect(jsonPath("$.freshness.stale").value(false))
    }
}
