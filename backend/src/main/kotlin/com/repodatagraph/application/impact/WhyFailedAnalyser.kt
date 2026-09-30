package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.DeploymentFacts
import com.repodatagraph.domain.model.DeploymentRecord
import com.repodatagraph.domain.model.FailureReason
import com.repodatagraph.domain.model.WhyFailedResult
import org.springframework.stereotype.Component

/**
 * Why a deployment failed, as far as the graph can tell (#21, FR4 and FR5).
 *
 * The window runs from the last successful deployment of the same repository to the same environment
 * up to this one. A dependency deployed to that environment inside the window is a changed
 * dependency. With no earlier success the window has no start, so every earlier dependency deployment
 * counts, and the missing baseline is itself a reason. A deployment that did not fail has nothing to
 * explain: no reasons and no changed dependencies, though its lineage is still reported.
 */
@Component
class WhyFailedAnalyser {
    fun analyse(facts: DeploymentFacts): WhyFailedResult {
        val preceding =
            facts.history
                .filter { it.status.equals(SUCCESS, ignoreCase = true) && it.deployedAt.isBefore(facts.deployedAt) }
                .maxByOrNull { it.deployedAt }
        val failed = facts.status.equals(FAILED, ignoreCase = true)
        val changed = if (failed) changedDependencies(facts, preceding) else emptyList()

        return WhyFailedResult(
            deployment = facts.deployment,
            status = facts.status,
            deployedAt = facts.deployedAt,
            artifact = facts.artifact,
            commitSha = facts.commitSha,
            repository = facts.repository,
            pipeline = facts.pipeline,
            environment = facts.environment,
            changedDependencies = changed,
            precedingSuccessfulDeployment = preceding,
            reasons = if (failed) reasons(facts, preceding, changed) else emptyList(),
        )
    }

    private fun changedDependencies(
        facts: DeploymentFacts,
        preceding: DeploymentRecord?,
    ): List<DeploymentRecord> =
        facts.dependencyDeployments
            .filter { preceding == null || it.deployedAt.isAfter(preceding.deployedAt) }
            .filter { !it.deployedAt.isAfter(facts.deployedAt) }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<DeploymentRecord> { it.deployedAt }.thenBy { it.id })

    private fun reasons(
        facts: DeploymentFacts,
        preceding: DeploymentRecord?,
        changed: List<DeploymentRecord>,
    ): List<FailureReason> {
        val where =
            facts.environment
                ?.key
                ?.let { " to $it" }
                .orEmpty()
        val reasons = mutableListOf<FailureReason>()
        if (preceding == null) {
            reasons += FailureReason("NO_PRECEDING_SUCCESS", "no earlier successful deployment of this repository$where is recorded")
        } else if (preceding.commitSha != null && facts.commitSha != null && preceding.commitSha != facts.commitSha) {
            reasons +=
                FailureReason(
                    "COMMIT_CHANGED",
                    "commit changed from ${preceding.commitSha} to ${facts.commitSha} since the last successful deployment$where",
                )
        }
        changed.forEach { dependency ->
            val repository = dependency.repository?.key ?: "an unknown repository"
            val commit = dependency.commitSha?.let { " at commit $it" }.orEmpty()
            reasons +=
                FailureReason(
                    "DEPENDENCY_CHANGED",
                    "dependency $repository was deployed$where$commit at ${dependency.deployedAt} (${dependency.status})",
                )
        }
        return reasons
    }

    private companion object {
        const val SUCCESS = "SUCCESS"
        const val FAILED = "FAILED"
    }
}
