package com.repodatagraph.adapter.out.githubactions

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.repodatagraph.adapter.out.github.GitHubRepoRef
import java.time.Instant

/**
 * A workflow run as GitHub describes it (#90): which commit and workflow, when it ran, and how it
 * ended. Only the fields the connector reads, so GitHub adding one is not a failure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class WorkflowRun(
    val id: Long,
    val name: String? = null,
    /** The workflow file, `.github/workflows/release.yml`; `dynamic/...` for one GitHub made up. */
    val path: String? = null,
    val headSha: String,
    val status: String? = null,
    val conclusion: String? = null,
    val createdAt: Instant,
    val runStartedAt: Instant? = null,
    /** When the run last changed: its completion, once it has completed. */
    val updatedAt: Instant,
    val htmlUrl: String? = null,
) {
    val completed: Boolean get() = status == COMPLETED

    /** When the run began working: a re-run starts again, later than the run was created. */
    val startedAt: Instant get() = runStartedAt ?: createdAt

    private companion object {
        const val COMPLETED = "completed"
    }
}

/** One page of a repository's runs, which GitHub wraps in an envelope rather than listing bare. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class WorkflowRunPage(
    val totalCount: Int = 0,
    val workflowRuns: List<WorkflowRun> = emptyList(),
)

/**
 * A deployment as the Deployments API records it: a commit, an environment and when it was asked
 * for. A job with an `environment:` key makes one of these too, so both reach the graph the same way.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class GitHubDeployment(
    val id: Long,
    val sha: String,
    val environment: String,
    val createdAt: Instant,
    val creator: GitHubActor? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubActor(
    val login: String? = null,
)

/** One step in a deployment's progress, newest first as GitHub lists them. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DeploymentStatus(
    val id: Long,
    val state: String,
    val createdAt: Instant,
    val logUrl: String? = null,
    val targetUrl: String? = null,
) {
    /**
     * The workflow run that reported this status, when a run did: GitHub names it only in the links,
     * as `.../actions/runs/<id>/job/<job>`.
     */
    val runId: Long?
        get() =
            listOfNotNull(logUrl, targetUrl)
                .firstNotNullOfOrNull {
                    RUN_LINK
                        .find(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                }

    /**
     * `inactive`: GitHub marking this deployment as replaced once a later one to the environment
     * succeeded. It says nothing about how this one went, and supersession is the graph's to work
     * out, so it is not read as the deployment's latest status.
     */
    val isReplacement: Boolean get() = state == INACTIVE

    private companion object {
        const val INACTIVE = "inactive"
        val RUN_LINK = Regex("""/actions/runs/(\d+)""")
    }
}

/** A package in GitHub Packages, and the repository it is published from where GitHub knows one. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class GitHubPackage(
    val name: String,
    val packageType: String,
    val repository: GitHubRepoRef? = null,
)

/**
 * One version of a package: a container image's is named by its digest and carries its tags, any
 * other kind's is named by its version.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PackageVersion(
    val name: String,
    val createdAt: Instant,
    val metadata: PackageMetadata? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PackageMetadata(
    val packageType: String? = null,
    val container: ContainerMetadata? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ContainerMetadata(
    val tags: List<String> = emptyList(),
)
