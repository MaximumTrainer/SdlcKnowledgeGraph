package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.adapter.out.github.GitHubWebhookVerifier
import com.repodatagraph.application.connector.WebhookSignatureVerifier
import com.repodatagraph.config.ConnectorSettings
import com.repodatagraph.config.ConnectorsProperties
import com.repodatagraph.config.IngestProperties
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.Health
import com.repodatagraph.domain.port.out.connector.SyncRequest
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * GitHub Actions as a source system (#7, #90): the deploy pipeline pushes its own report with the
 * ingest token, and the connector also reads workflow runs, packages and deployments from GitHub - on
 * a schedule from a cursor, and when GitHub's signed events say a run or a deployment has moved.
 */
class GitHubActionsConnectorTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val signatures = WebhookSignatureVerifier()
    private val deployments: GitHubDeployments = mock()
    private val verifier =
        GitHubWebhookVerifier(signatures, ConnectorsProperties(mapOf("github-actions" to ConnectorSettings(webhookSecret = SECRET))))

    private fun connector(token: String = "the-token") =
        GitHubActionsConnector(IngestProperties(token), mock(), mock(), deployments, verifier, Clock.fixed(now, ZoneOffset.UTC))

    private val body = """{"repository":"github.com/acme/api"}""".toByteArray()
    private val event = """{"action":"completed","workflow_run":{"id":4711}}""".toByteArray()

    private fun signed(
        payload: ByteArray = event,
        secret: String = SECRET,
    ) = mapOf(
        "X-GitHub-Event" to "workflow_run",
        "X-GitHub-Delivery" to "delivery-1",
        "X-Hub-Signature-256" to "sha256=" + signatures.sign(payload, secret),
    )

    @Test
    fun `is the github-actions source, polled from a cursor and pushed to, and never complete`() {
        val descriptor = connector().descriptor()

        assertThat(descriptor.name).isEqualTo("github-actions")
        assertThat(descriptor.sourceSystem).isEqualTo("github-actions")
        assertThat(descriptor.capabilities).containsExactlyInAnyOrder(Capability.WEBHOOK, Capability.INCREMENTAL, Capability.FULL)
        // It reads a window of recent runs, never everything there is, so a full run retires nothing.
        assertThat(descriptor.fullSyncIsComplete).isFalse()
        assertThat(descriptor.version).isEqualTo("2.0.0")
        assertThat(descriptor.nodeTypes).contains("Repository", "Pipeline", "Artifact", "Deployment", "Environment")
        assertThat(descriptor.edgeTypes).contains("HAS_PIPELINE", "BUILT_FROM", "DEPLOYED_TO", "TO_ENVIRONMENT")
    }

    @Test
    fun `believes a report carrying the configured bearer token`() {
        assertThat(connector().verifyWebhook(mapOf("authorization" to "Bearer the-token"), body)).isTrue()
    }

    @Test
    fun `refuses a report with the wrong token, a different scheme, or none`() {
        assertThat(connector().verifyWebhook(mapOf("authorization" to "Bearer not-the-token"), body)).isFalse()
        assertThat(connector().verifyWebhook(mapOf("authorization" to "Basic the-token"), body)).isFalse()
        assertThat(connector().verifyWebhook(emptyMap(), body)).isFalse()
    }

    @Test
    fun `refuses everything when no token is configured, rather than accepting an empty one`() {
        assertThat(connector(token = "").verifyWebhook(mapOf("authorization" to "Bearer "), body)).isFalse()
    }

    @Test
    fun `names a delivery by its content, so the same report twice is one delivery`() {
        val first = connector().deliveryId(WebhookEvent("github-actions", emptyMap(), body))
        val again = connector().deliveryId(WebhookEvent("github-actions", mapOf("x" to "y"), body.copyOf()))
        val other = connector().deliveryId(WebhookEvent("github-actions", emptyMap(), "{}".toByteArray()))

        assertThat(first).isNotBlank().isEqualTo(again).isNotEqualTo(other)
    }

    @Test
    fun `believes a GitHub event signed with its own webhook secret, and only that`() {
        assertThat(connector().verifyWebhook(signed(), event)).isTrue()
        assertThat(connector().verifyWebhook(signed(secret = "github-secret"), event)).isFalse()
    }

    @Test
    fun `judges a request carrying GitHub's signature by the signature, whatever token it also carries`() {
        val headers = signed(secret = "guess") + ("authorization" to "Bearer the-token")

        assertThat(connector().verifyWebhook(headers, event)).isFalse()
    }

    @Test
    fun `names a GitHub event by GitHub's delivery id`() {
        assertThat(connector().deliveryId(WebhookEvent("github-actions", signed(), event))).isEqualTo("delivery-1")
    }

    @Test
    fun `hands a GitHub event to the deployment reader rather than the report parser`() {
        val delta = GraphDelta()
        whenever(deployments.handle(any())).thenReturn(delta)

        assertThat(connector().onWebhook(WebhookEvent("github-actions", signed(), event))).isSameAs(delta)
    }

    @Test
    fun `reads from the cursor it was given, with the run's start as the next one`() {
        val since = Instant.parse("2026-09-30T11:00:00Z")
        whenever(deployments.isConfigured()).thenReturn(true)
        whenever(deployments.sync(anyOrNull(), any())).thenReturn(emptySequence())

        connector().sync(SyncRequest(since = since)).toList()

        verify(deployments).sync(since, now)
    }

    @Test
    fun `refuses to sync before GitHub is configured, rather than reporting an empty estate`() {
        whenever(deployments.isConfigured()).thenReturn(false)

        assertThatThrownBy { connector().sync(SyncRequest()).toList() }.hasMessageContaining("connectors.github")
        verify(deployments, never()).sync(anyOrNull(), any())
    }

    @Test
    fun `is up while it can take reports even when it cannot read GitHub, and down when it can do neither`() {
        whenever(deployments.isConfigured()).thenReturn(false)

        assertThat(connector().healthCheck().status).isEqualTo(Health.UP)
        assertThat(connector(token = "").healthCheck().status).isEqualTo(Health.DOWN)
    }

    @Test
    fun `is down when GitHub is configured and does not answer`() {
        whenever(deployments.isConfigured()).thenReturn(true)
        whenever(deployments.isReachable()).thenReturn(false)

        assertThat(connector().healthCheck().status).isEqualTo(Health.DOWN)
    }

    private companion object {
        const val SECRET = "actions-secret"
    }
}
