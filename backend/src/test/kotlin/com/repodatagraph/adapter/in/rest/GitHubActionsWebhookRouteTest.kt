package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.out.github.GitHubWebhookVerifier
import com.repodatagraph.adapter.out.githubactions.GitHubActionsConnector
import com.repodatagraph.adapter.out.githubactions.GitHubDeployments
import com.repodatagraph.application.connector.AdapterRegistry
import com.repodatagraph.application.connector.RegisteredConnector
import com.repodatagraph.application.connector.SyncService
import com.repodatagraph.application.connector.WebhookSignatureVerifier
import com.repodatagraph.config.ConnectorSettings
import com.repodatagraph.config.ConnectorsProperties
import com.repodatagraph.config.IngestProperties
import com.repodatagraph.observability.SyncMetrics
import com.repodatagraph.observability.WebhookResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock

/**
 * `POST /api/v1/webhooks/github-actions` (#90): the route takes two senders, and each is believed
 * only by its own credential. GitHub signs a `workflow_run` or `deployment_status` event with the
 * connector's webhook secret, as it signs every event; the deploy pipeline sends its report with the
 * ingest token (#7). A request carrying GitHub's signature header is judged by the signature alone,
 * so the ingest token cannot carry an event GitHub did not sign, and the GitHub connector's secret
 * signs nothing here.
 */
@WebMvcTest(WebhookController::class)
class GitHubActionsWebhookRouteTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var registry: AdapterRegistry

    @MockitoBean
    private lateinit var syncService: SyncService

    @MockitoBean
    private lateinit var metrics: SyncMetrics

    private val signatures = WebhookSignatureVerifier()
    private val body = """{"action":"completed","workflow_run":{"id":4711}}"""

    @BeforeEach
    fun register() {
        val verifier =
            GitHubWebhookVerifier(
                signatures,
                ConnectorsProperties(
                    mapOf(
                        "github-actions" to ConnectorSettings(webhookSecret = ACTIONS_SECRET),
                        "github" to ConnectorSettings(webhookSecret = GITHUB_SECRET),
                    ),
                ),
            )
        val connector =
            GitHubActionsConnector(IngestProperties(INGEST_TOKEN), mock(), mock(), mock<GitHubDeployments>(), verifier, Clock.systemUTC())
        whenever(registry.find("github-actions")).thenReturn(RegisteredConnector(connector, enabled = false))
        whenever(syncService.applyWebhook(any(), any())).thenReturn("run-1")
    }

    @Test
    fun `a GitHub event signed with the connector's secret is applied`() {
        deliver(signedWith = ACTIONS_SECRET).andExpect(status().isAccepted)

        verify(syncService).applyWebhook(eq("github-actions"), any())
    }

    @Test
    fun `a GitHub event signed with another connector's secret is refused`() {
        deliver(signedWith = GITHUB_SECRET).andExpect(status().isUnauthorized)

        verify(metrics).webhook("github-actions", WebhookResult.REJECTED)
        verify(syncService, never()).applyWebhook(any(), any())
    }

    @Test
    fun `the ingest token cannot carry an event whose GitHub signature does not verify`() {
        mockMvc
            .perform(
                post(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer $INGEST_TOKEN")
                    .header("X-GitHub-Event", "workflow_run")
                    .header("X-Hub-Signature-256", "sha256=" + signatures.sign(body.toByteArray(), "guess"))
                    .content(body),
            ).andExpect(status().isUnauthorized)

        verify(syncService, never()).applyWebhook(any(), any())
    }

    @Test
    fun `a deployment report with the ingest token is still accepted on the same route`() {
        mockMvc
            .perform(
                post(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer $INGEST_TOKEN")
                    .content("""{"repository":"github.com/acme/payments"}"""),
            ).andExpect(status().isAccepted)
    }

    @Test
    fun `an unsigned GitHub event is refused`() {
        mockMvc
            .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-GitHub-Event", "workflow_run").content(body))
            .andExpect(status().isUnauthorized)
    }

    private fun deliver(signedWith: String) =
        mockMvc.perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", "workflow_run")
                .header("X-GitHub-Delivery", "d-1")
                .header("X-Hub-Signature-256", "sha256=" + signatures.sign(body.toByteArray(), signedWith))
                .content(body),
        )

    private companion object {
        const val PATH = "/api/v1/webhooks/github-actions"
        const val ACTIONS_SECRET = "actions-secret"
        const val GITHUB_SECRET = "github-secret"
        const val INGEST_TOKEN = "ingest-token"
    }
}
