package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkFixtures
import com.repodatagraph.application.links.LinkFixtures.resource
import com.repodatagraph.application.links.LinkProposal
import com.repodatagraph.domain.model.DeploymentEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The deployment rule (#28, FR2): a pipeline deployed an artifact built from a repository to the
 * resource. Evidence of what actually runs there, so nearly as strong as a tag.
 */
class DeploymentLinkRuleTest {
    private val lambda = resource("aws:arn:aws:lambda:eu-west-1:1:function:payments-api", name = "payments-api")
    private val rule = DeploymentLinkRule()

    private fun deployment(
        repoKey: String,
        deployedAt: String,
        environment: String? = "production",
    ) = DeploymentEvidence(
        deploymentKey = "ghcr.io/acme/payments@sha256:1|production|$deployedAt",
        artifactKey = "ghcr.io/acme/payments@sha256:1",
        repoKey = repoKey,
        environment = environment,
        deployedAt = Instant.parse(deployedAt),
    )

    @Test
    fun `a deployment to the resource of an artifact built from a repository proposes it at 0_9`() {
        val context =
            LinkFixtures.FakeContext(
                LinkFixtures.store(),
                mapOf(lambda.key.key to listOf(deployment("github.com/acme/payments", "2026-09-30T12:00:00Z"))),
            )

        assertEquals(
            listOf(
                LinkProposal(
                    "github.com/acme/payments",
                    0.9,
                    "deployment",
                    mapOf(
                        "deployment" to "ghcr.io/acme/payments@sha256:1|production|2026-09-30T12:00:00Z",
                        "artifact" to "ghcr.io/acme/payments@sha256:1",
                        "environment" to "production",
                        "deployedAt" to "2026-09-30T12:00:00Z",
                    ),
                ),
            ),
            rule.evaluate(lambda, context),
        )
    }

    @Test
    fun `several deployments from one repository propose it once, citing the latest`() {
        val context =
            LinkFixtures.FakeContext(
                LinkFixtures.store(),
                mapOf(
                    lambda.key.key to
                        listOf(
                            deployment("github.com/acme/payments", "2026-09-01T12:00:00Z"),
                            deployment("github.com/acme/payments", "2026-09-30T12:00:00Z", environment = null),
                            deployment("github.com/acme/payments", "2026-09-15T12:00:00Z"),
                        ),
                ),
            )

        val proposals = rule.evaluate(lambda, context)

        assertEquals(1, proposals.size)
        assertEquals("2026-09-30T12:00:00Z", proposals.single().evidence["deployedAt"])
        assertEquals(null, proposals.single().evidence["environment"])
    }

    @Test
    fun `no deployment, no proposal`() {
        assertEquals(emptyList<LinkProposal>(), rule.evaluate(lambda, LinkFixtures.FakeContext(LinkFixtures.store())))
    }
}
