package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkFixtures
import com.repodatagraph.application.links.LinkFixtures.repository
import com.repodatagraph.application.links.LinkFixtures.resource
import com.repodatagraph.application.links.LinkProposal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The tag rule (#28, FR2): a cloud tag naming the repository, in any form a git remote is written,
 * under one of the configured keys. The strongest automatic evidence, because someone wrote it on
 * the resource on purpose.
 */
class TagLinkRuleTest {
    private val store = LinkFixtures.store(repository("github.com/acme/payments"))
    private val context = LinkFixtures.FakeContext(store)
    private val rule = TagLinkRule()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "github.com/acme/payments",
            "https://github.com/acme/payments",
            "https://github.com/Acme/Payments.git",
            "git@github.com:acme/payments.git",
            "acme/payments",
        ],
    )
    fun `a repo tag in any remote form proposes the repository at 0_95`(value: String) {
        val proposals = rule.evaluate(resource("aws:arn:aws:lambda:eu-west-1:1:function:x", tags = listOf("repo=$value")), context)

        assertEquals(
            listOf(LinkProposal("github.com/acme/payments", 0.95, "tag", mapOf("tag" to "repo", "value" to value))),
            proposals,
        )
    }

    @Test
    fun `the keys are matched without regard to case, in the order they are configured`() {
        val tags = listOf("Source-Repo=github.com/acme/elsewhere", "REPOSITORY=github.com/acme/payments")

        val proposals = rule.evaluate(resource("aws:arn:aws:s3:::x", tags = tags), context)

        assertEquals(listOf("github.com/acme/payments"), proposals.map { it.repoKey })
        assertEquals("REPOSITORY", proposals.single().evidence["tag"])
    }

    @Test
    fun `a value that is not a remote, a repository the graph does not hold and an unlisted key propose nothing`() {
        listOf("repo=not a remote", "repo=github.com/acme/nowhere", "team=github.com/acme/payments", "repo").forEach { tag ->
            assertEquals(emptyList<LinkProposal>(), rule.evaluate(resource("aws:arn:aws:s3:::x", tags = listOf(tag)), context), tag)
        }
    }

    @Test
    fun `a resource with no tags proposes nothing`() {
        assertEquals(emptyList<LinkProposal>(), rule.evaluate(resource("aws:arn:aws:s3:::x"), context))
    }

    @Test
    fun `the configured confidence and keys are used`() {
        val custom = TagLinkRule(confidence = 0.8, keys = listOf("owner-repo"))

        val proposals =
            custom.evaluate(
                resource("aws:arn:aws:s3:::x", tags = listOf("owner-repo=acme/payments", "repo=acme/payments")),
                context,
            )

        assertEquals(listOf(0.8), proposals.map { it.confidence })
        assertEquals("owner-repo", proposals.single().evidence["tag"])
    }
}
