package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.Pipeline
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.model.Team

interface GraphQueryUseCase {
    fun getCloudResourcesForRepo(repoId: String): List<CloudResource>

    fun getDependencies(repoId: String): List<Repository>

    fun getDependents(repoId: String): List<Repository>

    fun getDeploymentsForRepo(repoId: String): List<Deployment>

    fun getAuditEventsForRepo(repoId: String): List<AuditEvent>

    fun getTeamForRepo(repoId: String): Team?

    fun getConfigurationItemForRepo(repoId: String): GraphNode?

    fun getPipelinesForRepo(repoId: String): List<Pipeline>

    fun getImpactAnalysis(repoId: String): Map<String, List<Any>>
}
