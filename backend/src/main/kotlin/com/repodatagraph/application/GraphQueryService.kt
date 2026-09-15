package com.repodatagraph.application

import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.Pipeline
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.model.Team
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import com.repodatagraph.domain.port.out.FactStorePort
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.springframework.stereotype.Service

@Service
class GraphQueryService(
    private val graphPort: RepositoryGraphPort,
    private val factStorePort: FactStorePort,
) : GraphQueryUseCase {
    override fun getCloudResourcesForRepo(repoId: String): List<CloudResource> = graphPort.findCloudResourcesForRepo(repoId)

    override fun getDependencies(repoId: String): List<Repository> = graphPort.findDependencies(repoId)

    override fun getDependents(repoId: String): List<Repository> = graphPort.findDependents(repoId)

    override fun getDeploymentsForRepo(repoId: String): List<Deployment> = graphPort.findDeploymentsForRepo(repoId)

    override fun getAuditEventsForRepo(repoId: String): List<AuditEvent> = factStorePort.queryEvents(repoId)

    override fun getTeamForRepo(repoId: String): Team? = graphPort.findTeamForRepo(repoId)

    override fun getConfigurationItemForRepo(repoId: String): GraphNode? = graphPort.findConfigurationItemForRepo(repoId)

    override fun getPipelinesForRepo(repoId: String): List<Pipeline> = graphPort.findPipelinesForRepo(repoId)

    override fun getImpactAnalysis(repoId: String): Map<String, List<Any>> {
        val dependents = graphPort.findDependents(repoId)
        val cloudResources = graphPort.findCloudResourcesForRepo(repoId)
        val deployments = graphPort.findDeploymentsForRepo(repoId)
        return mapOf(
            "dependents" to dependents,
            "cloudResources" to cloudResources,
            "deployments" to deployments,
        )
    }
}
