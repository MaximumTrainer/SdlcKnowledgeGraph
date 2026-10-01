package com.repodatagraph.application.policy

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.policy.AgentFact
import com.repodatagraph.domain.policy.ResourceKind
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * The graph facts an agent cites, as the graph holds them (#95 FR-4): the agent-actions policy is
 * given these, never what the agent said about them.
 *
 * A fact is a node id (`Deployment:payments-api@2026-10-01`) or an edge id as the graph view writes
 * it (`CAUSED_BY:Incident:INC1>Deployment:d1`). How old it is comes from when its source observed it,
 * or failing that when it was ingested; a deployment's from when it was deployed. For a deployment the
 * policy is also told its environment, and whether a different artifact went there before it, so
 * there is something to roll back to.
 */
@Component
class GraphAgentFacts(
    private val graphStore: GraphStore,
    private val clock: Clock,
) {
    /** What the graph holds about [id], a node id or an edge id; not found when it holds nothing by it. */
    fun fact(id: String): AgentFact {
        val edge = EDGE_ID.matchEntire(id)
        val found =
            if (edge != null) {
                edgeFact(id, edge.destructured.component1(), edge.destructured.component2(), edge.destructured.component3())
            } else {
                runCatching { NodeKey.parse(id) }.getOrNull()?.let(graphStore::findNode)?.let { nodeFact(id, it) }
            }
        return found ?: AgentFact(id, found = false)
    }

    private fun edgeFact(
        id: String,
        type: String,
        from: String,
        to: String,
    ): AgentFact? =
        runCatching { graphStore.findEdge(type, NodeKey.parse(from), NodeKey.parse(to)) }
            .getOrNull()
            ?.let { provenanceFact(id, ResourceKind.EDGE, type, it.provenance) }

    private fun nodeFact(
        id: String,
        node: GraphNode,
    ): AgentFact {
        val fact = provenanceFact(id, ResourceKind.NODE, node.type, node.provenance)
        if (node.type != DEPLOYMENT) return fact
        val environment = text(node.props[ENVIRONMENT_ID]) ?: text(node.props[ENVIRONMENT_KEY])
        return fact.copy(
            ageMinutes = age(instant(node.props[DEPLOYED_AT]) ?: node.provenance.observedAt ?: node.provenance.ingestedAt),
            environment = environment,
            priorArtifact = environment != null && priorArtifact(node, environment),
        )
    }

    private fun provenanceFact(
        id: String,
        kind: ResourceKind,
        type: String,
        provenance: Provenance,
    ) = AgentFact(
        id = id,
        found = true,
        kind = kind,
        type = type,
        confidence = provenance.confidence,
        inferred = provenance.inferred,
        ageMinutes = age(provenance.observedAt ?: provenance.ingestedAt),
    )

    /** Whether a different artifact was deployed to the same [environment] before [deployment]. */
    private fun priorArtifact(
        deployment: GraphNode,
        environment: String,
    ): Boolean {
        val deployedAt = instant(deployment.props[DEPLOYED_AT])
        val artifact = artifactOf(deployment)
        val property = if (deployment.props[ENVIRONMENT_ID] != null) ENVIRONMENT_ID else ENVIRONMENT_KEY
        return deployedAt != null &&
            graphStore.findNodes(DEPLOYMENT, mapOf(property to environment)).any { earlier ->
                val at = instant(earlier.props[DEPLOYED_AT])
                val other = artifactOf(earlier)
                earlier.key != deployment.key && at != null && at.isBefore(deployedAt) && other != null && other != artifact
            }
    }

    private fun artifactOf(deployment: GraphNode): String? = text(deployment.props[ARTIFACT_ID]) ?: text(deployment.props[ARTIFACT_KEY])

    private fun age(at: Instant): Long = Duration.between(at, Instant.now(clock)).toMinutes().coerceAtLeast(0)

    private fun text(value: Any?): String? = value?.toString()?.takeIf { it.isNotBlank() }

    private fun instant(value: Any?): Instant? =
        when (value) {
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is ZonedDateTime -> value.toInstant()
            null -> null
            else ->
                try {
                    Instant.parse(value.toString())
                } catch (_: DateTimeParseException) {
                    null
                }
        }

    private companion object {
        const val DEPLOYMENT = "Deployment"
        const val DEPLOYED_AT = "deployedAt"
        const val ARTIFACT_ID = "artifactId"
        const val ARTIFACT_KEY = "artifactKey"
        const val ENVIRONMENT_ID = "environmentId"
        const val ENVIRONMENT_KEY = "environmentKey"

        /** `TYPE:FromType:fromKey>ToType:toKey`, the edge id of the graph view (#9). */
        val EDGE_ID = Regex("^([A-Z][A-Z0-9_]*):([^>]+)>(.+)$")
    }
}
