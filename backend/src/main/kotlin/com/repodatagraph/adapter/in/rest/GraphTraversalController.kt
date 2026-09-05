package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.model.CloudResource
import com.repodatagraph.domain.model.Deployment
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Traversals addressed by node id in a query parameter.
 *
 * Node ids contain the derived key, which contains slashes: `Repository:github.com/acme/payments`.
 * An encoded slash inside a path segment is rejected by the servlet container, and relaxing that
 * container-wide to allow prettier URLs would open a path-traversal surface. So ids travel as query
 * parameters. The path-based forms in [GraphController] remain for callers holding an older opaque
 * id, and both delegate to the same use case.
 */
@RestController
@RequestMapping("/api/v1/graph")
@Tag(name = "Graph traversal", description = "Traversals for nodes addressed by id")
class GraphTraversalController(
    private val graphQueryUseCase: GraphQueryUseCase,
) {
    @GetMapping("/deployments")
    @Operation(summary = "Deployments of artifacts built from a repository")
    fun deployments(
        @RequestParam repoId: String,
    ): List<Deployment> = graphQueryUseCase.getDeploymentsForRepo(repoId)

    @GetMapping("/dependencies")
    @Operation(summary = "Repositories this repository depends on")
    fun dependencies(
        @RequestParam repoId: String,
    ): List<Repository> = graphQueryUseCase.getDependencies(repoId)

    @GetMapping("/dependents")
    @Operation(summary = "Repositories that depend on this repository")
    fun dependents(
        @RequestParam repoId: String,
    ): List<Repository> = graphQueryUseCase.getDependents(repoId)

    @GetMapping("/cloud-resources")
    @Operation(summary = "Cloud resources owned by a repository")
    fun cloudResources(
        @RequestParam repoId: String,
    ): List<CloudResource> = graphQueryUseCase.getCloudResourcesForRepo(repoId)
}
