package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkFixtures
import com.repodatagraph.application.links.LinkFixtures.manualOwner
import com.repodatagraph.application.links.LinkFixtures.repository
import com.repodatagraph.application.links.LinkFixtures.resource
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The manual rule (#28, FR2): an owner a person stated, or a candidate a person accepted, at 1.0 -
 * so nothing a rule infers outranks it. A closed manual link, and an owner some other source states,
 * are not manual evidence.
 */
class ManualLinkRuleTest {
    private val payments = repository("github.com/acme/payments")
    private val billing = repository("github.com/acme/billing")
    private val ledger = repository("github.com/acme/ledger")
    private val queue = resource("aws:arn:aws:sqs:eu-west-1:1:billing-prod")

    @Test
    fun `a current manual owner is proposed at 1_0, and nothing else is`() {
        val store = LinkFixtures.store(payments, billing, ledger, queue)
        store.upsertEdge(manualOwner(billing.key, queue.key).copy(props = mapOf("rule" to "manual", "acceptedBy" to "dan")))
        store.upsertEdge(manualOwner(payments.key, queue.key).let { it.copy(provenance = it.provenance.copy(validTo = LinkFixtures.T0)) })
        store.upsertEdge(GraphEdge("OWNS_RESOURCE", ledger.key, queue.key, emptyMap(), Provenance.stated("github", LinkFixtures.T0)))

        val proposals = ManualLinkRule().evaluate(queue, LinkFixtures.FakeContext(store))

        assertEquals(listOf("github.com/acme/billing"), proposals.map { it.repoKey })
        assertEquals(1.0, proposals.single().confidence)
        assertEquals("manual", proposals.single().rule)
        assertEquals("dan", proposals.single().evidence["acceptedBy"])
    }
}
