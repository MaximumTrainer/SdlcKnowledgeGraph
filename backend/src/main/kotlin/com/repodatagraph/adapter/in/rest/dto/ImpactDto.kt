package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.AffectedNode
import com.repodatagraph.domain.model.DeploymentRecord
import com.repodatagraph.domain.model.FailureReason
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactResult
import com.repodatagraph.domain.model.Owner
import com.repodatagraph.domain.model.OwnersResult
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.WhyFailedResult

/** A node named, not described: enough to address it through `/api/v1/nodes/{type}/{key}`. */
data class NodeRef(
    val id: String,
    val type: String,
    val key: String,
) {
    companion object {
        fun from(node: GraphNode) = NodeRef(node.id, node.type, node.key.key)
    }
}

/** A node with its properties, for a caller that shows what was reached without fetching each one. */
data class ImpactNodeView(
    val id: String,
    val type: String,
    val key: String,
    val props: Map<String, Any?>,
) {
    companion object {
        fun from(node: GraphNode) = ImpactNodeView(node.id, node.type, node.key.key, node.props)
    }
}

/** One hop of an explanation, as walked: the edge's name in that direction, both ends, its confidence. */
data class PathStepResponse(
    val edge: String,
    val from: String,
    val to: String,
    val confidence: Double,
    val inferred: Boolean,
) {
    companion object {
        fun from(step: PathStep) = PathStepResponse(step.edge, step.from, step.to, step.confidence, step.inferred)
    }
}

data class AffectedNodeResponse(
    val node: ImpactNodeView,
    val distance: Int,
    val confidence: Double,
    val inferred: Boolean,
    val path: List<PathStepResponse>,
) {
    companion object {
        fun from(affected: AffectedNode) =
            AffectedNodeResponse(
                node = ImpactNodeView.from(affected.node),
                distance = affected.distance,
                confidence = affected.confidence,
                inferred = affected.inferred,
                path = affected.path.map(PathStepResponse::from),
            )
    }
}

/** `GET /api/v1/graph/impact` (#21, FR1). Replaces the repository-only `ImpactAnalysisResponse`. */
data class ImpactResponse(
    val root: NodeRef,
    val depth: Int,
    val direction: String,
    val minConfidence: Double,
    val truncated: Boolean,
    val affected: List<AffectedNodeResponse>,
    /** Affected nodes by type, counted before the listing cap, and `excluded`: those below minConfidence. */
    val byType: Map<String, Int>,
) {
    companion object {
        fun from(result: ImpactResult) =
            ImpactResponse(
                root = NodeRef.from(result.root),
                depth = result.depth,
                direction = result.direction.name.lowercase(),
                minConfidence = result.minConfidence,
                truncated = result.truncated,
                affected = result.affected.map(AffectedNodeResponse::from),
                byType = result.byType,
            )
    }
}

data class PipelineRef(
    val id: String,
    val lastRunStatus: String?,
)

data class DeploymentRecordResponse(
    val id: String,
    val repository: NodeRef?,
    val commitSha: String?,
    val deployedAt: String,
    val status: String,
) {
    companion object {
        fun from(record: DeploymentRecord) =
            DeploymentRecordResponse(
                id = record.id,
                repository = record.repository?.let { NodeRef(it.id, it.type, it.key) },
                commitSha = record.commitSha,
                deployedAt = record.deployedAt.toString(),
                status = record.status,
            )
    }
}

data class FailureReasonResponse(
    val kind: String,
    val detail: String,
) {
    companion object {
        fun from(reason: FailureReason) = FailureReasonResponse(reason.kind, reason.detail)
    }
}

/** `GET /api/v1/graph/why-failed` (#21, FR4). Absent lineage is null, never omitted. */
data class WhyFailedResponse(
    val deployment: NodeRef,
    val status: String,
    val deployedAt: String,
    val artifact: NodeRef?,
    val commitSha: String?,
    val repository: NodeRef?,
    val pipeline: PipelineRef?,
    val environment: NodeRef?,
    val changedDependencies: List<DeploymentRecordResponse>,
    val precedingSuccessfulDeployment: DeploymentRecordResponse?,
    val reasons: List<FailureReasonResponse>,
) {
    companion object {
        fun from(result: WhyFailedResult) =
            WhyFailedResponse(
                deployment = NodeRef.from(result.deployment),
                status = result.status,
                deployedAt = result.deployedAt.toString(),
                artifact = result.artifact?.let(NodeRef::from),
                commitSha = result.commitSha,
                repository = result.repository?.let(NodeRef::from),
                pipeline = result.pipeline?.let { PipelineRef(it.id, it.props["lastRunStatus"]?.toString()) },
                environment = result.environment?.let(NodeRef::from),
                changedDependencies = result.changedDependencies.map(DeploymentRecordResponse::from),
                precedingSuccessfulDeployment = result.precedingSuccessfulDeployment?.let(DeploymentRecordResponse::from),
                reasons = result.reasons.map(FailureReasonResponse::from),
            )
    }
}

data class TeamRef(
    val id: String,
    val key: String,
    val name: String?,
    val email: String?,
)

data class OwnerResponse(
    val team: TeamRef,
    val via: List<PathStepResponse>,
    val confidence: Double,
) {
    companion object {
        fun from(owner: Owner) =
            OwnerResponse(
                team =
                    TeamRef(
                        id = owner.team.id,
                        key = owner.team.key.key,
                        name = owner.team.props["name"]?.toString(),
                        email = owner.team.props["email"]?.toString(),
                    ),
                via = owner.via.map(PathStepResponse::from),
                confidence = owner.confidence,
            )
    }
}

/** `GET /api/v1/graph/owners` (#21, FR6). No owners is `owners: []`, not a 404. */
data class OwnersResponse(
    val node: NodeRef,
    val owners: List<OwnerResponse>,
) {
    companion object {
        fun from(result: OwnersResult) = OwnersResponse(NodeRef.from(result.node), result.owners.map(OwnerResponse::from))
    }
}
