package com.repodatagraph.domain.ontology

import com.repodatagraph.domain.exception.InvalidGitRemoteException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant

/**
 * Identity keys are what stop the same real-world thing becoming two nodes when two connectors
 * report it. They are derived from properties, never generated.
 */
class IdentityResolverTest {
    private val resolver = IdentityResolver()

    @ParameterizedTest(name = "{0} resolves to github.com/acme/payments")
    @CsvSource(
        "https://github.com/Acme/Payments.git",
        "https://github.com/acme/payments",
        "git@github.com:acme/payments.git",
        "ssh://git@github.com/acme/payments.git",
        "acme/payments",
        "HTTPS://GitHub.com/ACME/PAYMENTS/",
    )
    fun `every form of a repository remote resolves to one key`(url: String) {
        val key = resolver.keyFor("Repository", mapOf("url" to url))

        assertEquals("github.com/acme/payments", key.key)
        assertEquals("Repository:github.com/acme/payments", key.id)
    }

    @Test
    fun `a repository host other than github is preserved`() {
        val key = resolver.keyFor("Repository", mapOf("url" to "https://gitlab.example.com/team/tool.git"))

        assertEquals("gitlab.example.com/team/tool", key.key)
    }

    @Test
    fun `a repository can be identified by its parts instead of a url`() {
        val key =
            resolver.keyFor(
                "Repository",
                mapOf("host" to "github.com", "org" to "Acme", "name" to "Payments"),
            )

        assertEquals("github.com/acme/payments", key.key)
    }

    /**
     * Now an [InvalidGitRemoteException] rather than an [IdentityResolutionException], because the
     * two say different things to a caller. This one means the url they sent is wrong and can be
     * corrected, which the API reports as a 400. Identity resolution failing means the node type has
     * no rule, which is ours to fix, not theirs (#8).
     */
    @Test
    fun `a url that is not a repository is rejected`() {
        val error =
            assertThrows<InvalidGitRemoteException> {
                resolver.keyFor("Repository", mapOf("url" to "https://example.com"))
            }

        assertEquals(true, error.message!!.contains("https://example.com"))
    }

    @Test
    fun `cloud resources are keyed by provider and native identifier`() {
        val key =
            resolver.keyFor(
                "CloudResource",
                mapOf("provider" to "aws", "resourceId" to "arn:aws:s3:::acme-logs"),
            )

        assertEquals("aws:arn:aws:s3:::acme-logs", key.key)
    }

    @Test
    fun `configuration items are keyed by instance and sys_id so two instances cannot collide`() {
        val key =
            resolver.keyFor(
                "ConfigurationItem",
                mapOf("sourceSystem" to "servicenow", "instance" to "acme", "sysId" to "abc123"),
            )

        assertEquals("servicenow:acme:abc123", key.key)
    }

    @Test
    fun `pipelines are keyed by provider, repository and workflow path`() {
        val key =
            resolver.keyFor(
                "Pipeline",
                mapOf(
                    "provider" to "github-actions",
                    "repoKey" to "github.com/acme/payments",
                    "workflowPath" to ".github/workflows/ci.yml",
                ),
            )

        assertEquals("github-actions:github.com/acme/payments:.github/workflows/ci.yml", key.key)
    }

    @Test
    fun `artifacts prefer an immutable digest`() {
        val key =
            resolver.keyFor(
                "Artifact",
                mapOf("registry" to "ghcr.io", "name" to "acme/payments", "digest" to "sha256:abc"),
            )

        assertEquals("ghcr.io/acme/payments@sha256:abc", key.key)
    }

    @Test
    fun `artifacts fall back to name and version when no digest is known`() {
        val key = resolver.keyFor("Artifact", mapOf("name" to "payments", "version" to "1.4.0"))

        assertEquals("payments:1.4.0", key.key)
    }

    @ParameterizedTest(name = "environment ''{0}'' resolves to ''{1}''")
    @CsvSource(
        "prod, production",
        "PROD, production",
        "production, production",
        "stg, staging",
        "stage, staging",
        "staging, staging",
        "dev, development",
        "qa, qa",
    )
    fun `environment aliases collapse to one name`(
        given: String,
        expected: String,
    ) {
        assertEquals(expected, resolver.keyFor("Environment", mapOf("name" to given)).key)
    }

    @Test
    fun `teams and services are keyed by lowercased name`() {
        assertEquals("platform", resolver.keyFor("Team", mapOf("name" to "Platform")).key)
        assertEquals("payments", resolver.keyFor("Service", mapOf("name" to "Payments")).key)
    }

