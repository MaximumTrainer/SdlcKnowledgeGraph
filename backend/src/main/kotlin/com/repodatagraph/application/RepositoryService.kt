package com.repodatagraph.application

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.`in`.RepositoryUseCase
import com.repodatagraph.domain.port.out.FactStorePort
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
@Suppress("TooManyFunctions") // One per RepositoryUseCase operation.
class RepositoryService(
    private val graphPort: RepositoryGraphPort,
    private val factStorePort: FactStorePort,
    private val graphStore: GraphStore,
    private val registry: OntologyRegistry,
) : RepositoryUseCase {
    override fun registerRepository(repository: Repository): Repository {
        val saved = graphPort.save(repository)
        factStorePort.recordEvent(
            AuditEvent(
                id = UUID.randomUUID().toString(),
                eventType = "REPOSITORY_REGISTERED",
                repoId = saved.id,
                actor = "system",
                timestamp = Instant.now(),
                details = mapOf("url" to saved.url),
            ),
        )
        return saved
    }

    override fun getRepository(id: String): Repository? = graphPort.findById(id)

    override fun listRepositories(): List<Repository> = graphPort.findAll()

    /** The provider is read in lower case and checked against the values the ontology declares for it. */
    override fun findByProviderId(
        provider: String,
        providerId: String,
    ): GraphNode? {
        val normalised = provider.trim().lowercase()
        val allowed =
            registry
                .nodeType(REPOSITORY)
                ?.property(PROVIDER)
                ?.enum
                .orEmpty()
        if (normalised !in allowed) {
            throw InvalidQueryParameterException(PROVIDER, "provider must be one of ${allowed.joinToString()}")
        }
        return graphStore.findNodeByAlias(REPOSITORY, mapOf(PROVIDER to normalised, PROVIDER_ID to providerId.trim()))
    }

    override fun findByPreviousKey(key: String): GraphNode? = graphStore.findNodeByPreviousKey(NodeKey(REPOSITORY, key))

    override fun deleteRepository(id: String) {
        graphPort.delete(id)
        factStorePort.recordEvent(
            AuditEvent(
                id = UUID.randomUUID().toString(),
                eventType = "REPOSITORY_DELETED",
                repoId = id,
                actor = "system",
                timestamp = Instant.now(),
            ),
        )
    }

    override fun linkToTeam(
        repoId: String,
        teamId: String,
    ) = graphPort.linkToTeam(repoId, teamId)

    override fun linkToCloudResource(
        repoId: String,
        cloudResourceId: String,
    ) = graphPort.linkToCloudResource(repoId, cloudResourceId)

    override fun linkToPipeline(
        repoId: String,
        pipelineId: String,
    ) = graphPort.linkToPipeline(repoId, pipelineId)

    override fun linkToServiceNowCI(
        repoId: String,
        ciId: String,
    ) = graphPort.linkToServiceNowCI(repoId, ciId)

    override fun addDependency(
        fromRepoId: String,
        toRepoId: String,
    ) = graphPort.addDependency(fromRepoId, toRepoId)

    private companion object {
        const val REPOSITORY = "Repository"
        const val PROVIDER = "provider"
        const val PROVIDER_ID = "providerId"
    }
}
