package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.CreateRepositoryRequest
import com.repodatagraph.adapter.`in`.rest.dto.RepositoryResponse
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.GraphNode
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
        val created = nodeUseCase.create(REPOSITORY, propsOf(request))
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

    /**
     * Every repository, or with `url` the one that remote resolves to (#88): a list either way, so the
     * route answers one shape, holding at most one repository when a url is given and none when
     * nothing resolves. A remote a repository had before a rename resolves to it too.
     */
    @GetMapping
    @Operation(summary = "List all registered repositories, or find the one a git remote resolves to, before or after a rename")
    fun listRepositories(
        @RequestParam(required = false) url: String?,
    ): ResponseEntity<List<RepositoryResponse>> {
        if (url == null) return ResponseEntity.ok(repositoryUseCase.listRepositories().map { RepositoryResponse.from(it) })
        return ResponseEntity.ok(listOfNotNull(resolve(url)).map { RepositoryResponse.from(it) })
    }

    /**
     * Lookup by the id the provider gives a repository (#88): GitHub's numeric repository id, which a
     * GitHub App holds and which survives a rename or a transfer between organisations. Neither part
     * can hold a slash, so both travel as path segments.
     */
    @GetMapping("/by-provider/{provider}/{providerId}")
    @Operation(summary = "Find a repository by the id its provider gives it, such as GitHub's repository id")
    fun getRepositoryByProviderId(
        @PathVariable provider: String,
        @PathVariable providerId: String,
    ): ResponseEntity<RepositoryResponse> {
        val node =
            repositoryUseCase.findByProviderId(provider, providerId)
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(RepositoryResponse.from(node))
    }

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
        val node = resolve(key) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(RepositoryResponse.from(node))
    }

    /**
     * The repository [remote] resolves to: the one holding its key now, else the one that held it
     * before a rename (#88). The current holder wins, so a new repository created under a remote an
     * older one has left is found as itself.
     */
    private fun resolve(remote: String): GraphNode? {
        val canonical = gitRemoteParser.parse(remote).key
        return nodeUseCase.get(REPOSITORY, canonical) ?: repositoryUseCase.findByPreviousKey(canonical)
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
        const val REPOSITORY = "Repository"
        const val DEPRECATION_HEADER = "Deprecation"
        const val LINK_HEADER = "Link"
        const val SUCCESSOR_LINK = "</api/v1/nodes/Repository>; rel=\"successor-version\""
    }
}
