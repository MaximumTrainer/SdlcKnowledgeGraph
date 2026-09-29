package com.repodatagraph.application.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.application.connector.SyncService
import com.repodatagraph.config.IngestProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.`in`.DeploymentIngestOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The order the ingest endpoint decides in (#7, FR5): whether it is on at all, then whether the caller
 * may use it, then whether what they sent makes sense, and only then what it changes.
 */
class DeploymentIngestServiceTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val syncService: SyncService = mock()
    private val recorder: SyncRunRecorder = mock()

    private fun service(token: String = "the-token") =
        DeploymentIngestService(
            IngestProperties(token),
            DeploymentReportParser(objectMapper),
            DeploymentReportMapper(IdentityResolver(), GitRemoteParser()),
            syncService,
            recorder,
        )

    private val report =
        objectMapper.writeValueAsBytes(
            mapOf(
                "repository" to "github.com/acme/api",
                "commitSha" to "c1",
                "artifacts" to listOf(mapOf("name" to "ghcr.io/acme/api", "digest" to "sha256:abc")),
                "environment" to "staging",
                "status" to "SUCCESS",
                "deployedAt" to "2026-09-29T12:00:00Z",
                "runUrl" to "https://github.com/acme/api/actions/runs/1",
                "pipeline" to mapOf("workflowPath" to ".github/workflows/deploy.yml"),
            ),
        )

    @Test
    fun `is off when no token is configured, whatever is sent`() {
        assertThat(service(token = "").ingest("Bearer anything", report)).isEqualTo(DeploymentIngestOutcome.Disabled)
        verify(syncService, never()).applyWebhook(any(), any())
    }

    @Test
    fun `refuses a caller without the token before reading what they sent`() {
        assertThat(service().ingest(null, "not even json".toByteArray())).isEqualTo(DeploymentIngestOutcome.Unauthorized)
        assertThat(service().ingest("Bearer wrong", report)).isEqualTo(DeploymentIngestOutcome.Unauthorized)
        verify(syncService, never()).applyWebhook(any(), any())
    }

    @Test
    fun `names what is wrong with a report and applies none of it`() {
        val outcome = service().ingest("Bearer the-token", "{}".toByteArray())

        assertThat((outcome as DeploymentIngestOutcome.Invalid).errors).containsKey("repository")
        verify(syncService, never()).applyWebhook(any(), any())
    }

    @Test
    fun `applies a new report through the github-actions connector`() {
        whenever(syncService.applyWebhook(eq("github-actions"), any())).thenReturn("run-1")

        val outcome = service().ingest("Bearer the-token", report) as DeploymentIngestOutcome.Accepted

        assertThat(outcome.created).isTrue()
        assertThat(outcome.deploymentIds).containsExactly("Deployment:ghcr.io/acme/api@sha256:abc#staging#1790683200")
        assertThat(outcome.nodes).isEqualTo(5)
        assertThat(outcome.edges).isEqualTo(4)
        verify(syncService).applyWebhook(eq("github-actions"), any())
    }

    @Test
    fun `says a report it has already applied created nothing`() {
        whenever(recorder.runForDelivery(eq("github-actions"), anyOrNull())).thenReturn("run-1")
        whenever(syncService.applyWebhook(eq("github-actions"), any())).thenReturn("run-1")

        val outcome = service().ingest("Bearer the-token", report) as DeploymentIngestOutcome.Accepted

        assertThat(outcome.created).isFalse()
    }
}
