package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.Pipeline
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.model.Team

interface GraphQueryUseCase : ImpactUseCase {
    fun getCloudResourcesForRepo(repoId: String): List<CloudResource>

    fun getDependencies(repoId: String): List<Repository>

    fun getDependents(repoId: String): List<Repository>

    fun getDeploymentsForRepo(repoId: String): List<Deployment>

    fun getAuditEventsForRepo(repoId: String): List<AuditEvent>

    fun getTeamForRepo(repoId: String): Team?

    fun getConfigurationItemForRepo(repoId: String): GraphNode?

    fun getPipelinesForRepo(repoId: String): List<Pipeline>

    /**
     * The repository-shaped impact of the deprecated `/repositories/{repoId}/impact` (#21): direct
     * dependents, owned resources and the deployments of what the repository builds, answered from
     * [impact] so the two cannot disagree.
     */
    fun getImpactAnalysis(repoId: String): Map<String, List<Any>>
}
