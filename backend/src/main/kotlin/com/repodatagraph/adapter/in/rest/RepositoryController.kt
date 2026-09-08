package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.CreateRepositoryRequest
import com.repodatagraph.adapter.`in`.rest.dto.RepositoryResponse
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.`in`.NodeUseCase
import com.repodatagraph.domain.port.`in`.RepositoryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/repositories")
@Tag(name = "Repositories", description = "Git repository management")
class RepositoryController(
    private val repositoryUseCase: RepositoryUseCase,
    private val nodeUseCase: NodeUseCase,
    private val identityResolver: IdentityResolver,
) {
    /**
     * Superseded by `POST /api/v1/nodes/Repository`, and kept only so existing callers keep working.
     *
     * It delegates to the same use case rather than keeping a second write path, so registry
     * validation, derived identity and provenance apply here too. The `Deprecation` header and the
     * `Link` to the successor are how a caller finds out without reading the release notes.
     */
    @PostMapping
    @Operation(summary = "Register a repository in the graph", deprecated = true)
    fun registerRepository(
        @RequestBody request: CreateRepositoryRequest,
    ): ResponseEntity<RepositoryResponse> {
        val created = nodeUseCase.create("Repository", propsOf(request))
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .header(DEPRECATION_HEADER, "true")
            .header(LINK_HEADER, SUCCESSOR_LINK)
            .body(RepositoryResponse.from(created))
    }

    /**
     * The old endpoint accepts `org/repo`, which is a shorthand rather than a remote. It is expanded
     * to the canonical URL here so the node is stored with a real one, instead of the graph keeping a
     * fragment that no connector could later match. #8 replaces this with a full parser.
     */
    private fun propsOf(request: CreateRepositoryRequest): Map<String, Any?> =
        mapOf(
            "url" to "https://" + identityResolver.repositoryKey(mapOf("url" to request.orgRepo)),
            "orgRepo" to request.orgRepo,
            "defaultBranch" to request.defaultBranch,
            "topics" to request.topics,
            "codeowners" to request.codeowners,
            "serviceId" to request.serviceId,
            "language" to request.language,
            "description" to request.description,
        ).filterValues { it != null }

    @GetMapping
    @Operation(summary = "List all registered repositories")
    fun listRepositories(): ResponseEntity<List<RepositoryResponse>> =
        ResponseEntity.ok(repositoryUseCase.listRepositories().map { RepositoryResponse.from(it) })

    @GetMapping("/{id}")
    @Operation(summary = "Get a repository by ID")
    fun getRepository(
        @PathVariable id: String,
    ): ResponseEntity<RepositoryResponse> {
        val repo =
            repositoryUseCase.getRepository(id)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(RepositoryResponse.from(repo))
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a repository from the graph")
    fun deleteRepository(
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        repositoryUseCase.deleteRepository(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{repoId}/teams/{teamId}")
    @Operation(summary = "Link repository to a team")
    fun linkToTeam(
        @PathVariable repoId: String,
        @PathVariable teamId: String,
    ): ResponseEntity<Void> {
        repositoryUseCase.linkToTeam(repoId, teamId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{repoId}/cloud-resources/{resourceId}")
    @Operation(summary = "Link repository to a cloud resource")
    fun linkToCloudResource(
        @PathVariable repoId: String,
        @PathVariable resourceId: String,
    ): ResponseEntity<Void> {
        repositoryUseCase.linkToCloudResource(repoId, resourceId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{repoId}/pipelines/{pipelineId}")
    @Operation(summary = "Link repository to a pipeline")
    fun linkToPipeline(
        @PathVariable repoId: String,
        @PathVariable pipelineId: String,
    ): ResponseEntity<Void> {
        repositoryUseCase.linkToPipeline(repoId, pipelineId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{repoId}/servicenow/{ciId}")
    @Operation(summary = "Link repository to a ServiceNow CI item")
    fun linkToServiceNowCI(
        @PathVariable repoId: String,
        @PathVariable ciId: String,
    ): ResponseEntity<Void> {
        repositoryUseCase.linkToServiceNowCI(repoId, ciId)
        return ResponseEntity.ok().build()
    }

    @PostMapping("/{repoId}/dependencies/{depRepoId}")
    @Operation(summary = "Add a dependency between repositories")
    fun addDependency(
        @PathVariable repoId: String,
        @PathVariable depRepoId: String,
    ): ResponseEntity<Void> {
        repositoryUseCase.addDependency(repoId, depRepoId)
        return ResponseEntity.ok().build()
    }

    private companion object {
        const val DEPRECATION_HEADER = "Deprecation"
        const val LINK_HEADER = "Link"
        const val SUCCESSOR_LINK = "</api/v1/nodes/Repository>; rel=\"successor-version\""
    }
}
