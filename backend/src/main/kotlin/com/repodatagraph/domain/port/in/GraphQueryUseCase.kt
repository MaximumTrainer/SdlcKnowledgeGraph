package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.*

interface GraphQueryUseCase {
    fun getCloudResourcesForRepo(repoId: String): List<CloudResource>
    fun getDependencies(repoId: String): List<Repository>
    fun getDependents(repoId: String): List<Repository>
    fun getDeploymentsForRepo(repoId: String): List<Deployment>
    fun getAuditEventsForRepo(repoId: String): List<AuditEvent>
    fun getTeamForRepo(repoId: String): Team?
    fun getServiceNowCIForRepo(repoId: String): ServiceNowCI?
    fun getPipelinesForRepo(repoId: String): List<Pipeline>
    fun getImpactAnalysis(repoId: String): Map<String, List<Any>>
}
