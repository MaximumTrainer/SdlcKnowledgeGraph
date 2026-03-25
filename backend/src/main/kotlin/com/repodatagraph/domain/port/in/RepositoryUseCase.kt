package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.Repository

interface RepositoryUseCase {
    fun registerRepository(repository: Repository): Repository
    fun getRepository(id: String): Repository?
    fun listRepositories(): List<Repository>
    fun deleteRepository(id: String)
    fun linkToTeam(repoId: String, teamId: String)
    fun linkToCloudResource(repoId: String, cloudResourceId: String)
    fun linkToPipeline(repoId: String, pipelineId: String)
    fun linkToServiceNowCI(repoId: String, ciId: String)
    fun addDependency(fromRepoId: String, toRepoId: String)
}
