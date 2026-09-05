package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Pipeline
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ServiceNowCI
import com.repodatagraph.domain.model.Team
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component
import java.time.Instant
import com.repodatagraph.domain.model.Repository as DomainRepository

/**
 * The repository-shaped view of the graph, kept so existing callers do not change, implemented
 * entirely on top of [GraphStore].
 *
 * Everything that used to be a Spring Data node class and a per-type repository is gone: nodes are
 * addressed by derived key, writes carry provenance, and relationship writes fail loudly when an
 * endpoint is missing instead of quietly doing nothing.
 */
@Component
class Neo4jRepositoryAdapter(
    private val graphStore: GraphStore,
    private val identityResolver: IdentityResolver,
    private val neo4jClient: Neo4jClient,
) : RepositoryGraphPort {
    override fun save(repository: DomainRepository): DomainRepository {
        val key = identityResolver.keyFor("Repository", mapOf("url" to repository.orgRepo))
        graphStore.upsertNode(
            GraphNode(
                key = key,
                props =
                    mapOf(
                        "orgRepo" to repository.orgRepo,
                        "defaultBranch" to repository.defaultBranch,
                        "topics" to repository.topics,
                        "codeowners" to repository.codeowners,
                        "serviceId" to repository.serviceId,
                        "language" to repository.language,
                        "description" to repository.description,
                    ),
                provenance = Provenance.manual(),
            ),
        )
        // The stored id is the derived one, so a second registration of the same remote updates the
        // node rather than creating a twin under a fresh random id.
        return repository.copy(id = key.id)
    }

    override fun findById(id: String): DomainRepository? = graphStore.findNode(keyOf(id, "Repository"))?.toRepository()

    override fun findAll(): List<DomainRepository> = graphStore.findNodes("Repository").map { it.toRepository() }

    override fun delete(id: String) {
        graphStore.deleteNode(keyOf(id, "Repository"), cascade = true)
    }

    override fun linkToTeam(
        repoId: String,
        teamId: String,
    ) = link("OWNED_BY", repoId, "Repository", teamId, "Team")

    override fun linkToCloudResource(
        repoId: String,
        cloudResourceId: String,
    ) = link("OWNS_RESOURCE", repoId, "Repository", cloudResourceId, "CloudResource")

    override fun linkToPipeline(
        repoId: String,
        pipelineId: String,
    ) = link("HAS_PIPELINE", repoId, "Repository", pipelineId, "Pipeline")

    override fun linkToServiceNowCI(
        repoId: String,
        ciId: String,
    ) = link("RELATES_TO_CI", repoId, "Repository", ciId, "ConfigurationItem")

    override fun addDependency(
        fromRepoId: String,
        toRepoId: String,
    ) = link("DEPENDS_ON", fromRepoId, "Repository", toRepoId, "Repository")

    override fun findCloudResourcesForRepo(repoId: String): List<CloudResource> =
        query(
            """
            MATCH (r:Repository { key: ${'$'}key })-[:OWNS_RESOURCE]->(c:CloudResource)
            RETURN c { .* } AS c
            """.trimIndent(),
            repoId,
        ).map { row ->
            val props = row.nodeProps("c")
            CloudResource(
                id = props.str("id"),
                provider = props.str("provider"),
                resourceType = props.str("resourceType"),
                name = props.str("name"),
                region = props.strOrNull("region"),
                repoId = props.strOrNull("repoId"),
            )
        }

    override fun findDependencies(repoId: String): List<DomainRepository> =
        query(
            "MATCH (r:Repository { key: ${'$'}key })-[:DEPENDS_ON]->(d:Repository) RETURN d { .* } AS d",
            repoId,
        ).map { it.nodeProps("d").toRepository() }

    override fun findDependents(repoId: String): List<DomainRepository> =
        query(
            "MATCH (r:Repository { key: ${'$'}key })<-[:DEPENDS_ON]-(d:Repository) RETURN d { .* } AS d",
            repoId,
        ).map { it.nodeProps("d").toRepository() }

    /**
     * Deployments reached through the artifact that was built from this repository. This returned
     * nothing at all until BUILT_FROM was actually written by [upsertEdge] callers.
     */
    override fun findDeploymentsForRepo(repoId: String): List<Deployment> =
        query(
            """
            MATCH (r:Repository { key: ${'$'}key })<-[:BUILT_FROM]-(a:Artifact)-[:DEPLOYED_TO]->(d:Deployment)
            OPTIONAL MATCH (d)-[:TO_ENVIRONMENT]->(e:Environment)
            RETURN d { .* } AS d, a { .* } AS a, e { .* } AS e
            """.trimIndent(),
            repoId,
        ).map { row ->
            val props = row.nodeProps("d")
            Deployment(
                id = props.str("id"),
                artifactId = props.strOrNull("artifactId") ?: row.nodeProps("a").str("id"),
                environmentId = props.strOrNull("environmentId") ?: row.nodeProps("e").strOrNull("id").orEmpty(),
                deployedAt = props.instant("deployedAt"),
                deployedBy = props.strOrNull("deployedBy"),
                status = props.strOrNull("status") ?: "SUCCESS",
            )
        }

    override fun findTeamForRepo(repoId: String): Team? =
        query(
            "MATCH (r:Repository { key: ${'$'}key })-[:OWNED_BY]->(t:Team) RETURN t { .* } AS t",
            repoId,
        ).firstOrNull()?.let { row ->
            val props = row.nodeProps("t")
            Team(id = props.str("id"), name = props.str("name"), email = props.strOrNull("email"))
        }

    override fun findServiceNowCIForRepo(repoId: String): ServiceNowCI? =
        query(
            "MATCH (r:Repository { key: ${'$'}key })-[:RELATES_TO_CI]->(c:ConfigurationItem) RETURN c { .* } AS c",
            repoId,
        ).firstOrNull()?.let { row ->
            val props = row.nodeProps("c")
            ServiceNowCI(
                id = props.str("id"),
                ciName = props.str("ciName"),
                serviceId = props.strOrNull("serviceId").orEmpty(),
                repoId = props.strOrNull("repoId"),
            )
        }

    override fun findPipelinesForRepo(repoId: String): List<Pipeline> =
        query(
            "MATCH (r:Repository { key: ${'$'}key })-[:HAS_PIPELINE]->(p:Pipeline) RETURN p { .* } AS p",
            repoId,
        ).map { row ->
            val props = row.nodeProps("p")
            Pipeline(
                id = props.str("id"),
                name = props.str("name"),
                provider = props.str("provider"),
                repoId = props.strOrNull("repoId").orEmpty(),
                lastRunStatus = props.strOrNull("lastRunStatus"),
            )
        }

    private fun link(
        edgeType: String,
        fromId: String,
        fromType: String,
        toId: String,
        toType: String,
    ) {
        graphStore.upsertEdge(
            GraphEdge(
                type = edgeType,
                from = keyOf(fromId, fromType),
                to = keyOf(toId, toType),
                provenance = Provenance.manual(),
            ),
        )
    }

    /** Accepts either a full `Type:key` id or a bare key, so older callers keep working. */
    private fun keyOf(
        id: String,
        expectedType: String,
    ): NodeKey =
        runCatching { NodeKey.parse(id) }
            .getOrNull()
            ?.takeIf { it.type == expectedType }
            ?: NodeKey(expectedType, id)

    private fun query(
        cypher: String,
        repoId: String,
    ): List<Map<String, Any?>> =
        neo4jClient
            .query(cypher)
            .bindAll(mapOf("key" to keyOf(repoId, "Repository").key))
            .fetch()
            .all()
            .toList()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.nodeProps(alias: String): Map<String, Any?> = (this[alias] as? Map<String, Any?>).orEmpty()

    private fun Map<String, Any?>.str(name: String): String = this[name]?.toString().orEmpty()

    private fun Map<String, Any?>.strOrNull(name: String): String? = this[name]?.toString()

    private fun Map<String, Any?>.instant(name: String): Instant =
        when (val value = this[name]) {
            is Instant -> value
            is java.time.ZonedDateTime -> value.toInstant()
            is java.time.OffsetDateTime -> value.toInstant()
            else -> runCatching { Instant.parse(value.toString()) }.getOrDefault(Instant.EPOCH)
        }

    private fun Map<String, Any?>.toRepository(): DomainRepository =
        DomainRepository(
            id = str("id").ifEmpty { "Repository:${str("key")}" },
            orgRepo = str("orgRepo").ifEmpty { str("key") },
            defaultBranch = strOrNull("defaultBranch") ?: "main",
            topics = stringList("topics"),
            codeowners = stringList("codeowners"),
            serviceId = strOrNull("serviceId"),
            language = strOrNull("language"),
            description = strOrNull("description"),
        )

    private fun Map<String, Any?>.stringList(name: String): List<String> =
        (this[name] as? Collection<*>)?.map { it.toString() } ?: emptyList()

    private fun GraphNode.toRepository(): DomainRepository = (props + mapOf("id" to id, "key" to key.key)).toRepository()
}
