package com.repodatagraph.domain.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The edges that join a deployment to the intent it carries (#85), found in the registry by the node
 * types each one connects rather than by name: an Artifact's deployments ([deployedAs]), the Changes
 * an Artifact holds ([contains]), the ExternalWorkItems a Change implements ([implements]), and the
 * edges that place a deployment in its environment ([placement]).
 */
data class LineageTraversal(
    val deployedAs: Set<String>,
    val contains: Set<String>,
    val implements: Set<String>,
    val placement: Set<String>,
) {
    /** Whether the registry declares the whole chain; without it no deployment can carry a work item. */
    val isComplete: Boolean get() = deployedAs.isNotEmpty() && contains.isNotEmpty() && implements.isNotEmpty()
}

/**
 * Whether a deployment's change lineage is in the graph (#85, FR5). [UNKNOWN] when its artifact has no
 * CONTAINS edge at all: an empty list of work items would read as "carries nothing", which the graph
 * cannot say.
 */
enum class LineageStatus(
    val wire: String,
) {
    KNOWN("known"),
    UNKNOWN("unknown"),
}

/** One way a work item reaches a deployment: a change implementing it, in an artifact that was deployed. */
data class WorkItemCarrier(
    val deployment: GraphNode,
    val artifact: GraphNode,
    val change: GraphNode,
)

/** One change a deployment's artifact contains, with the work items it implements (none is possible). */
data class DeployedChange(
    val artifact: GraphNode,
    val change: GraphNode,
    val workItems: List<GraphNode>,
)

/** A deployment a work item is live in, with the artifacts and changes that carry it there. */
data class CarriedDeployment(
    val deployment: GraphNode,
    val environment: GraphNode?,
    val artifacts: List<GraphNode>,
    val changes: List<GraphNode>,
) {
    val deployedAt: Instant? get() = deployedAtOf(deployment)
}

/** Where a work item is live (#85): every deployment carrying a change that implements it. */
data class WorkItemDeployments(
    val workItem: GraphNode,
    val deployments: List<CarriedDeployment>,
)

/** A change a deployment carries, and the artifact it was carried in. */
data class CarriedChange(
    val change: GraphNode,
    val artifact: GraphNode,
)

/** A work item a deployment carries, and the changes that implement it there. */
data class CarriedWorkItem(
    val workItem: GraphNode,
    val changes: List<GraphNode>,
)

/** What intent a deployment carries (#85), and whether that can be known at all. */
data class DeploymentWorkItems(
    val deployment: GraphNode,
    val environment: GraphNode?,
    val lineage: LineageStatus,
    val changes: List<CarriedChange>,
    val workItems: List<CarriedWorkItem>,
) {
    val deployedAt: Instant? get() = deployedAtOf(deployment)
}

/**
 * When a deployment happened. The API stores an instant as the ISO-8601 text it was given and the
 * deployment ingest as a date-time, so both are read; anything else is not an instant.
 */
fun deployedAtOf(deployment: GraphNode): Instant? =
    when (val value = deployment.props["deployedAt"]) {
        null -> null
        is Instant -> value
        is ZonedDateTime -> value.toInstant()
        is OffsetDateTime -> value.toInstant()
        is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
        else -> runCatching { Instant.parse(value.toString()) }.getOrNull()
    }
