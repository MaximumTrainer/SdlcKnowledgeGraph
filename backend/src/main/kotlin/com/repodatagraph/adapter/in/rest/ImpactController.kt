package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.ImpactResponse
import com.repodatagraph.adapter.`in`.rest.dto.OwnersResponse
import com.repodatagraph.adapter.`in`.rest.dto.WhyFailedResponse
import com.repodatagraph.domain.model.ImpactSpec
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The multi-hop graph questions (#21): what a change reaches, why a deployment failed, who owns a
 * node. Nodes are addressed as `Type:key` in a query parameter, since keys hold '/' and '#'. A bad
 * parameter is a 400 naming it (`{error, field}`), and a node that resolves to nothing a 404.
 */
@RestController
@RequestMapping("/api/v1/graph")
@Tag(name = "Graph Queries", description = "Engineering knowledge graph queries")
class ImpactController(
    private val graphQueryUseCase: GraphQueryUseCase,
) {
    /**
     * What a change to a node reaches (#21, FR1 to FR3). The parameters are read as text and bounded
     * by [ImpactSpec], so a malformed one is a 400 naming it rather than Spring's generic conversion error.
     */
    @GetMapping("/impact")
    @Operation(
        summary = "Blast radius: every node a change reaches, with the path, distance and confidence that explain it",
        description =
            "nodeId is Type:key. depth 1..5 (default 3), minConfidence 0..1 (default 0.5), direction downstream (default) or " +
                "upstream. Edges walked are those the ontology flags `impact: propagates`. At most 1000 nodes are listed.",
    )
    fun impact(
        @RequestParam(required = false) nodeId: String?,
        @RequestParam(required = false) depth: String?,
        @RequestParam(required = false) minConfidence: String?,
        @RequestParam(required = false) direction: String?,
    ): ResponseEntity<ImpactResponse> =
        ResponseEntity.ok(ImpactResponse.from(graphQueryUseCase.impact(ImpactSpec.of(nodeId, depth, minConfidence, direction))))

    /**
     * Why a deployment failed (#21, FR4 and FR5). A query parameter rather than a path segment: a
     * Deployment key holds '/' and '#', and an encoded slash in a path is refused by the servlet container.
     */
    @GetMapping("/why-failed")
    @Operation(summary = "Why a deployment failed: its lineage, the last success before it and the dependencies deployed since")
    fun whyFailed(
        @RequestParam(required = false) deploymentId: String?,
    ): ResponseEntity<WhyFailedResponse> =
        ResponseEntity.ok(WhyFailedResponse.from(graphQueryUseCase.whyDeploymentFailed(deploymentId.orEmpty())))

    @GetMapping("/owners")
    @Operation(summary = "Who owns a node: its own OWNED_BY, or the owners of what it inherits ownership from")
    fun owners(
        @RequestParam(required = false) nodeId: String?,
    ): ResponseEntity<OwnersResponse> = ResponseEntity.ok(OwnersResponse.from(graphQueryUseCase.owners(nodeId.orEmpty())))
}
