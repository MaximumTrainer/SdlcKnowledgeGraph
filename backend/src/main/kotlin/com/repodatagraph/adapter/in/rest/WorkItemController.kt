package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.WorkItemDeploymentsResponse
import com.repodatagraph.domain.port.`in`.ChangeLineageUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Where a work item is live (#85). The work item is named by its URI in a query parameter: a URI
 * holds `//`, which the security firewall refuses in a path, and an encoded slash the servlet
 * container refuses. A missing uri is a 400 naming it, a work item the graph does not hold a 404.
 */
@RestController
@RequestMapping("/api/v1/work-items")
@Tag(name = "Change Lineage", description = "Which deployments carry which work items")
class WorkItemController(
    private val useCase: ChangeLineageUseCase,
) {
    @GetMapping("/deployments")
    @Operation(
        summary = "Where a work item is live: every deployment of an artifact containing a change that implements it",
        description =
            "uri is the ExternalWorkItem's URI exactly as written. Most recent deployment first, each with its environment " +
                "and the artifacts and changes that carry the work item there. A work item live nowhere has no deployments.",
    )
    fun deployments(
        @RequestParam(required = false) uri: String?,
    ): ResponseEntity<WorkItemDeploymentsResponse> = ResponseEntity.ok(WorkItemDeploymentsResponse.from(useCase.deploymentsOfWorkItem(uri)))
}
