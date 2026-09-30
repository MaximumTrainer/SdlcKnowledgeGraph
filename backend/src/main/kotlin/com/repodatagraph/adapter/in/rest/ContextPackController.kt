package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.ContextPackRequest
import com.repodatagraph.adapter.`in`.rest.dto.ContextPackResponse
import com.repodatagraph.domain.model.ContextPackQuery
import com.repodatagraph.domain.port.`in`.ContextPackUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The bounded subgraph a task needs (#96): a registry template walked from one node, ranked and cut
 * to the caller's budget.
 *
 * A POST like `/api/v1/impact`, for a structured body rather than a write: it reads only, so it needs
 * `graph:read` ([ReadsOverPost][com.repodatagraph.config.ReadsOverPost]) and a read-only instance
 * answers it. A field missing or out of bounds is a 400 naming it (`{error, field}`), and a start node
 * the graph does not hold a 404.
 */
@RestController
@Tag(name = "Graph Queries", description = "Engineering knowledge graph queries")
class ContextPackController(
    private val useCase: ContextPackUseCase,
) {
    @PostMapping("/api/v1/context-pack")
    @Operation(
        summary = "Context pack: the bounded subgraph a task needs, walked by a registry template from one node",
        description =
            "Walks the named template (templates.yaml; listed by GET /api/v1/ontology) from startId, adds owners where " +
                "the template asks, and ranks what it reached by distance ascending, path confidence descending, impact " +
                "score (scoring version 1) descending and id ascending. At most `budget` (1..500) nodes are returned; " +
                "`truncated` and `cut` say how many were left out. Each node and edge carries a provenance summary, and " +
                "each edge its manifest, rule or commitSha where it has one. `asOf` reads the graph as it was then.",
    )
    fun contextPack(
        @RequestBody request: ContextPackRequest,
    ): ResponseEntity<ContextPackResponse> {
        val query = ContextPackQuery.of(request.startId, request.template, request.budget, request.asOf)
        return ResponseEntity.ok(ContextPackResponse.from(useCase.contextPack(query)))
    }
}
