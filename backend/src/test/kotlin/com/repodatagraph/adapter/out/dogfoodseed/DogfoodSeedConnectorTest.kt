package com.repodatagraph.adapter.out.dogfoodseed

import com.repodatagraph.application.ingest.ParsedSeed
import com.repodatagraph.application.ingest.SeedBatchParser
import com.repodatagraph.config.IngestProperties
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The dogfood seed as a source system (#47, FR10): it pushes one batch at a time, is believed only with
 * the ingest token, and may assert only the few types seeding exists for.
 */
class DogfoodSeedConnectorTest {
    private val parser: SeedBatchParser = mock()

    private fun connector(token: String = "the-token") = DogfoodSeedConnector(IngestProperties(token), parser)

    private val body = """{"nodes":[{"type":"Team","props":{"name":"platform"}}]}""".toByteArray()

    @Test
    fun `is the dogfood-seed source, taking webhooks only`() {
        val descriptor = connector().descriptor()

        assertThat(descriptor.name).isEqualTo("dogfood-seed")
        assertThat(descriptor.sourceSystem).isEqualTo("dogfood-seed")
        assertThat(descriptor.capabilities).containsExactly(Capability.WEBHOOK)
        assertThat(descriptor.nodeTypes).containsExactlyInAnyOrder("Repository", "Team", "Pipeline")
        assertThat(descriptor.edgeTypes).containsExactlyInAnyOrder("OWNED_BY", "HAS_PIPELINE", "DEPENDS_ON")
        assertThat(descriptor.fullSyncIsComplete).isFalse()
    }

    @Test
    fun `believes a batch carrying the configured bearer token, and nothing else`() {
        assertThat(connector().verifyWebhook(mapOf("Authorization" to "Bearer the-token"), body)).isTrue()
        assertThat(connector().verifyWebhook(mapOf("Authorization" to "Bearer wrong"), body)).isFalse()
        assertThat(connector().verifyWebhook(emptyMap(), body)).isFalse()
        assertThat(connector(token = "").verifyWebhook(mapOf("Authorization" to "Bearer "), body)).isFalse()
    }

    @Test
    fun `turns a valid batch into its delta and an invalid one into nothing`() {
        val delta = GraphDelta(nodes = listOf(NodeUpsert("Team", mapOf("name" to "platform"))))
        whenever(parser.parse(any())).thenReturn(ParsedSeed.Valid(delta), ParsedSeed.Invalid(mapOf("nodes" to "nodes must not be empty")))

        assertThat(connector().onWebhook(WebhookEvent("dogfood-seed", emptyMap(), body))).isEqualTo(delta)
        assertThat(connector().onWebhook(WebhookEvent("dogfood-seed", emptyMap(), body))).isNull()
    }

    @Test
    fun `names a delivery by its content, so the same batch twice is one delivery`() {
        val first = connector().deliveryId(WebhookEvent("dogfood-seed", emptyMap(), body))
        val again = connector().deliveryId(WebhookEvent("dogfood-seed", emptyMap(), body.copyOf()))

        assertThat(first).isNotBlank().isEqualTo(again)
    }
}
