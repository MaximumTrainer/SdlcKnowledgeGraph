package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.adapter.`in`.rest.dto.ImpactAnalysisResponse
import com.repodatagraph.adapter.`in`.rest.dto.NeighbourhoodResponse
import com.repodatagraph.adapter.`in`.rest.dto.ServiceNowCIResponse
import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.NeighbourhoodSpec
import com.repodatagraph.domain.model.Pipeline
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.model.Team
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriUtils
import kotlin.text.Charsets.UTF_8

@RestController
@RequestMapping("/api/v1/graph")
@Tag(name = "Graph Queries", description = "Engineering knowledge graph queries")
class GraphController(
    private val graphQueryUseCase: GraphQueryUseCase,
) {
    @GetMapping("/repositories/{repoId}/cloud-resources")
    @Operation(summary = "Get cloud resources deployed by a repository")
    fun getCloudResources(
        @PathVariable repoId: String,
    ): ResponseEntity<List<CloudResource>> = ResponseEntity.ok(graphQueryUseCase.getCloudResourcesForRepo(repoId))

    @GetMapping("/repositories/{repoId}/dependencies")
    @Operation(summary = "Get upstream dependencies of a repository")
    fun getDependencies(
        @PathVariable repoId: String,
    ): ResponseEntity<List<Repository>> = ResponseEntity.ok(graphQueryUseCase.getDependencies(repoId))

    @GetMapping("/repositories/{repoId}/dependents")
    @Operation(summary = "Get downstream dependents of a repository")
    fun getDependents(
        @PathVariable repoId: String,
    ): ResponseEntity<List<Repository>> = ResponseEntity.ok(graphQueryUseCase.getDependents(repoId))

    @GetMapping("/repositories/{repoId}/deployments")
    @Operation(summary = "Get all deployments from a repository")
    fun getDeployments(
        @PathVariable repoId: String,
    ): ResponseEntity<List<Deployment>> = ResponseEntity.ok(graphQueryUseCase.getDeploymentsForRepo(repoId))

    @GetMapping("/repositories/{repoId}/audit")
    @Operation(summary = "Get audit trail for a repository")
    fun getAuditEvents(
        @PathVariable repoId: String,
    ): ResponseEntity<List<AuditEvent>> = ResponseEntity.ok(graphQueryUseCase.getAuditEventsForRepo(repoId))

    @GetMapping("/repositories/{repoId}/team")
    @Operation(summary = "Get the owning team for a repository")
    fun getTeam(
        @PathVariable repoId: String,
    ): ResponseEntity<Team> {
        val team = graphQueryUseCase.getTeamForRepo(repoId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(team)
    }

    @GetMapping("/repositories/{repoId}/servicenow")
    @Operation(summary = "Get the ServiceNow CI item linked to a repository")
    fun getServiceNowCI(
        @PathVariable repoId: String,
    ): ResponseEntity<ServiceNowCIResponse> {
        val ci = graphQueryUseCase.getConfigurationItemForRepo(repoId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ServiceNowCIResponse.from(ci, repoId))
    }

    @GetMapping("/repositories/{repoId}/pipelines")
    @Operation(summary = "Get pipelines for a repository")
    fun getPipelines(
        @PathVariable repoId: String,
    ): ResponseEntity<List<Pipeline>> = ResponseEntity.ok(graphQueryUseCase.getPipelinesForRepo(repoId))

    /**
     * The neighbourhood the graph view draws (#9, FR1 to FR3). Parameters are read as text and bounded
     * by [NeighbourhoodSpec], so a malformed one is a 400 naming it; the node is a query parameter
     * because its key holds '/'. Filters are comma separated.
     */
    @GetMapping("/neighbourhood")
    @Operation(
        summary = "A bounded neighbourhood of a node, ready to draw: labelled nodes and the edges between them",
        description =
            "nodeId is Type:key. depth 1..3 (default 1); nodeTypes and edgeTypes are comma separated (default: every type " +
                "but the meta ones, and every edge type); direction in, out or both (default). At most 500 nodes, nearest " +
                "first; truncated says there were more. An edge's id is type:from>to.",
    )
    fun neighbourhood(
        @RequestParam(required = false) nodeId: String?,
        @RequestParam(required = false) depth: String?,
        @RequestParam(required = false) nodeTypes: String?,
        @RequestParam(required = false) edgeTypes: String?,
        @RequestParam(required = false) direction: String?,
    ): ResponseEntity<NeighbourhoodResponse> {
        val spec = NeighbourhoodSpec.of(nodeId, depth, nodeTypes, edgeTypes, direction)
        return ResponseEntity.ok(NeighbourhoodResponse.from(graphQueryUseCase.neighbourhood(spec)))
    }

    /**
     * Superseded by `GET /api/v1/graph/impact` (#21), and answered from it: the same traversal, cut
     * back to the three lists this endpoint has always sent. The `Deprecation` header and the `Link` to
     * the successor say so without the caller reading release notes.
     */
    @GetMapping("/repositories/{repoId}/impact")
    @Operation(summary = "Impact analysis: what is affected if this repo breaks. Deprecated: use /api/v1/graph/impact", deprecated = true)
    fun getImpactAnalysis(
        @PathVariable repoId: String,
    ): ResponseEntity<ImpactAnalysisResponse> {
        val analysis = graphQueryUseCase.getImpactAnalysis(repoId)
        val response =
            ImpactAnalysisResponse(
                repoId = repoId,
                dependents = analysis["dependents"]?.filterIsInstance<Repository>() ?: emptyList(),
                cloudResources = analysis["cloudResources"]?.filterIsInstance<CloudResource>() ?: emptyList(),
                deployments = analysis["deployments"]?.filterIsInstance<Deployment>() ?: emptyList(),
            )
        // Encoded, because the path variable is the caller's text and a header is no place for it raw.
        val successor = UriUtils.encodeQueryParam(if (repoId.startsWith("$REPOSITORY:")) repoId else "$REPOSITORY:$repoId", UTF_8)
        return ResponseEntity
            .ok()
            .header(DEPRECATION_HEADER, "true")
            .header(LINK_HEADER, "</api/v1/graph/impact?nodeId=$successor>; rel=\"successor-version\"")
            .body(response)
    }

    private companion object {
        const val REPOSITORY = "Repository"
        const val DEPRECATION_HEADER = "Deprecation"
        const val LINK_HEADER = "Link"
    }
}
