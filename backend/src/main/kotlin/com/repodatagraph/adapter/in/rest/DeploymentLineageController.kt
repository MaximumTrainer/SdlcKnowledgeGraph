package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.DeploymentWorkItemsResponse
import com.repodatagraph.domain.port.`in`.ChangeLineageUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * What intent a deployment carries (#85). The deployment is named in a query parameter, since its key
 * holds `/` and `#`. `lineage` is `unknown` when no artifact of the deployment contains a change, so
 * an empty list is never mistaken for "carries nothing".
 */
@RestController
@RequestMapping("/api/v1/deployments")
@Tag(name = "Change Lineage", description = "Which deployments carry which work items")
class DeploymentLineageController(
    private val useCase: ChangeLineageUseCase,
) {
    @GetMapping("/work-items")
    @Operation(
        summary = "What a deployment carries: the changes its artifacts contain and the work items they implement",
        description =
            "deploymentId is Deployment:key or the bare key. lineage is known, or unknown when no artifact of the " +
                "deployment CONTAINS a Change, in which case the lists are empty because nothing is known.",
    )
    fun workItems(
        @RequestParam(required = false) deploymentId: String?,
    ): ResponseEntity<DeploymentWorkItemsResponse> =
        ResponseEntity.ok(DeploymentWorkItemsResponse.from(useCase.workItemsOfDeployment(deploymentId)))
}
