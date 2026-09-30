package com.repodatagraph.application

import com.repodatagraph.application.impact.ImpactAnalysisService
import com.repodatagraph.application.impact.LegacyImpactView
import com.repodatagraph.application.neighbourhood.NeighbourhoodService
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactSpec
import com.repodatagraph.domain.model.Pipeline
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.model.Team
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import com.repodatagraph.domain.port.`in`.ImpactUseCase
import com.repodatagraph.domain.port.`in`.NeighbourhoodUseCase
import com.repodatagraph.domain.port.out.FactStorePort
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.springframework.stereotype.Service

@Service
class GraphQueryService(
    private val graphPort: RepositoryGraphPort,
    private val factStorePort: FactStorePort,
    private val impactAnalysis: ImpactAnalysisService,
    neighbourhoods: NeighbourhoodService,
) : GraphQueryUseCase,
    // The multi-hop questions (#21) are the impact service's; this answers them by delegating to it.
    ImpactUseCase by impactAnalysis,
    // And the graph view's neighbourhood (#9) is the neighbourhood service's.
    NeighbourhoodUseCase by neighbourhoods {
    override fun getCloudResourcesForRepo(repoId: String): List<CloudResource> = graphPort.findCloudResourcesForRepo(repoId)

    override fun getDependencies(repoId: String): List<Repository> = graphPort.findDependencies(repoId)

    override fun getDependents(repoId: String): List<Repository> = graphPort.findDependents(repoId)

    override fun getDeploymentsForRepo(repoId: String): List<Deployment> = graphPort.findDeploymentsForRepo(repoId)

    override fun getAuditEventsForRepo(repoId: String): List<AuditEvent> = factStorePort.queryEvents(repoId)

    override fun getTeamForRepo(repoId: String): Team? = graphPort.findTeamForRepo(repoId)

    override fun getConfigurationItemForRepo(repoId: String): GraphNode? = graphPort.findConfigurationItemForRepo(repoId)

    override fun getPipelinesForRepo(repoId: String): List<Pipeline> = graphPort.findPipelinesForRepo(repoId)

    /**
     * Two hops, every confidence: far enough to reach a deployment through the artifact a repository
     * builds, and no filter, because this endpoint never had one. A repository that is not there has
     * always answered three empty lists rather than 404, and still does.
     */
    override fun getImpactAnalysis(repoId: String): Map<String, List<Any>> {
        val key = LegacyImpactView.repositoryKey(repoId)
        val result =
            try {
                impactAnalysis.impact(ImpactSpec(key, depth = LEGACY_DEPTH, minConfidence = 0.0))
            } catch (_: NodeNotFoundException) {
                return LegacyImpactView.EMPTY
            }
        return LegacyImpactView.of(result)
    }

    private companion object {
        const val LEGACY_DEPTH = 2
    }
}
