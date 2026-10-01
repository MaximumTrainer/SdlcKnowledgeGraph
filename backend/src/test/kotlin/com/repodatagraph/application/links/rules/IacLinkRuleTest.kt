package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkFixtures
import com.repodatagraph.application.links.LinkFixtures.iacFile
import com.repodatagraph.application.links.LinkFixtures.repository
import com.repodatagraph.application.links.LinkFixtures.resource
import com.repodatagraph.application.links.LinkProposal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The infrastructure-as-code rule (#28, FR2): a file in a repository names the resource literally -
 * its ARN or id, its name, or a Terraform resource whose local name is the resource's name. A claim
 * that something should exist, so weaker than a tag, but stronger than a name that merely matches.
 */
class IacLinkRuleTest {
    private val rule = IacLinkRule()
    private val bucket = resource("aws:arn:aws:s3:::acme-payments-logs", name = "acme-payments-logs")

    private fun proposals(vararg files: com.repodatagraph.domain.model.GraphNode) =
        rule.evaluate(
            bucket,
            LinkFixtures.FakeContext(LinkFixtures.store(repository("github.com/acme/payments"), *files)),
        )

    @Test
    fun `a reference to the ARN proposes the file's repository at 0_7`() {
        val found = proposals(iacFile("github.com/acme/payments", "infra/main.tf", "arn:aws:s3:::acme-payments-logs"))

        assertEquals(
            listOf(
                LinkProposal(
                    "github.com/acme/payments",
                    0.7,
                    "iac",
                    mapOf("path" to "infra/main.tf", "reference" to "arn:aws:s3:::acme-payments-logs", "matched" to "resourceId"),
                ),
            ),
            found,
        )
    }

    @Test
    fun `a reference to the name matches it whatever its case`() {
        val found = proposals(iacFile("github.com/acme/payments", "infra/main.tf", "Acme-Payments-Logs"))

        assertEquals("name", found.single().evidence["matched"])
        assertEquals("infra/main.tf", found.single().evidence["path"])
    }

    @Test
    fun `a Terraform resource whose local name is the resource's name matches`() {
        val found = proposals(iacFile("github.com/acme/payments", "infra/logs.tf", "aws_s3_bucket.acme_payments_logs"))

        assertEquals("terraform", found.single().evidence["matched"])
        assertEquals("aws_s3_bucket.acme_payments_logs", found.single().evidence["reference"])
    }

    @Test
    fun `references to other things, a retired file and a repository the graph lacks propose nothing`() {
        val unrelated = iacFile("github.com/acme/payments", "infra/other.tf", "acme-payments-receipts", "aws_s3_bucket.receipts")
        val retired = iacFile("github.com/acme/payments", "infra/old.tf", "acme-payments-logs", validTo = LinkFixtures.T0)
        val elsewhere = iacFile("github.com/acme/unknown", "infra/main.tf", "acme-payments-logs")

        assertEquals(emptyList<LinkProposal>(), proposals(unrelated, retired, elsewhere))
    }

    @Test
    fun `two files naming it propose the repository once per file, in path order`() {
        val found =
            proposals(
                iacFile("github.com/acme/payments", "infra/z.tf", "acme-payments-logs"),
                iacFile("github.com/acme/payments", "infra/a.tf", "acme-payments-logs"),
            )

        assertEquals(listOf("infra/a.tf", "infra/z.tf"), found.map { it.evidence["path"] })
    }
}
