package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.Pipeline
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.model.Team

interface RepositoryGraphPort {
    fun save(repository: Repository): Repository

    fun findById(id: String): Repository?

    fun findAll(): List<Repository>

    fun delete(id: String)

    fun linkToTeam(
        repoId: String,
        teamId: String,
    )

    fun linkToCloudResource(
        repoId: String,
        cloudResourceId: String,
    )

    fun linkToPipeline(
        repoId: String,
        pipelineId: String,
    )

    fun linkToServiceNowCI(
        repoId: String,
        ciId: String,
    )

    fun addDependency(
        fromRepoId: String,
        toRepoId: String,
    )

    fun findCloudResourcesForRepo(repoId: String): List<CloudResource>

    fun findDependencies(repoId: String): List<Repository>

    fun findDependents(repoId: String): List<Repository>

    fun findDeploymentsForRepo(repoId: String): List<Deployment>

    fun findTeamForRepo(repoId: String): Team?

    /**
     * The configuration item a repository is linked to, as the registry declares it.
     *
     * A [GraphNode] rather than a hand-written type: what a CMDB records about a service is the
     * ontology's business now, and a Kotlin class beside it was a second declaration that could
     * disagree with the first.
     */
    fun findConfigurationItemForRepo(repoId: String): GraphNode?

    fun findPipelinesForRepo(repoId: String): List<Pipeline>
}
