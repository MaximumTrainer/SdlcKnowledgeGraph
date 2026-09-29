package com.repodatagraph.application.ingest

import java.time.Instant

/**
 * What the deploy pipeline says it did (#7): which commit, built into which artifacts, deployed to
 * where, by which run, and whether it worked.
 *
 * The payload is documented in docs/ADAPTERS.md, and #22's connector webhook takes it unchanged.
 */
data class DeploymentReport(
    val repository: String,
    val commitSha: String,
    val artifacts: List<ReportedArtifact>,
    val environment: String,
    val status: String,
    val deployedAt: Instant,
    val deployedBy: String?,
    val runUrl: String,
    val pipeline: ReportedPipeline,
)

/** An image as the registry names it: `ghcr.io/acme/api`, with its digest and the tag it was pushed under. */
data class ReportedArtifact(
    val name: String,
    val digest: String?,
    val tag: String?,
)

data class ReportedPipeline(
    val provider: String,
    val workflowPath: String,
)

/** A report, or everything that is wrong with it. */
sealed interface ParsedReport {
    data class Valid(
        val report: DeploymentReport,
    ) : ParsedReport

    data class Invalid(
        val errors: Map<String, String>,
    ) : ParsedReport
}
