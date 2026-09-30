package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.ChangeImpactRequest
import com.repodatagraph.adapter.`in`.rest.dto.ChangeImpactResponse
import com.repodatagraph.domain.model.ChangeImpactQuery
import com.repodatagraph.domain.port.`in`.ChangeImpactUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * What a change to a repository reaches, ranked for an agent's context pack (#87).
 *
 * A POST because the question is a structured body - a list of paths among it - not because it
 * writes: it reads only, so it needs `graph:read` ([ReadsOverPost][com.repodatagraph.config.ReadsOverPost])
 * and a read-only instance answers it. A bound broken is a 400 naming the field and the bound
 * (`{error, field}`), and a repository the graph does not hold a 404.
 */
@RestController
@Tag(name = "Graph Queries", description = "Engineering knowledge graph queries")
class ChangeImpactController(
    private val useCase: ChangeImpactUseCase,
) {
    @PostMapping("/api/v1/impact")
    @Operation(
        summary = "Impact of a change: what a change to a repository reaches, ranked, with owners and a citation per hit",
        description =
            "Walks the edges the ontology flags `impact: propagates` downstream from the repository, depth 1..4 " +
                "(default 2). Each hit is scored min(1, 1/(1+hops) * tierWeight * pathBoost) - scoring version 1 - and " +
                "the hits are ordered by score descending, hops ascending, node id ascending, at most `limit` " +
                "(1..500, default 50) of them. Identical requests against an unchanged graph give identical bodies.",
    )
    fun impact(
        @RequestBody request: ChangeImpactRequest,
    ): ResponseEntity<ChangeImpactResponse> {
        val query =
            ChangeImpactQuery.of(
                request.repositoryKey,
                request.paths,
                request.sha,
                request.depth,
                request.limit,
                request.provider,
                request.providerId,
            )
        return ResponseEntity.ok(ChangeImpactResponse.from(useCase.changeImpact(query)))
    }
}
