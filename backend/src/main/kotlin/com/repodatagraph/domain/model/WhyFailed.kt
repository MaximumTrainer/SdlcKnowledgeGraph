package com.repodatagraph.domain.model

import java.time.Instant

/** One deployment as the why-failed analysis sees it: which repository it came from, at which commit, when and how it went. */
data class DeploymentRecord(
    val id: String,
    val repository: NodeKey?,
    val commitSha: String?,
    val deployedAt: Instant,
    val status: String,
)

/**
 * What the graph knows around a deployment (#21, FR4 and FR5): its lineage, every deployment of the
 * same repository to the same environment ([history], which includes this one), and every deployment
 * to that environment of a repository it depends on ([dependencyDeployments]).
 */
data class DeploymentFacts(
    val deployment: GraphNode,
    val status: String,
    val deployedAt: Instant,
    val artifact: GraphNode?,
    val commitSha: String?,
    val repository: GraphNode?,
    val pipeline: GraphNode?,
    val environment: GraphNode?,
    val history: List<DeploymentRecord>,
    val dependencyDeployments: List<DeploymentRecord>,
)

/** One thing that changed between the last success and this failure. [kind] is stable; [detail] is for a person. */
data class FailureReason(
    val kind: String,
    val detail: String,
)

/** Why a deployment failed, as far as the graph can tell (#21, FR4). */
data class WhyFailedResult(
    val deployment: GraphNode,
    val status: String,
    val deployedAt: Instant,
    val artifact: GraphNode?,
    val commitSha: String?,
    val repository: GraphNode?,
    val pipeline: GraphNode?,
    val environment: GraphNode?,
    val changedDependencies: List<DeploymentRecord>,
    val precedingSuccessfulDeployment: DeploymentRecord?,
    val reasons: List<FailureReason>,
)
