package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.AffectedNode
import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactResult
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * The deprecated repository impact (#21) read off the new traversal, in the shape it has always had:
 * the repositories that depend on this one directly, the resources it owns directly, and the
 * deployments of the artifacts it builds.
 */
object LegacyImpactView {
    val EMPTY: Map<String, List<Any>> = mapOf("dependents" to emptyList(), "cloudResources" to emptyList(), "deployments" to emptyList())

    /** A full `Repository:key` id or a bare key, as the repository endpoints have always accepted. */
    fun repositoryKey(repoId: String): NodeKey =
        runCatching { NodeKey.parse(repoId) }.getOrNull()?.takeIf { it.type == REPOSITORY } ?: NodeKey(REPOSITORY, repoId)

    fun of(result: ImpactResult): Map<String, List<Any>> =
        mapOf(
            "dependents" to result.affected.filter { it.reached(REPOSITORY, "DEPENDED_ON_BY") }.map { it.node.toRepository() },
            "cloudResources" to result.affected.filter { it.reached(CLOUD_RESOURCE, "OWNS_RESOURCE") }.map { it.node.toCloudResource() },
            "deployments" to result.affected.filter { it.reached(DEPLOYMENT, "BUILDS", "DEPLOYED_TO") }.map { it.node.toDeployment() },
        )

    private fun AffectedNode.reached(
        type: String,
        vararg edges: String,
    ): Boolean = node.type == type && path.map { it.edge } == edges.toList()

    private fun GraphNode.toRepository() =
        Repository(
            id = id,
            url = str("url"),
            host = str("host"),
            org = str("org"),
            name = str("name"),
            defaultBranch = strOrNull("defaultBranch") ?: "main",
            topics = strings("topics"),
            codeowners = strings("codeowners"),
            serviceId = strOrNull("serviceId"),
            language = strOrNull("language"),
            description = strOrNull("description"),
        )

    private fun GraphNode.toCloudResource() =
        CloudResource(
            id = id,
            provider = str("provider"),
            resourceType = str("resourceType"),
            name = str("name"),
            region = strOrNull("region"),
            repoId = strOrNull("repoId"),
        )

    private fun GraphNode.toDeployment() =
        Deployment(
            id = id,
            artifactId = str("artifactId"),
            environmentId = str("environmentId"),
            deployedAt = instant(props["deployedAt"]),
            deployedBy = strOrNull("deployedBy"),
            status = strOrNull("status") ?: "SUCCESS",
            provenance = provenance,
        )

    private fun GraphNode.str(name: String): String = props[name]?.toString().orEmpty()

    private fun GraphNode.strOrNull(name: String): String? = props[name]?.toString()

    private fun GraphNode.strings(name: String): List<String> = (props[name] as? Collection<*>)?.map { it.toString() } ?: emptyList()

    private fun instant(value: Any?): Instant =
        when (value) {
            is Instant -> value
            is ZonedDateTime -> value.toInstant()
            is OffsetDateTime -> value.toInstant()
            else -> runCatching { Instant.parse(value.toString()) }.getOrDefault(Instant.EPOCH)
        }

    private const val REPOSITORY = "Repository"
    private const val CLOUD_RESOURCE = "CloudResource"
    private const val DEPLOYMENT = "Deployment"
}