    @Test
    fun `a deployment is identified by what was deployed, where, and when`() {
        val key =
            resolver.keyFor(
                "Deployment",
                mapOf(
                    "artifactKey" to "ghcr.io/acme/payments@sha256:abc",
                    "environmentKey" to "production",
                    "deployedAt" to Instant.parse("2026-01-02T03:04:05Z"),
                ),
            )

        assertEquals("ghcr.io/acme/payments@sha256:abc#production#1767323045", key.key)
    }

    @Test
    fun `libraries are keyed by ecosystem as well as name`() {
        val npm = resolver.keyFor("Library", mapOf("ecosystem" to "npm", "name" to "utils"))
        val pypi = resolver.keyFor("Library", mapOf("ecosystem" to "pypi", "name" to "utils"))

        // "utils" on npm and "utils" on PyPI are different packages. A key on name alone would merge
        // two unrelated dependencies into one node, and every traversal through it would be wrong.
        assertEquals("npm:utils", npm.key)
        assertEquals("pypi:utils", pypi.key)
    }

    @Test
    fun `a library keeps the case its ecosystem is case-sensitive about`() {
        val key = resolver.keyFor("Library", mapOf("ecosystem" to "MAVEN", "name" to "com.acme:Payments"))

        // Maven coordinates are case-sensitive; the ecosystem name is not.
        assertEquals("maven:com.acme:Payments", key.key)
    }

    @Test
    fun `an IaC file is keyed by the repository and the path within it`() {
        val key =
            resolver.keyFor(
                "IacFile",
                mapOf("repoKey" to "github.com/acme/payments", "path" to "infra/main.tf"),
            )

        assertEquals("github.com/acme/payments:infra/main.tf", key.key)
    }

    @Test
    fun `a missing required property is reported rather than producing a partial key`() {
        val error =
            assertThrows<IdentityResolutionException> {
                resolver.keyFor("CloudResource", mapOf("provider" to "aws"))
            }

        assertEquals(true, error.message!!.contains("resourceId"))
    }

    @Test
    fun `an unknown type cannot be keyed`() {
        assertThrows<IdentityResolutionException> { resolver.keyFor("Nonsense", mapOf("name" to "x")) }
    }

    @Test
    fun `a service principal is keyed by its name exactly, since client ids are case-sensitive (#115)`() {
        assertEquals("Triage-Agent", resolver.keyFor("ServicePrincipal", mapOf("name" to " Triage-Agent ")).key)
    }

    @Test
    fun `a change is keyed by its repository and sha, the repository in any remote form and the sha lowercased (#85)`() {
        val key = resolver.keyFor("Change", mapOf("repositoryKey" to "https://github.com/Acme/Payments.git", "sha" to "A1B2C3"))

        assertEquals("Change:github.com/acme/payments@a1b2c3", key.id)
    }

    @Test
    fun `a pull request is keyed by its repository and number (#85)`() {
        assertEquals(
            "github.com/acme/payments/pull/42",
            resolver.keyFor("PullRequest", mapOf("repositoryKey" to "github.com/acme/payments", "number" to 42)).key,
        )
        assertEquals(
            "github.com/acme/payments/pull/42",
            resolver.keyFor("PullRequest", mapOf("repositoryKey" to "acme/payments", "number" to "42")).key,
        )
    }

    @ParameterizedTest(name = "{0} is its own key")
    @CsvSource(
        "chorus://task/01JABC",
        "https://acme.atlassian.net/browse/PAY-42",
        "https://acme.atlassian.net/browse/pay-42",
        "HTTPS://Linear.app/acme/issue/ENG-7/",
    )
    fun `a work item is keyed by its uri exactly as written, case and all (#85)`(uri: String) {
        assertEquals("ExternalWorkItem:$uri", resolver.keyFor("ExternalWorkItem", mapOf("uri" to uri)).id)
    }

    @Test
    fun `two work items whose uris differ only in case are two work items (#85)`() {
        val upper = resolver.keyFor("ExternalWorkItem", mapOf("uri" to "https://acme.atlassian.net/browse/PAY-42"))
        val lower = resolver.keyFor("ExternalWorkItem", mapOf("uri" to "https://acme.atlassian.net/browse/pay-42"))

        assertEquals(false, upper == lower)
    }

    @Test
    fun `a change without a sha, or a pull request without a number, cannot be keyed (#85)`() {
        assertThrows<IdentityResolutionException> { resolver.keyFor("Change", mapOf("repositoryKey" to "github.com/acme/payments")) }
        assertThrows<IdentityResolutionException> { resolver.keyFor("PullRequest", mapOf("repositoryKey" to "github.com/acme/payments")) }
        assertThrows<IdentityResolutionException> { resolver.keyFor("ExternalWorkItem", mapOf("system" to "chorus")) }
    }
}
