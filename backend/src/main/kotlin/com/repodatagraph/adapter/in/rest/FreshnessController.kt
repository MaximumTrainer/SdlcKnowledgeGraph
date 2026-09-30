package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.SourceFreshnessResponse
import com.repodatagraph.domain.port.`in`.SourceFreshnessUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * How far behind each source system is (#93, FR-3 and FR-6).
 *
 * The same answer as the `freshness` component of /actuator/health, as a read the web interface can
 * make: it proxies no health details (docs/DEPLOYMENT.md, D1), and a caller with `graph:read` should
 * not need the actuator to learn how current the graph it reads is.
 */
@RestController
@Tag(name = "Freshness", description = "How current the graph is, source by source")
class FreshnessController(
    private val sources: SourceFreshnessUseCase,
) {
    @GetMapping("/api/v1/freshness")
    @Operation(summary = "Each source's last successful sync and its lag against the source's freshness window")
    fun freshness(): ResponseEntity<SourceFreshnessResponse> = ResponseEntity.ok(SourceFreshnessResponse.from(sources.lag()))
}
