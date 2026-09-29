package com.repodatagraph.application.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What the deploy pipeline may send (#7, FR4-FR5). Every problem is reported against the field it is
 * about, all at once, so a broken workflow step is fixed in one round rather than one field at a time.
 */
class DeploymentReportParserTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val parser = DeploymentReportParser(objectMapper)

    private val valid =
        mapOf(
            "repository" to "github.com/maximumtrainer/sdlcknowledgegraph",
            "commitSha" to "c1",
            "artifacts" to listOf(mapOf("name" to "ghcr.io/maximumtrainer/sdlc-graph-backend", "digest" to "sha256:abc", "tag" to "v1")),
            "environment" to "staging",
            "status" to "SUCCESS",
            "deployedAt" to "2026-09-29T12:00:00Z",
            "deployedBy" to "dan",
            "runUrl" to "https://github.com/maximumtrainer/sdlcknowledgegraph/actions/runs/1",
            "pipeline" to mapOf("provider" to "github-actions", "workflowPath" to ".github/workflows/deploy-dogfood.yml"),
        )

    private fun parse(body: Map<String, Any?>) = parser.parse(objectMapper.writeValueAsBytes(body))

    private fun errorsFor(body: Map<String, Any?>) = (parse(body) as ParsedReport.Invalid).errors

    @Test
    fun `reads a complete report`() {
        val report = (parse(valid) as ParsedReport.Valid).report

        assertThat(report.repository).isEqualTo("github.com/maximumtrainer/sdlcknowledgegraph")
        assertThat(report.commitSha).isEqualTo("c1")
        assertThat(report.artifacts).containsExactly(ReportedArtifact("ghcr.io/maximumtrainer/sdlc-graph-backend", "sha256:abc", "v1"))
        assertThat(report.environment).isEqualTo("staging")
        assertThat(report.status).isEqualTo("SUCCESS")
        assertThat(report.deployedAt).isEqualTo(Instant.parse("2026-09-29T12:00:00Z"))
        assertThat(report.deployedBy).isEqualTo("dan")
        assertThat(report.pipeline).isEqualTo(ReportedPipeline("github-actions", ".github/workflows/deploy-dogfood.yml"))
    }

    @Test
    fun `refuses a report with no artifacts`() {
        assertThat(errorsFor(valid + ("artifacts" to emptyList<Any>()))).containsEntry("artifacts", "artifacts must not be empty")
    }

    @Test
    fun `names every missing field at once`() {
        val errors = errorsFor(mapOf("artifacts" to valid["artifacts"]))

        assertThat(
            errors.keys,
        ).contains("repository", "commitSha", "environment", "status", "deployedAt", "runUrl", "pipeline.workflowPath")
    }

    @Test
    fun `accepts only SUCCESS and FAILED as a status`() {
        assertThat(errorsFor(valid + ("status" to "MAYBE"))).containsEntry("status", "status must be SUCCESS or FAILED")
    }

    @Test
    fun `refuses a time that is not an ISO-8601 instant`() {
        assertThat(errorsFor(valid + ("deployedAt" to "yesterday"))).containsEntry("deployedAt", "deployedAt must be an ISO-8601 instant")
    }

    @Test
    fun `refuses an artifact that says neither its digest nor its tag`() {
        val errors = errorsFor(valid + ("artifacts" to listOf(mapOf("name" to "ghcr.io/acme/api"))))

        assertThat(errors).containsEntry("artifacts[0]", "an artifact needs a digest or a tag")
    }

    @Test
    fun `refuses a repository that is not a git remote`() {
        assertThat(errorsFor(valid + ("repository" to "not a remote"))).containsKey("repository")
    }

    @Test
    fun `refuses a body that is not a JSON object`() {
        val parsed = parser.parse("[1, 2".toByteArray())

        assertThat((parsed as ParsedReport.Invalid).errors).containsEntry("body", "body must be a JSON object")
    }

    @Test
    fun `defaults the pipeline provider to github-actions`() {
        val body = valid + ("pipeline" to mapOf("workflowPath" to ".github/workflows/deploy.yml"))

        assertThat((parse(body) as ParsedReport.Valid).report.pipeline.provider).isEqualTo("github-actions")
    }
}
