package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.CreateRepositoryRequest
import com.repodatagraph.adapter.`in`.rest.dto.LinkRequest
import com.repodatagraph.adapter.`in`.rest.dto.RepositoryResponse
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.port.`in`.RepositoryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/repositories")
@Tag(name = "Repositories", description = "Git repository management")
class RepositoryController(private val repositoryUseCase: RepositoryUseCase) {

    @PostMapping
    @Operation(summary = "Register a repository in the graph")
    fun registerRepository(@RequestBody request: CreateRepositoryRequest): ResponseEntity<RepositoryResponse> {
        val repository = Repository(
            id = UUID.randomUUID().toString(),
            orgRepo = request.orgRepo,
            defaultBranch = request.defaultBranch,
            topics = request.topics,
            codeowners = request.codeowners,
            serviceId = request.serviceId,
            language = request.language,
            description = request.description
        )
        val saved = repositoryUseCase.registerRepository(repository)
        return ResponseEntity.status(HttpStatus.CREATED).body(RepositoryResponse.from(saved))
    }

    @GetMapping
    @Operation(summary = "List all registered repositories")
    fun listRepositories(): ResponseEntity<List<RepositoryResponse>> =
        ResponseEntity.ok(repositoryUseCase.listRepositories().map { RepositoryResponse.from(it) })

    @GetMapping("/{id}")
    @Operation(summary = "Get a repository by ID")
    fun getRepository(@PathVariable id: String): ResponseEntity<RepositoryResponse> {
        val repo = repositoryUseCase.getRepository(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(RepositoryResponse.from(repo))
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a repository from the graph")
    fun deleteRepository(@PathVariable id: String): ResponseEntity<Void> {
        repositoryUseCase.deleteRepository(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{repoId}/teams/{teamId}")
    @Operation(summary = "Link repository to a team")
    fun linkToTeam(@PathVariable repoId: String, @PathVariable teamId: String): ResponseEntity<Void> {
        repositoryUseCase.linkToTeam(repoId, teamId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{repoId}/cloud-resources/{resourceId}")
    @Operation(summary = "Link repository to a cloud resource")
    fun linkToCloudResource(@PathVariable repoId: String, @PathVariable resourceId: String): ResponseEntity<Void> {
        repositoryUseCase.linkToCloudResource(repoId, resourceId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{repoId}/pipelines/{pipelineId}")
    @Operation(summary = "Link repository to a pipeline")
    fun linkToPipeline(@PathVariable repoId: String, @PathVariable pipelineId: String): ResponseEntity<Void> {
        repositoryUseCase.linkToPipeline(repoId, pipelineId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{repoId}/servicenow/{ciId}")
    @Operation(summary = "Link repository to a ServiceNow CI item")
    fun linkToServiceNowCI(@PathVariable repoId: String, @PathVariable ciId: String): ResponseEntity<Void> {
        repositoryUseCase.linkToServiceNowCI(repoId, ciId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{repoId}/dependencies/{depRepoId}")
    @Operation(summary = "Add a dependency between repositories")
    fun addDependency(@PathVariable repoId: String, @PathVariable depRepoId: String): ResponseEntity<Void> {
        repositoryUseCase.addDependency(repoId, depRepoId)
        return ResponseEntity.ok().build()
    }
}
