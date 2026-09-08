package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.EdgeRequest
import com.repodatagraph.domain.port.`in`.EdgeUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The repository-centric link endpoints, superseded by `POST /api/v1/edges` (#5).
 *
 * They live apart from [RepositoryController] so that the superseded surface is visible as such and
 * can be deleted in one move rather than unpicked from the endpoints that are still current.
 *
 * They delegate to the same use case, so registry validation, endpoint checking and provenance apply
 * to them too rather than existing twice, and they carry the headers that say where to go instead.
 *
 * They are, in practice, unreachable for repositories. A Repository id is
 * `Repository:host/org/name`, and that cannot sit in a mid-path segment, so anything with a modern
 * identity has to use the successor. That is an argument for deleting these rather than keeping them.
 */
@RestController
@RequestMapping("/api/v1/repositories")
@Tag(name = "Repositories (deprecated links)", description = "Superseded by /api/v1/edges")
class DeprecatedRepositoryLinkController(
    private val edgeUseCase: EdgeUseCase,
) {
    @PostMapping("/{repoId}/teams/{teamId}")
    @Operation(summary = "Link repository to a team", deprecated = true)
    fun linkToTeam(
        @PathVariable repoId: String,
        @PathVariable teamId: String,
    ): ResponseEntity<Void> = link("OWNED_BY", repoId, "Repository", teamId, "Team")

    @PostMapping("/{repoId}/cloud-resources/{resourceId}")
    @Operation(summary = "Link repository to a cloud resource", deprecated = true)
    fun linkToCloudResource(
        @PathVariable repoId: String,
        @PathVariable resourceId: String,
    ): ResponseEntity<Void> = link("OWNS_RESOURCE", repoId, "Repository", resourceId, "CloudResource")

    @PostMapping("/{repoId}/pipelines/{pipelineId}")
    @Operation(summary = "Link repository to a pipeline", deprecated = true)
    fun linkToPipeline(
        @PathVariable repoId: String,
        @PathVariable pipelineId: String,
    ): ResponseEntity<Void> = link("HAS_PIPELINE", repoId, "Repository", pipelineId, "Pipeline")

    @PostMapping("/{repoId}/servicenow/{ciId}")
    @Operation(summary = "Link repository to a ServiceNow CI item", deprecated = true)
    fun linkToServiceNowCI(
        @PathVariable repoId: String,
        @PathVariable ciId: String,
    ): ResponseEntity<Void> = link("RELATES_TO_CI", repoId, "Repository", ciId, "ConfigurationItem")

    /**
     * DEPENDS_ON now requires the kind of dependency, because "what depends on this" is not much of
     * an answer without it. This endpoint has no way to know, so it takes the kind as a parameter and
     * assumes a library dependency when the caller says nothing — the commonest case, and the one an
     * old caller was implicitly recording.
     */
    @PostMapping("/{repoId}/dependencies/{depRepoId}")
    @Operation(summary = "Add a dependency between repositories", deprecated = true)
    fun addDependency(
        @PathVariable repoId: String,
        @PathVariable depRepoId: String,
        @RequestParam(defaultValue = "library") kind: String,
    ): ResponseEntity<Void> = link("DEPENDS_ON", repoId, "Repository", depRepoId, "Repository", mapOf("kind" to kind))

    private fun link(
        edgeType: String,
        fromId: String,
        fromType: String,
        toId: String,
        toType: String,
        props: Map<String, Any?> = emptyMap(),
    ): ResponseEntity<Void> {
        edgeUseCase.create(
            EdgeRequest(
                type = edgeType,
                fromId = qualified(fromId, fromType),
                toId = qualified(toId, toType),
                props = props,
            ),
        )
        return ResponseEntity
            .ok()
            .header(DEPRECATION_HEADER, "true")
            .header(LINK_HEADER, EDGES_LINK)
            .build()
    }

    /** These endpoints predate typed ids, so a bare key is accepted and qualified here. */
    private fun qualified(
        idOrKey: String,
        type: String,
    ): String = if (idOrKey.startsWith("$type:")) idOrKey else "$type:$idOrKey"

    private companion object {
        const val DEPRECATION_HEADER = "Deprecation"
        const val LINK_HEADER = "Link"
        const val EDGES_LINK = "</api/v1/edges>; rel=\"successor-version\""
    }
}
