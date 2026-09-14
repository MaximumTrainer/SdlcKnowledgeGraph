package com.repodatagraph.support.connector

import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.DiscoveryResult
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.HealthStatus
import com.repodatagraph.domain.port.out.connector.SourceConnector
import com.repodatagraph.domain.port.out.connector.SyncRequest
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A connector with no system behind it, scripted from the test.
 *
 * Every later connector issue (#23 to #36) needs to prove the same things about the mechanism -
 * that a run is recorded, that provenance is stamped, that a tombstone closes validity rather than
 * deleting - and none of those are about the system being connected to. Proving them against a real
 * API would make the suite slow and flaky for reasons that have nothing to do with what is being
 * tested, so they are proved here once.
 *
 * It also records what it was *asked*, which is the only way to assert that an incremental run
 * passed the stored watermark rather than quietly doing a full one.
 */
class FakeConnector(
    private val name: String = "fake",
    private val capabilities: Set<Capability> =
        setOf(Capability.FULL, Capability.INCREMENTAL, Capability.WEBHOOK, Capability.DISCOVERY),
    var webhookSecret: String = "fake-secret",
) : SourceConnector {
    /** Pages the next sync will return. A page that throws is how a partial run is scripted. */
    var pages: List<() -> GraphDelta> = emptyList()

    /** What a verified webhook turns into. Null means "nothing to record". */
    var webhookDelta: GraphDelta? = GraphDelta()

    var health: HealthStatus = HealthStatus.up("scripted")

    /** Every request this connector was given, so a test can assert what it was asked for. */
    val requests: MutableList<SyncRequest> = CopyOnWriteArrayList()

    /** Every webhook that got past verification. Empty means verification refused it. */
    val handledWebhooks: MutableList<WebhookEvent> = CopyOnWriteArrayList()

    override fun descriptor() =
        ConnectorDescriptor(
            name = name,
            sourceSystem = name,
            nodeTypes = setOf("Repository", "Team"),
            edgeTypes = setOf("DEPENDS_ON", "OWNED_BY"),
            capabilities = capabilities,
        )

    override fun healthCheck() = health

    override fun discover() = DiscoveryResult(scopes = listOf(name))

    override fun sync(request: SyncRequest): Sequence<GraphDelta> {
        requests += request
        // Lazily, so a page that throws does so while the run is applying pages rather than here -
        // which is what makes a PARTIAL run distinguishable from a FAILED one.
        return pages.asSequence().map { it() }
    }

    override fun onWebhook(event: WebhookEvent): GraphDelta? {
        handledWebhooks += event
        return webhookDelta
    }

    override fun verifyWebhook(
        headers: Map<String, String>,
        body: ByteArray,
    ): Boolean {
        val offered = headers.entries.firstOrNull { it.key.equals(SIGNATURE_HEADER, ignoreCase = true) }?.value
        return offered != null && offered == sign(body, webhookSecret)
    }

    /** Resets the script and the record of what was asked, between scenarios. */
    fun reset() {
        pages = emptyList()
        webhookDelta = GraphDelta()
        health = HealthStatus.up("scripted")
        requests.clear()
        handledWebhooks.clear()
    }

    companion object {
        const val SIGNATURE_HEADER = "X-Fake-Signature"

        /** The same HMAC a caller has to produce, exposed so a test can sign a body correctly. */
        fun sign(
            body: ByteArray,
            secret: String,
        ): String {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(secret.toByteArray(), HMAC_SHA256))
            return mac.doFinal(body).joinToString("") { "%02x".format(it) }
        }

        private const val HMAC_SHA256 = "HmacSHA256"
    }
}
