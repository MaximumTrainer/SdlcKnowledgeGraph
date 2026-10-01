package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.CandidatePageResponse
import com.repodatagraph.adapter.`in`.rest.dto.CandidateResponse
import com.repodatagraph.adapter.`in`.rest.dto.ManualLinkRequest
import com.repodatagraph.adapter.`in`.rest.dto.OwnerLinkResponse
import com.repodatagraph.adapter.`in`.rest.dto.ResolutionResponse
import com.repodatagraph.adapter.`in`.rest.dto.ResolveRequest
import com.repodatagraph.domain.exception.CandidateDecidedException
import com.repodatagraph.domain.exception.CandidateNotFoundException
import com.repodatagraph.domain.exception.ManualLinkExistsException
import com.repodatagraph.domain.exception.ManualLinkNotFoundException
import com.repodatagraph.domain.model.CandidateQuery
import com.repodatagraph.domain.model.CandidateStatus
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.port.`in`.LinkUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Cloud-to-repository link resolution and its review (#28).
 *
 * Listing candidates is a read, and needs graph:read; everything else changes the graph and needs
 * graph:write, and is refused on a read-only deployment ([com.repodatagraph.adapter.in.security.ScopePolicy]).
 */
@RestController
@RequestMapping("/api/v1/links")
@Tag(name = "Links", description = "Which repository owns each cloud resource, inferred by rules and reviewed by people")
class LinkController(
    private val links: LinkUseCase,
) {
    @PostMapping("/resolve")
    @Operation(
        operationId = "resolveLinks",
        summary = "Start a link resolution, of every resource or of a scope, as a sync run of link-engine",
    )
    fun resolve(
        @RequestBody(required = false) request: ResolveRequest?,
    ): ResponseEntity<ResolutionResponse> {
        val scope = request?.scope?.toScope() ?: LinkScope()
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ResolutionResponse.from(links.resolve(scope)))
    }

    @GetMapping("/candidates")
    @Operation(operationId = "listLinkCandidates", summary = "List candidate links, strongest first; the open ones unless asked")
    @Suppress("LongParameterList")
    fun candidates(
        @Parameter(description = "Comma-separated: pending, conflict, rejected, superseded, accepted. Default pending,conflict")
        @RequestParam(required = false)
        status: String?,
        @Parameter(description = "Only resources in this cloud: aws, azure or gcp")
        @RequestParam(required = false)
        provider: String?,
        @Parameter(description = "Only candidates at least this confident, 0 to 1")
        @RequestParam(required = false)
        minConfidence: Double?,
        @Parameter(description = "Text in the resource's key or name, or the repository's key, in any case")
        @RequestParam(required = false)
        q: String?,
        @Parameter(description = "Page number, from 0")
        @RequestParam(defaultValue = "0")
        page: Int,
        @Parameter(description = "Candidates per page, 1 to ${CandidateQuery.MAX_SIZE}")
        @RequestParam(defaultValue = "${CandidateQuery.DEFAULT_SIZE}")
        size: Int,
    ): CandidatePageResponse {
        val statuses =
            status
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map(CandidateStatus::fromWireName)
                ?.toSet()
                ?.ifEmpty { null }
                ?: CandidateStatus.OPEN
        val query =
            CandidateQuery(
                statuses = statuses,
                provider = provider?.takeIf { it.isNotBlank() },
                minConfidence = minConfidence,
                search = q?.takeIf { it.isNotBlank() },
                page = page,
                size = size,
            )
        return CandidatePageResponse.from(links.candidates(query))
    }

    @PostMapping("/candidates/{id}/accept")
    @Operation(operationId = "acceptLinkCandidate", summary = "Accept a candidate: it becomes a manual OWNS_RESOURCE")
    fun accept(
        @PathVariable id: String,
    ): OwnerLinkResponse = OwnerLinkResponse.from(links.accept(id))

    @PostMapping("/candidates/{id}/reject")
    @Operation(operationId = "rejectLinkCandidate", summary = "Reject a candidate: it is not proposed again on the same evidence")
    fun reject(
        @PathVariable id: String,
    ): CandidateResponse = CandidateResponse.from(links.reject(id))

    @PostMapping("/manual")
    @Operation(operationId = "createManualLink", summary = "State that a repository owns a cloud resource")
    fun link(
        @RequestBody request: ManualLinkRequest,
    ): ResponseEntity<OwnerLinkResponse> {
        val resourceKey = request.resourceKey?.takeIf { it.isNotBlank() }
        val repoKey = request.repoKey?.takeIf { it.isNotBlank() }
        require(resourceKey != null && repoKey != null) { "a manual link needs resourceKey and repoKey" }
        return ResponseEntity.status(HttpStatus.CREATED).body(OwnerLinkResponse.from(links.link(resourceKey, repoKey)))
    }

    @DeleteMapping("/manual")
    @Operation(operationId = "closeManualLink", summary = "Close a manual link; the edge keeps its history with validTo set")
    fun unlink(
        @RequestParam resourceKey: String,
        @RequestParam repoKey: String,
    ): ResponseEntity<Void> {
        links.unlink(resourceKey, repoKey)
        return ResponseEntity.noContent().build()
    }
}

@RestControllerAdvice
class LinkRestExceptionHandler {
    @ExceptionHandler(CandidateNotFoundException::class)
    fun onCandidateNotFound(exception: CandidateNotFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "candidate not found", "id" to exception.id))

    @ExceptionHandler(CandidateDecidedException::class)
    fun onCandidateDecided(exception: CandidateDecidedException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "candidate already decided", "id" to exception.id, "status" to exception.status.wireName),
        )

    @ExceptionHandler(ManualLinkExistsException::class)
    fun onManualLinkExists(exception: ManualLinkExistsException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf("error" to "manual link exists", "resourceKey" to exception.resourceKey, "repoKey" to exception.repoKey),
        )

    @ExceptionHandler(ManualLinkNotFoundException::class)
    fun onManualLinkNotFound(exception: ManualLinkNotFoundException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf("error" to "manual link not found", "resourceKey" to exception.resourceKey, "repoKey" to exception.repoKey),
        )
}
