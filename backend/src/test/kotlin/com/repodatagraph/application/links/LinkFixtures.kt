package com.repodatagraph.application.links

import com.repodatagraph.domain.model.DeploymentEvidence
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.EnvironmentAliases
import com.repodatagraph.domain.ontology.EnvironmentDef
import com.repodatagraph.domain.port.out.GraphStore
import java.time.Instant

/** Nodes and a context for the link engine's tests, stated the way connectors write them. */
object LinkFixtures {
    val T0: Instant = Instant.parse("2026-09-30T12:00:00Z")

    /** The shipped alias table (environments.yaml), so the naming rule strips what production strips. */
    val ENVIRONMENTS =
        EnvironmentAliases(
            listOf(
                EnvironmentDef("production", aliases = listOf("prod", "prd", "live")),
                EnvironmentDef("staging", aliases = listOf("stg", "stage")),
                EnvironmentDef("development", aliases = listOf("dev")),
                EnvironmentDef("testing", aliases = listOf("test")),
            ),
        )

    fun repository(
        key: String,
        packageNames: List<String> = emptyList(),
    ): GraphNode {
        val (host, org, name) = key.split('/')
        return GraphNode(
            NodeKey("Repository", key),
            mapOf("url" to "https://$key", "host" to host, "org" to org, "name" to name, "packageNames" to packageNames),
            Provenance.stated("github", T0),
        )
    }

    fun resource(
        key: String,
        name: String = key.substringAfterLast('/').substringAfterLast(':'),
        tags: List<String> = emptyList(),
        accountId: String? = "1",
    ): GraphNode {
        val provider = key.substringBefore(':')
        return GraphNode(
            NodeKey("CloudResource", key),
            mapOf(
                "provider" to provider,
                "resourceId" to key.substringAfter(':'),
                "resourceType" to "queue",
                "name" to name,
                "accountId" to accountId,
                "tags" to tags,
            ),
            Provenance.stated(provider, T0),
        )
    }

    fun iacFile(
        repoKey: String,
        path: String,
        vararg refs: String,
        validTo: Instant? = null,
    ): GraphNode =
        GraphNode(
            NodeKey("IacFile", "$repoKey:$path"),
            mapOf("repoKey" to repoKey, "path" to path, "format" to "terraform", "resourceRefs" to refs.toList()),
            Provenance.stated("github", T0).copy(validTo = validTo),
        )

    /** A manual OWNS_RESOURCE, as POST /api/v1/links/manual states one. */
    fun manualOwner(
        repository: NodeKey,
        resource: NodeKey,
    ): GraphEdge =
        GraphEdge(
            "OWNS_RESOURCE",
            repository,
            resource,
            mapOf("rule" to "manual"),
            Provenance.manual(T0),
        )

    /** A context over [store], with the deployments a test names. */
    class FakeContext(
        override val graphStore: GraphStore,
        private val deployments: Map<String, List<DeploymentEvidence>> = emptyMap(),
    ) : LinkContext {
        override val environments: EnvironmentAliases = ENVIRONMENTS

        override fun repository(key: String): GraphNode? = graphStore.findNode(NodeKey("Repository", key))

        override fun repositories(): List<GraphNode> = graphStore.findNodes("Repository")

        override fun iacFiles(): List<GraphNode> = graphStore.findNodes("IacFile")

        override fun deploymentsTargeting(resourceKey: String): List<DeploymentEvidence> = deployments[resourceKey].orEmpty()
    }

    fun store(vararg nodes: GraphNode): InMemoryGraphStore = InMemoryGraphStore().apply { nodes.forEach { upsertNode(it) } }
}
