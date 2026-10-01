package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkFixtures
import com.repodatagraph.application.links.LinkFixtures.repository
import com.repodatagraph.application.links.LinkFixtures.resource
import com.repodatagraph.application.links.LinkProposal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The naming rule (#28, FR2): a resource called what a repository is called, once the decoration
 * clouds and teams add is taken off. Only ever a candidate on its own, since names collide.
 */
class NamingLinkRuleTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "billing-prod, billing",
        "billing_dev, billing",
        "Billing-STG, billing",
        "billing-production, billing",
        "billing-staging, billing",
        "billing.test, billing",
        "123456789012-billing, billing",
        "eu-west-1-billing-prod, billing",
        "us-gov-west-1-billing, billing",
        "europe-west1-billing, billing",
        "billing-api, billing-api",
        "prod, prod",
        "billing-api1-x, billing-api1-x",
    )
    fun `a name is normalised by lower-casing it and taking off environment, account and region`(
        name: String,
        normalised: String,
    ) {
        assertEquals(normalised, NamingLinkRule.normalise(name, LinkFixtures.ENVIRONMENTS))
    }

    private val store =
        LinkFixtures.store(
            repository("github.com/acme/billing"),
            repository("github.com/acme/payments", packageNames = listOf("@acme/payments-client", "com.acme:ledger")),
        )
    private val context = LinkFixtures.FakeContext(store)
    private val rule = NamingLinkRule()

    @Test
    fun `a resource named after a repository proposes it at 0_4`() {
        val proposals = rule.evaluate(resource("aws:arn:aws:sqs:eu-west-1:1:billing-prod", name = "billing-prod"), context)

        assertEquals(
            listOf(
                LinkProposal(
                    "github.com/acme/billing",
                    0.4,
                    "naming",
                    mapOf("name" to "billing-prod", "normalised" to "billing", "matched" to "name"),
                ),
            ),
            proposals,
        )
    }

    @Test
    fun `a resource named after a package a repository publishes proposes that repository`() {
        val client =
            rule.evaluate(
                resource("aws:arn:aws:lambda:eu-west-1:1:function:payments-client-dev", name = "payments-client-dev"),
                context,
            )
        val ledger = rule.evaluate(resource("gcp://ledger-prod", name = "ledger-prod"), context)

        assertEquals(listOf("github.com/acme/payments"), client.map { it.repoKey })
        assertEquals("packageName", client.single().evidence["matched"])
        assertEquals(listOf("github.com/acme/payments"), ledger.map { it.repoKey })
    }

    @Test
    fun `a name matching nothing proposes nothing`() {
        assertEquals(
            emptyList<LinkProposal>(),
            rule.evaluate(resource("aws:arn:aws:sqs:eu-west-1:1:orders-prod", name = "orders-prod"), context),
        )
    }
}
