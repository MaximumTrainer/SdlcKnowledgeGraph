package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.port.`in`.DeploymentIngestOutcome
import com.repodatagraph.domain.port.`in`.DeploymentIngestUseCase
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
 * Where the deploy pipeline reports each deployment (#7). The payload is in docs/ADAPTERS.md.
 *
 * The body is taken as bytes and handed on untouched: it is deduplicated by its content, so binding
 * and re-serialising it here would make two identical reports look different.
 *
 * This is the one write a read-only instance still accepts (docs/DEPLOYMENT.md, D6). It is guarded by
 * its own bearer token instead, and answers 503 on an instance that was given none.
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "Ingest", description = "Push ingestion from the delivery pipeline")
class DeploymentIngestController(
    private val useCase: DeploymentIngestUseCase,
) {
    @PostMapping("/deployment")
    @Operation(summary = "Record a deployment reported by the deploy pipeline")
    fun ingest(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> =
        when (val outcome = useCase.ingest(authorization, body ?: ByteArray(0))) {
            is DeploymentIngestOutcome.Accepted -> ResponseEntity.accepted().body(outcome.toResponse())
            is DeploymentIngestOutcome.Invalid ->
                ResponseEntity.badRequest().body(mapOf("error" to "invalid deployment report", "fields" to outcome.errors))
            DeploymentIngestOutcome.Unauthorized ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "a valid bearer token is required"))
            DeploymentIngestOutcome.Disabled ->
                ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(mapOf("error" to "deployment ingest is not configured on this instance"))
        }

    private fun DeploymentIngestOutcome.Accepted.toResponse() =
        DeploymentIngestResponse(deploymentIds = deploymentIds, created = created, nodes = nodes, edges = edges)
}

data class DeploymentIngestResponse(
    val deploymentIds: List<String>,
    val created: Boolean,
    val nodes: Int,
    val edges: Int,
)
