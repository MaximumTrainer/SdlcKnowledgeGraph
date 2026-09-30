package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.Repository

@Suppress("TooManyFunctions") // The legacy repository surface, plus its lookups (#88).
interface RepositoryUseCase {
    fun registerRepository(repository: Repository): Repository

    fun getRepository(id: String): Repository?

    fun listRepositories(): List<Repository>

    /**
     * The repository holding [providerId] at [provider] (#88), which survives a rename or a transfer
     * where the remote does not; null when none does.
     *
     * @throws com.repodatagraph.domain.exception.InvalidQueryParameterException if the ontology does
     *   not declare [provider]
     */
    fun findByProviderId(
        provider: String,
        providerId: String,
    ): GraphNode?

    /** The repository that was known by [key] before a rename (#88), or null. */
    fun findByPreviousKey(key: String): GraphNode?

    fun deleteRepository(id: String)

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
}
