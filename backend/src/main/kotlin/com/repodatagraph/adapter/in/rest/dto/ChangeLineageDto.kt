package com.repodatagraph.adapter.`in`.rest.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.repodatagraph.domain.model.CarriedChange
import com.repodatagraph.domain.model.CarriedDeployment
import com.repodatagraph.domain.model.CarriedWorkItem
import com.repodatagraph.domain.model.DeploymentWorkItems
import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.WorkItemDeployments
import io.swagger.v3.oas.annotations.media.Schema

/** An ExternalWorkItem as a reference (ADR-0012): its URI, the system that owns it, and what it was called. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkItemRef(
    val id: String,
    val uri: String,
    val system: String?,
    val externalKey: String?,
    val title: String?,
) {
    companion object {
        fun from(node: GraphNode) =
            WorkItemRef(
                id = node.id,
                uri = node.props["uri"]?.toString() ?: node.key.key,
                system = node.props["system"]?.toString(),
                externalKey = node.props["externalKey"]?.toString(),
                title = node.props["title"]?.toString(),
            )
    }
}

/** A Change: the commit, the repository it was made in and its title where one was recorded. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChangeRef(
    val id: String,
    val sha: String?,
    val repositoryKey: String?,
    val title: String?,
) {
    companion object {
        fun from(node: GraphNode) =
            ChangeRef(
                id = node.id,
                sha = node.props["sha"]?.toString(),
                repositoryKey = node.props["repositoryKey"]?.toString(),
                title = node.props["title"]?.toString(),
            )
    }
}

/** A node named by id alone. */
data class IdRef(
    val id: String,
)

/** A deployment, when it happened and how it went, and the environment it targeted if the graph says. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeploymentRef(
    val id: String,
    @field:Schema(description = "ISO-8601 instant, absent when the deployment records none")
    val deployedAt: String?,
    val status: String?,
    @field:Schema(description = "Absent when the deployment is placed in no environment")
    val environment: EnvironmentRef?,
) {
    companion object {
        fun of(
            deployment: GraphNode,
            deployedAt: java.time.Instant?,
            environment: GraphNode?,
        ) = DeploymentRef(
            id = deployment.id,
            deployedAt = deployedAt?.toString(),
            status = deployment.props["status"]?.toString(),
            environment = environment?.let { EnvironmentRef(it.id, it.key.key, EnvironmentTier.of(it.props["tier"]).wire) },
        )
    }
}

/** A deployment a work item is live in, with the artifacts and changes that carry it there. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LiveDeploymentResponse(
    val id: String,
    val deployedAt: String?,
    val status: String?,
    val environment: EnvironmentRef?,
    val artifacts: List<IdRef>,
    val changes: List<ChangeRef>,
) {
    companion object {
        fun from(carried: CarriedDeployment): LiveDeploymentResponse {
            val deployment = DeploymentRef.of(carried.deployment, carried.deployedAt, carried.environment)
            return LiveDeploymentResponse(
                id = deployment.id,
                deployedAt = deployment.deployedAt,
                status = deployment.status,
                environment = deployment.environment,
                artifacts = carried.artifacts.map { IdRef(it.id) },
                changes = carried.changes.map(ChangeRef::from),
            )
        }
    }
}

/** `GET /api/v1/work-items/deployments` (#85): where a work item is live, most recent deployment first. */
data class WorkItemDeploymentsResponse(
    val workItem: WorkItemRef,
    val deployments: List<LiveDeploymentResponse>,
) {
    companion object {
        fun from(result: WorkItemDeployments) =
            WorkItemDeploymentsResponse(
                workItem = WorkItemRef.from(result.workItem),
                deployments = result.deployments.map(LiveDeploymentResponse::from),
            )
    }
}

/** A change a deployment carries, and the id of the artifact that carried it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CarriedChangeResponse(
    val id: String,
    val sha: String?,
    val repositoryKey: String?,
    val title: String?,
    val artifact: String,
) {
    companion object {
        fun from(carried: CarriedChange): CarriedChangeResponse {
            val change = ChangeRef.from(carried.change)
            return CarriedChangeResponse(change.id, change.sha, change.repositoryKey, change.title, carried.artifact.id)
        }
    }
}

/** A work item a deployment carries, and the ids of the changes that implement it there. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CarriedWorkItemResponse(
    val id: String,
    val uri: String,
    val system: String?,
    val externalKey: String?,
    val title: String?,
    val changes: List<String>,
) {
    companion object {
        fun from(carried: CarriedWorkItem): CarriedWorkItemResponse {
            val workItem = WorkItemRef.from(carried.workItem)
            return CarriedWorkItemResponse(
                id = workItem.id,
                uri = workItem.uri,
                system = workItem.system,
                externalKey = workItem.externalKey,
                title = workItem.title,
                changes = carried.changes.map { it.id },
            )
        }
    }
}

/**
 * `GET /api/v1/deployments/work-items` (#85): the changes a deployment carries and the work items they
 * implement. `lineage` is `unknown` when the deployment's artifact contains no change at all, so the
 * empty lists then mean "not known", not "nothing".
 */
data class DeploymentWorkItemsResponse(
    val deployment: DeploymentRef,
    @field:Schema(
        description = "known, or unknown when no artifact of the deployment CONTAINS a Change",
        allowableValues = ["known", "unknown"],
    )
    val lineage: String,
    val changes: List<CarriedChangeResponse>,
    val workItems: List<CarriedWorkItemResponse>,
) {
    companion object {
        fun from(result: DeploymentWorkItems) =
            DeploymentWorkItemsResponse(
                deployment = DeploymentRef.of(result.deployment, result.deployedAt, result.environment),
                lineage = result.lineage.wire,
                changes = result.changes.map(CarriedChangeResponse::from),
                workItems = result.workItems.map(CarriedWorkItemResponse::from),
            )
    }
}
