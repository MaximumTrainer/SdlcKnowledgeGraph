package com.repodatagraph.adapter.out.github

import com.repodatagraph.application.connector.WebhookSignatureVerifier
import com.repodatagraph.config.ConnectorsProperties
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import org.springframework.stereotype.Component

/**
 * Whether an event really came from GitHub, and which delivery it is.
 *
 * Separate from [GitHubWebhookHandler] because the two answer different questions and only one of
 * them is allowed to be wrong in interesting ways. This one decides whether anything happens at all;
 * keeping it apart is what makes "nothing unverified reaches the connector" something that can be
 * read in one place rather than inferred from the order of statements in a larger method.
 */
@Component
class GitHubWebhookVerifier(
    private val signatures: WebhookSignatureVerifier,
    private val connectors: ConnectorsProperties,
) {
    /** `X-Hub-Signature-256: sha256=<hex>`, compared in constant time. */
    fun verify(
        headers: Map<String, String>,
        body: ByteArray,
    ): Boolean =
        headers
            .header(SIGNATURE_HEADER)
            // A signature sent under a scheme this code does not implement must fail, not be read as
            // though it were the one it does.
            ?.takeIf { it.startsWith(SIGNATURE_PREFIX) }
            ?.let { signatures.verify(body, secret(), it.removePrefix(SIGNATURE_PREFIX)) }
            ?: false

    /** GitHub's own id for the delivery, which is what makes a redelivery recognisable. */
    fun deliveryId(event: WebhookEvent): String? = event.headers.header(DELIVERY_HEADER)

    /** Which kind of event this is, as GitHub names it. */
    fun eventType(event: WebhookEvent): String? = event.headers.header(EVENT_HEADER)

    private fun secret() = connectors.settingsFor(CONNECTOR).webhookSecret

    private companion object {
        const val CONNECTOR = "github"
        const val SIGNATURE_HEADER = "X-Hub-Signature-256"
        const val SIGNATURE_PREFIX = "sha256="
        const val DELIVERY_HEADER = "X-GitHub-Delivery"
        const val EVENT_HEADER = "X-GitHub-Event"
    }
}

/** Header names arrive in whatever case the sender used, and HTTP does not care which. */
internal fun Map<String, String>.header(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
