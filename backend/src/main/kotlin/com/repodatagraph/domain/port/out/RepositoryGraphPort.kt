package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.*

interface RepositoryGraphPort {
    fun save(repository: Repository): Repository
    fun findById(id: String): Repository?
    fun findAll(): List<Repository>
    fun delete(id: String)
    fun linkToTeam(repoId: String, teamId: String)
    fun linkToCloudResource(repoId: String, cloudResourceId: String)
    fun linkToPipeline(repoId: String, pipelineId: String)
    fun linkToServiceNowCI(repoId: String, ciId: String)
    fun addDependency(fromRepoId: String, toRepoId: String)
    fun findCloudResourcesForRepo(repoId: String): List<CloudResource>
    fun findDependencies(repoId: String): List<Repository>
    fun findDependents(repoId: String): List<Repository>
    fun findDeploymentsForRepo(repoId: String): List<Deployment>
    fun findTeamForRepo(repoId: String): Team?
    fun findServiceNowCIForRepo(repoId: String): ServiceNowCI?
    fun findPipelinesForRepo(repoId: String): List<Pipeline>
}
