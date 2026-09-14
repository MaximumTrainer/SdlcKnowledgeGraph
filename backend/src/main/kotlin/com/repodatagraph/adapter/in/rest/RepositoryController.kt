package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.CreateRepositoryRequest
import com.repodatagraph.adapter.`in`.rest.dto.RepositoryResponse
import com.repodatagraph.domain.identity.GitRemoteParser
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/repositories")
@Tag(name = "Repositories", description = "Git repository management")
class RepositoryController(
    private val repositoryUseCase: RepositoryUseCase,
    private val nodeUseCase: NodeUseCase,
    private val gitRemoteParser: GitRemoteParser,
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
     * The url is passed through as given: the node use case parses and canonicalises it, so this
     * endpoint and `POST /api/v1/nodes/Repository` cannot disagree about what a remote means (#8).
     */
    private fun propsOf(request: CreateRepositoryRequest): Map<String, Any?> =
        mapOf(
            "url" to request.url,
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

    /**
     * Lookup by the key a repository resolves to, rather than by the id the graph assigned.
     *
     * This is what a connector needs. It arrives holding a remote in whatever notation its source
     * system wrote, and has no way to know our id. The key it supplies goes through the same parser
     * the stored key came from, so `Acme/Payments` finds the node stored as
     * `github.com/acme/payments` - otherwise every caller would have to normalise first, which is
     * the duplication this issue exists to remove (#8).
     */
    @GetMapping("/by-key")
    @Operation(summary = "Find a repository by its canonical key, in any remote notation")
    fun getRepositoryByKey(
        @RequestParam key: String,
    ): ResponseEntity<RepositoryResponse> {
        val canonical = gitRemoteParser.parse(key).key
        val node =
            nodeUseCase.get("Repository", canonical)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(RepositoryResponse.from(node))
    }

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

    private companion object {
        const val DEPRECATION_HEADER = "Deprecation"
        const val LINK_HEADER = "Link"
        const val SUCCESSOR_LINK = "</api/v1/nodes/Repository>; rel=\"successor-version\""
    }
}
