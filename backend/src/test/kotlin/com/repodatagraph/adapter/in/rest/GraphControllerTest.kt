package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The shape `/servicenow` sends, pinned.
 *
 * This endpoint used to return a hand-written `ServiceNowCI` class; it now reads a
 * `ConfigurationItem` out of the generic store, which the registry declares and the ServiceNow
 * connector writes (#24). Nothing a caller sees was supposed to change, and this is what says so - a
 * controller test rather than a pact because the frontend does not consume this endpoint, so there is
 * no consumer contract to verify against. The protection needed is against *this* code changing the
 * payload, which is exactly what it asserts.
 */
@WebMvcTest(GraphController::class)
class GraphControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var graphQueryUseCase: GraphQueryUseCase

    private val configurationItem =
        GraphNode(
            key = NodeKey("ConfigurationItem", "servicenow:sn.example.test:a1"),
            props =
                mapOf(
                    "sourceSystem" to "servicenow",
                    "instance" to "sn.example.test",
                    "sysId" to "a1",
                    "ciName" to "payments-api",
                    "ciClass" to "cmdb_ci_app",
                    "serviceId" to "SVC-1",
                    // Everything the registry grew in #24, none of which this endpoint sends.
                    "businessCriticality" to "1 - most critical",
                    "supportGroup" to "Payments On Call",
                ),
            provenance =
                Provenance(
                    sourceSystem = "servicenow",
                    ingestedAt = Instant.parse("2026-09-01T10:00:00Z"),
                    validFrom = Instant.parse("2026-09-01T10:00:00Z"),
                ),
        )

    @Test
    fun `sends the four fields it has always sent, and nothing else`() {
        whenever(graphQueryUseCase.getConfigurationItemForRepo("R1")).thenReturn(configurationItem)

        mockMvc
            .perform(get("/api/v1/graph/repositories/R1/servicenow"))
            .andExpect(status().isOk)
            // Exact, not "contains": a field appearing is as much of a change to a consumer as one
            // disappearing, and the richer view is at /api/v1/nodes/ConfigurationItem/{key}.
            .andExpect(
                content().json(
                    """
                    {
                      "id": "ConfigurationItem:servicenow:sn.example.test:a1",
                      "ciName": "payments-api",
                      "serviceId": "SVC-1",
                      "repoId": "R1"
                    }
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                ),
            )
    }

    @Test
    fun `sends an empty serviceId rather than omitting it`() {
        whenever(graphQueryUseCase.getConfigurationItemForRepo("R1"))
            .thenReturn(configurationItem.copy(props = configurationItem.props - "serviceId"))

        // What this endpoint has always done. A caller checking for a missing field would start
        // seeing one if the property were simply dropped.
        mockMvc
            .perform(get("/api/v1/graph/repositories/R1/servicenow"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.serviceId").value(""))
    }

    @Test
    fun `answers 404 for a repository with no configuration item`() {
        whenever(graphQueryUseCase.getConfigurationItemForRepo("R1")).thenReturn(null)

        mockMvc
            .perform(get("/api/v1/graph/repositories/R1/servicenow"))
            .andExpect(status().isNotFound)
    }
}
