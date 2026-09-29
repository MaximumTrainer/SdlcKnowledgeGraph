package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.application.connector.AdapterRegistry
import com.repodatagraph.application.connector.RegisteredConnector
import com.repodatagraph.application.connector.SyncService
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.observability.SyncMetrics
import com.repodatagraph.observability.WebhookResult
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * A webhook refused for its signature is counted where it is refused (#29, FR1), because it never
 * reaches the sync service: `sdlc_webhook_events_total{result="rejected"}` is how an operator sees a
 * misconfigured secret, or someone guessing at one.
 */
@WebMvcTest(WebhookController::class)
class WebhookControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var registry: AdapterRegistry

    @MockitoBean
    private lateinit var syncService: SyncService

    @MockitoBean
    private lateinit var metrics: SyncMetrics

    private val connector: SourceConnector = mock()

    /** A connector that takes webhooks and whose signature check says [verified]. */
    private fun registered(verified: Boolean) {
        whenever(connector.descriptor()).thenReturn(
            ConnectorDescriptor(
                name = "fake",
                sourceSystem = "fake",
                nodeTypes = emptySet(),
                edgeTypes = emptySet(),
                capabilities = setOf(Capability.WEBHOOK),
            ),
        )
        whenever(connector.verifyWebhook(any(), any())).thenReturn(verified)
        whenever(registry.find("fake")).thenReturn(RegisteredConnector(connector, enabled = true))
    }

    private fun deliver() = mockMvc.perform(post("/api/v1/webhooks/fake").content("{}"))

    @Test
    fun `a bad signature is refused and counted as rejected`() {
        registered(verified = false)

        deliver().andExpect(status().isUnauthorized)

        verify(metrics).webhook("fake", WebhookResult.REJECTED)
        verify(syncService, never()).applyWebhook(any(), any())
    }

    @Test
    fun `a good signature is left for the sync service to count`() {
        registered(verified = true)
        whenever(syncService.applyWebhook(any(), any())).thenReturn("run-1")

        deliver().andExpect(status().isAccepted)

        verify(metrics, never()).webhook(any(), any())
    }
}
