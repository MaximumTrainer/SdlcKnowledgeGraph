package com.repodatagraph.adapter.out.githubactions

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Which workflow run reported a deployment status (#90, FR-6). GitHub names it only in the status's
 * links, as `.../actions/runs/<id>/job/<job>` when a job with an `environment:` key made it.
 */
class DeploymentStatusTest {
    private val at = Instant.parse("2026-09-30T10:00:00Z")

    @Test
    fun `names the run in its log url`() {
        val status = DeploymentStatus(1, "success", at, logUrl = "https://github.com/acme/payments/actions/runs/4711/job/47110")

        assertThat(status.runId).isEqualTo(4711L)
    }

    @Test
    fun `falls back to the target url, and to nothing`() {
        assertThat(DeploymentStatus(1, "success", at, targetUrl = "https://github.com/acme/payments/actions/runs/12").runId).isEqualTo(12L)
        assertThat(DeploymentStatus(1, "success", at, logUrl = "", targetUrl = "https://example.test/deploys/9").runId).isNull()
    }

    @Test
    fun `an inactive status says only that a later deployment replaced this one`() {
        assertThat(DeploymentStatus(1, "inactive", at).isReplacement).isTrue()
        assertThat(DeploymentStatus(1, "success", at).isReplacement).isFalse()
    }
}
