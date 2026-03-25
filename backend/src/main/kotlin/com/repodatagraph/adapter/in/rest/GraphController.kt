package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.ImpactAnalysisResponse
import com.repodatagraph.domain.model.*
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/graph")
@Tag(name = "Graph Queries", description = "Engineering knowledge graph queries")
class GraphController(private val graphQueryUseCase: GraphQueryUseCase) {

    @GetMapping("/repositories/{repoId}/cloud-resources")
    @Operation(summary = "Get cloud resources deployed by a repository")
    fun getCloudResources(@PathVariable repoId: String): ResponseEntity<List<CloudResource>> =
        ResponseEntity.ok(graphQueryUseCase.getCloudResourcesForRepo(repoId))

    @GetMapping("/repositories/{repoId}/dependencies")
    @Operation(summary = "Get upstream dependencies of a repository")
    fun getDependencies(@PathVariable repoId: String): ResponseEntity<List<Repository>> =
        ResponseEntity.ok(graphQueryUseCase.getDependencies(repoId))

    @GetMapping("/repositories/{repoId}/dependents")
    @Operation(summary = "Get downstream dependents of a repository")
    fun getDependents(@PathVariable repoId: String): ResponseEntity<List<Repository>> =
        ResponseEntity.ok(graphQueryUseCase.getDependents(repoId))

    @GetMapping("/repositories/{repoId}/deployments")
    @Operation(summary = "Get all deployments from a repository")
    fun getDeployments(@PathVariable repoId: String): ResponseEntity<List<Deployment>> =
        ResponseEntity.ok(graphQueryUseCase.getDeploymentsForRepo(repoId))

    @GetMapping("/repositories/{repoId}/audit")
    @Operation(summary = "Get audit trail for a repository")
    fun getAuditEvents(@PathVariable repoId: String): ResponseEntity<List<AuditEvent>> =
        ResponseEntity.ok(graphQueryUseCase.getAuditEventsForRepo(repoId))

    @GetMapping("/repositories/{repoId}/team")
    @Operation(summary = "Get the owning team for a repository")
    fun getTeam(@PathVariable repoId: String): ResponseEntity<Team> {
        val team = graphQueryUseCase.getTeamForRepo(repoId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(team)
    }

    @GetMapping("/repositories/{repoId}/servicenow")
    @Operation(summary = "Get the ServiceNow CI item linked to a repository")
    fun getServiceNowCI(@PathVariable repoId: String): ResponseEntity<ServiceNowCI> {
        val ci = graphQueryUseCase.getServiceNowCIForRepo(repoId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ci)
    }

    @GetMapping("/repositories/{repoId}/pipelines")
    @Operation(summary = "Get pipelines for a repository")
    fun getPipelines(@PathVariable repoId: String): ResponseEntity<List<Pipeline>> =
        ResponseEntity.ok(graphQueryUseCase.getPipelinesForRepo(repoId))

    @GetMapping("/repositories/{repoId}/impact")
    @Operation(summary = "Impact analysis: what is affected if this repo breaks")
    fun getImpactAnalysis(@PathVariable repoId: String): ResponseEntity<ImpactAnalysisResponse> {
        val analysis = graphQueryUseCase.getImpactAnalysis(repoId)
        val response = ImpactAnalysisResponse(
            repoId = repoId,
            dependents = analysis["dependents"]?.filterIsInstance<Repository>() ?: emptyList(),
            cloudResources = analysis["cloudResources"]?.filterIsInstance<CloudResource>() ?: emptyList(),
            deployments = analysis["deployments"]?.filterIsInstance<Deployment>() ?: emptyList()
        )
        return ResponseEntity.ok(response)
    }
}
