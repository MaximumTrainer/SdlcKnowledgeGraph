package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.port.`in`.SeedIngestOutcome
import com.repodatagraph.domain.port.`in`.SeedIngestUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Where the dogfood seed writes this repository's own SDLC (#47, FR10). The payload is in
 * docs/ADAPTERS.md.
 *
 * Like the deployment ingest endpoint, it is allowed through on a read-only instance and guards itself
 * with the ingest token instead (docs/DEPLOYMENT.md, D6), answering 503 on an instance given none.
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "Ingest", description = "Push ingestion from the delivery pipeline")
class SeedIngestController(
    private val useCase: SeedIngestUseCase,
) {
    @PostMapping("/seed")
    @Operation(summary = "Record repositories, teams, pipelines and dependencies read by the dogfood seed")
    fun seed(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> =
        when (val outcome = useCase.seed(authorization, body ?: ByteArray(0))) {
            is SeedIngestOutcome.Accepted ->
                ResponseEntity.accepted().body(SeedIngestResponse(outcome.created, outcome.nodes, outcome.edges))
            is SeedIngestOutcome.Invalid ->
                ResponseEntity.badRequest().body(mapOf("error" to "invalid seed", "fields" to outcome.errors))
            SeedIngestOutcome.Unauthorized ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "a valid bearer token is required"))
            SeedIngestOutcome.Disabled ->
                ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(mapOf("error" to "seed ingest is not configured on this instance"))
        }
}

data class SeedIngestResponse(
    val created: Boolean,
    val nodes: Int,
    val edges: Int,
)
