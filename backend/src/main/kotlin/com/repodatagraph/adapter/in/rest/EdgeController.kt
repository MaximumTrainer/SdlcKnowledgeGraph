package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.EdgeListResponse
import com.repodatagraph.adapter.`in`.rest.dto.EdgeRequestBody
import com.repodatagraph.adapter.`in`.rest.dto.EdgeResponse
import com.repodatagraph.adapter.`in`.rest.dto.EdgeViewResponse
import com.repodatagraph.adapter.`in`.rest.dto.ProvenanceRequest
import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.domain.exception.EdgeValidationException
import com.repodatagraph.domain.exception.PropertyError
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.EdgeRequest
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.asOfParameter
import com.repodatagraph.domain.port.`in`.EdgeUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * One surface for every relationship the registry declares.
 *
 * Node ids contain slashes, so they travel in the body or as query parameters rather than as path
 * segments: an encoded slash in a path is rejected by the servlet container, and enabling it would
 * open a path-traversal surface for the sake of prettier URLs.
 *
 * That applies to the per-node listing too, which is why it is `GET /api/v1/edges?nodeId=` rather
 * than `/nodes/{type}/{id}/edges`. A derived key contains slashes, so the node segment has to be a
 * greedy capture, and a greedy capture swallows any suffix after it — the sub-resource path is not
 * expressible. The same structural limit is why the repository link endpoints cannot address a
 * repository at all.
 */
@RestController
@Tag(name = "Edges", description = "Typed relationships between nodes")
class EdgeController(
    private val edgeUseCase: EdgeUseCase,
    private val freshness: FactFreshness,
) {
    @PostMapping("/api/v1/edges")
    @Operation(summary = "State a relationship between two existing nodes, as manual or as a source the caller's scopes allow")
    fun create(
        @RequestBody body: EdgeRequestBody,
    ): ResponseEntity<EdgeResponse> {
        if (body.provenance?.validTo != null) {
            throw EdgeValidationException(listOf(PropertyError(ProvenanceRequest.VALID_TO_FIELD, ProvenanceRequest.VALID_TO_ON_CREATE)))
        }
        val written =
            edgeUseCase.create(
                EdgeRequest(body.type, body.fromId, body.toId, body.props, ProvenanceRequest.sourceOf(body.provenance)),
            )
        // Stating a known fact again is not an error, but it is not a creation either.
        val status = if (written.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(EdgeResponse.from(written, freshness))
    }

    @DeleteMapping("/api/v1/edges")
    @Operation(summary = "Remove a relationship, addressed by its exact triple")
    fun delete(
        @RequestParam type: String,
        @RequestParam fromId: String,
        @RequestParam toId: String,
    ): ResponseEntity<Void> =
        if (edgeUseCase.delete(type, fromId, toId)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }

    @GetMapping("/api/v1/edges")
    @Operation(summary = "Every relationship touching a node, under the name this end sees, now or as of an instant (#93)")
    fun forNode(
        @RequestParam nodeId: String,
        @RequestParam(defaultValue = "both") direction: String,
        @RequestParam(required = false) edgeType: String?,
        @Parameter(
            description =
                "An ISO-8601 instant: only the relationships whose [validFrom, validTo) contains it, to a node " +
                    "valid then too (#93). Left out, the current view.",
        )
        @RequestParam(required = false)
        asOf: String?,
    ): ResponseEntity<EdgeListResponse> {
        val instant = asOfParameter(asOf)
        val node = NodeKey.parse(nodeId)
        val items = edgeUseCase.forNode(node.type, node.key, directionOf(direction), edgeType, instant)
        return ResponseEntity.ok(EdgeListResponse(items.map { EdgeViewResponse.from(it, freshness) }))
    }

    private fun directionOf(value: String): Direction =
        when (value.lowercase()) {
            "in" -> Direction.INCOMING
            "out" -> Direction.OUTGOING
            "both" -> Direction.BOTH
            else -> throw IllegalArgumentException("direction must be one of in, out, both")
        }
}
