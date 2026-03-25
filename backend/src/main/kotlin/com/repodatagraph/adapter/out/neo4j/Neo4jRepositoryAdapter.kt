package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.adapter.out.neo4j.node.*
import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.Pipeline
import com.repodatagraph.domain.model.Repository as DomainRepository
import com.repodatagraph.domain.model.ServiceNowCI
import com.repodatagraph.domain.model.Team
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository

@Repository
interface RepositoryNodeRepository : Neo4jRepository<RepositoryNode, String>

@Repository
interface TeamNodeRepository : Neo4jRepository<TeamNode, String>

@Repository
interface CloudResourceNodeRepository : Neo4jRepository<CloudResourceNode, String>

@Repository
interface PipelineNodeRepository : Neo4jRepository<PipelineNode, String>

@Repository
interface ServiceNowCINodeRepository : Neo4jRepository<ServiceNowCINode, String>

@Repository
interface ArtifactNodeRepository : Neo4jRepository<ArtifactNode, String>

@Repository
interface DeploymentNodeRepository : Neo4jRepository<DeploymentNode, String>

@Component
class Neo4jRepositoryAdapter(
    private val repoNodeRepo: RepositoryNodeRepository,
    private val teamNodeRepo: TeamNodeRepository,
    private val cloudResourceNodeRepo: CloudResourceNodeRepository,
    private val pipelineNodeRepo: PipelineNodeRepository,
    private val serviceNowCINodeRepo: ServiceNowCINodeRepository,
    private val artifactNodeRepo: ArtifactNodeRepository,
    private val deploymentNodeRepo: DeploymentNodeRepository,
    private val neo4jClient: Neo4jClient
) : RepositoryGraphPort {

    override fun save(repository: DomainRepository): DomainRepository {
        val node = RepositoryNode(
            id = repository.id,
            orgRepo = repository.orgRepo,
            defaultBranch = repository.defaultBranch,
            topics = repository.topics,
            codeowners = repository.codeowners,
            serviceId = repository.serviceId,
            language = repository.language,
            description = repository.description
        )
        repoNodeRepo.save(node)
        return repository
    }

    override fun findById(id: String): DomainRepository? =
        repoNodeRepo.findById(id).map { it.toDomain() }.orElse(null)

    override fun findAll(): List<DomainRepository> =
        repoNodeRepo.findAll().map { it.toDomain() }

    override fun delete(id: String) = repoNodeRepo.deleteById(id)

    override fun linkToTeam(repoId: String, teamId: String) {
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId}), (t:Team {id: \$teamId}) MERGE (r)-[:OWNED_BY]->(t)"
        ).bind(repoId).to("repoId").bind(teamId).to("teamId").run()
    }

    override fun linkToCloudResource(repoId: String, cloudResourceId: String) {
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId}), (c:CloudResource {id: \$cloudResourceId}) MERGE (r)-[:OWNS_RESOURCE]->(c)"
        ).bind(repoId).to("repoId").bind(cloudResourceId).to("cloudResourceId").run()
    }

    override fun linkToPipeline(repoId: String, pipelineId: String) {
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId}), (p:Pipeline {id: \$pipelineId}) MERGE (r)-[:HAS_PIPELINE]->(p)"
        ).bind(repoId).to("repoId").bind(pipelineId).to("pipelineId").run()
    }

    override fun linkToServiceNowCI(repoId: String, ciId: String) {
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId}), (s:ServiceNowCI {id: \$ciId}) MERGE (r)-[:RELATES_TO_CI]->(s)"
        ).bind(repoId).to("repoId").bind(ciId).to("ciId").run()
    }

    override fun addDependency(fromRepoId: String, toRepoId: String) {
        neo4jClient.query(
            "MATCH (r1:Repository {id: \$fromId}), (r2:Repository {id: \$toId}) MERGE (r1)-[:DEPENDS_ON]->(r2)"
        ).bind(fromRepoId).to("fromId").bind(toRepoId).to("toId").run()
    }

    override fun findCloudResourcesForRepo(repoId: String): List<CloudResource> =
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId})-[:OWNS_RESOURCE]->(c:CloudResource) RETURN c"
        ).bind(repoId).to("repoId")
            .fetchAs(CloudResourceNode::class.java)
            .mappedBy { _, record ->
                val c = record["c"].asMap()
                CloudResourceNode(
                    id = c["id"].toString(),
                    provider = c["provider"].toString(),
                    resourceType = c["resourceType"].toString(),
                    name = c["name"].toString(),
                    region = c["region"]?.toString(),
                    repoId = c["repoId"]?.toString()
                )
            }
            .all().map { it.toDomain() }

    override fun findDependencies(repoId: String): List<DomainRepository> =
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId})-[:DEPENDS_ON]->(dep:Repository) RETURN dep"
        ).bind(repoId).to("repoId")
            .fetchAs(RepositoryNode::class.java)
            .mappedBy { _, record ->
                val n = record["dep"].asMap()
                @Suppress("UNCHECKED_CAST")
                RepositoryNode(
                    id = n["id"].toString(),
                    orgRepo = n["orgRepo"].toString(),
                    defaultBranch = n["defaultBranch"]?.toString() ?: "main",
                    topics = (n["topics"] as? List<String>) ?: emptyList(),
                    codeowners = (n["codeowners"] as? List<String>) ?: emptyList(),
                    serviceId = n["serviceId"]?.toString(),
                    language = n["language"]?.toString(),
                    description = n["description"]?.toString()
                )
            }
            .all().map { it.toDomain() }

    override fun findDependents(repoId: String): List<DomainRepository> =
        neo4jClient.query(
            "MATCH (dep:Repository)-[:DEPENDS_ON]->(r:Repository {id: \$repoId}) RETURN dep"
        ).bind(repoId).to("repoId")
            .fetchAs(RepositoryNode::class.java)
            .mappedBy { _, record ->
                val n = record["dep"].asMap()
                @Suppress("UNCHECKED_CAST")
                RepositoryNode(
                    id = n["id"].toString(),
                    orgRepo = n["orgRepo"].toString(),
                    defaultBranch = n["defaultBranch"]?.toString() ?: "main",
                    topics = (n["topics"] as? List<String>) ?: emptyList(),
                    codeowners = (n["codeowners"] as? List<String>) ?: emptyList(),
                    serviceId = n["serviceId"]?.toString(),
                    language = n["language"]?.toString(),
                    description = n["description"]?.toString()
                )
            }
            .all().map { it.toDomain() }

    override fun findDeploymentsForRepo(repoId: String): List<Deployment> =
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId})<-[:BUILT_FROM]-(a:Artifact)-[:DEPLOYED_TO]->(d:Deployment) RETURN d"
        ).bind(repoId).to("repoId")
            .fetchAs(DeploymentNode::class.java)
            .mappedBy { _, record ->
                val n = record["d"].asMap()
                DeploymentNode(
                    id = n["id"].toString(),
                    artifactId = n["artifactId"].toString(),
                    environmentId = n["environmentId"].toString(),
                    deployedBy = n["deployedBy"]?.toString(),
                    status = n["status"]?.toString() ?: "SUCCESS"
                )
            }
            .all().map { it.toDomain() }

    override fun findTeamForRepo(repoId: String): Team? =
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId})-[:OWNED_BY]->(t:Team) RETURN t"
        ).bind(repoId).to("repoId")
            .fetchAs(TeamNode::class.java)
            .mappedBy { _, record ->
                val n = record["t"].asMap()
                TeamNode(
                    id = n["id"].toString(),
                    name = n["name"].toString(),
                    email = n["email"]?.toString()
                )
            }
            .one().map { it.toDomain() }.orElse(null)

    override fun findServiceNowCIForRepo(repoId: String): ServiceNowCI? =
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId})-[:RELATES_TO_CI]->(s:ServiceNowCI) RETURN s"
        ).bind(repoId).to("repoId")
            .fetchAs(ServiceNowCINode::class.java)
            .mappedBy { _, record ->
                val n = record["s"].asMap()
                ServiceNowCINode(
                    id = n["id"].toString(),
                    ciName = n["ciName"].toString(),
                    serviceId = n["serviceId"].toString(),
                    repoId = n["repoId"]?.toString()
                )
            }
            .one().map { it.toDomain() }.orElse(null)

    override fun findPipelinesForRepo(repoId: String): List<Pipeline> =
        neo4jClient.query(
            "MATCH (r:Repository {id: \$repoId})-[:HAS_PIPELINE]->(p:Pipeline) RETURN p"
        ).bind(repoId).to("repoId")
            .fetchAs(PipelineNode::class.java)
            .mappedBy { _, record ->
                val n = record["p"].asMap()
                PipelineNode(
                    id = n["id"].toString(),
                    name = n["name"].toString(),
                    provider = n["provider"].toString(),
                    repoId = n["repoId"].toString(),
                    lastRunStatus = n["lastRunStatus"]?.toString()
                )
            }
            .all().map { it.toDomain() }

    private fun RepositoryNode.toDomain() = DomainRepository(
        id = id, orgRepo = orgRepo, defaultBranch = defaultBranch,
        topics = topics, codeowners = codeowners, serviceId = serviceId,
        language = language, description = description
    )

    private fun TeamNode.toDomain() = Team(id = id, name = name, email = email)

    private fun CloudResourceNode.toDomain() = CloudResource(
        id = id, provider = provider, resourceType = resourceType,
        name = name, region = region, repoId = repoId
    )

    private fun DeploymentNode.toDomain() = Deployment(
        id = id, artifactId = artifactId, environmentId = environmentId,
        deployedAt = deployedAt, deployedBy = deployedBy, status = status
    )

    private fun ServiceNowCINode.toDomain() = ServiceNowCI(
        id = id, ciName = ciName, serviceId = serviceId, repoId = repoId
    )

    private fun PipelineNode.toDomain() = Pipeline(
        id = id, name = name, provider = provider, repoId = repoId, lastRunStatus = lastRunStatus
    )
}
