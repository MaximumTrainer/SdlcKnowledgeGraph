package com.repodatagraph.application

import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.port.`in`.RepositoryUseCase
import com.repodatagraph.domain.port.out.FactStorePort
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class RepositoryService(
    private val graphPort: RepositoryGraphPort,
    private val factStorePort: FactStorePort,
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
}
