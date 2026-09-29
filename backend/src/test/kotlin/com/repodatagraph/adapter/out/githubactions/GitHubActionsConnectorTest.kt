package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.config.IngestProperties
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * The deploy pipeline as a source system (#7): it pushes and is never polled, and what it pushes is
 * believed only with the bearer token this instance was given.
 */
class GitHubActionsConnectorTest {
    private fun connector(token: String = "the-token") = GitHubActionsConnector(IngestProperties(token), mock(), mock())

    private val body = """{"repository":"github.com/acme/api"}""".toByteArray()

    @Test
    fun `is the github-actions source, taking webhooks only`() {
        val descriptor = connector().descriptor()

        assertThat(descriptor.name).isEqualTo("github-actions")
        assertThat(descriptor.sourceSystem).isEqualTo("github-actions")
        assertThat(descriptor.capabilities).containsExactly(Capability.WEBHOOK)
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
}
