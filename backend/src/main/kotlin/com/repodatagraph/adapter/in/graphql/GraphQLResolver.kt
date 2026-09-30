package com.repodatagraph.adapter.`in`.graphql

import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.ChangeImpactQuery
import com.repodatagraph.domain.model.DeploymentRecord
import com.repodatagraph.domain.model.ImpactDirection
import com.repodatagraph.domain.model.ImpactHit
import com.repodatagraph.domain.model.ImpactScoring
import com.repodatagraph.domain.model.ImpactSpec
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.model.nodeIdParameter
import com.repodatagraph.domain.port.`in`.ChangeImpactUseCase
import com.repodatagraph.domain.port.`in`.GraphQueryUseCase
import com.repodatagraph.domain.port.`in`.RepositoryUseCase
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.util.UUID

@Controller
class GraphQLResolver(
    private val repositoryUseCase: RepositoryUseCase,
    private val graphQueryUseCase: GraphQueryUseCase,
    private val gitRemoteParser: GitRemoteParser,
    private val changeImpactUseCase: ChangeImpactUseCase,
) {
    @QueryMapping
    fun repository(
        @Argument id: String,
    ) = repositoryUseCase.getRepository(id)

    @QueryMapping
    fun repositories() = repositoryUseCase.listRepositories()

    @QueryMapping
    fun cloudResourcesForRepo(
        @Argument repoId: String,
    ) = graphQueryUseCase.getCloudResourcesForRepo(repoId)

    @QueryMapping
    fun dependenciesForRepo(
        @Argument repoId: String,
    ) = graphQueryUseCase.getDependencies(repoId)

    @QueryMapping
    fun dependentsForRepo(
        @Argument repoId: String,
    ) = graphQueryUseCase.getDependents(repoId)

    @QueryMapping
    fun deploymentsForRepo(
        @Argument repoId: String,
    ) = graphQueryUseCase.getDeploymentsForRepo(repoId)

    @QueryMapping
    fun teamForRepo(
        @Argument repoId: String,
    ) = graphQueryUseCase.getTeamForRepo(repoId)

    @QueryMapping
    fun pipelinesForRepo(
        @Argument repoId: String,
    ) = graphQueryUseCase.getPipelinesForRepo(repoId)

    @QueryMapping
    fun impactAnalysis(
        @Argument repoId: String,
    ): Map<String, Any> {
        val analysis = graphQueryUseCase.getImpactAnalysis(repoId)
        return mapOf(
            "repoId" to repoId,
            "dependents" to (analysis["dependents"] ?: emptyList<Any>()),
            "cloudResources" to (analysis["cloudResources"] ?: emptyList<Any>()),
            "deployments" to (analysis["deployments"] ?: emptyList<Any>()),
        )
    }

    /** The blast radius (#21, FR10): the same answer as `GET /api/v1/graph/impact`, over the generated node types. */
    @QueryMapping
    fun impact(
        @Argument nodeId: String,
        @Argument depth: Int,
        @Argument minConfidence: Double,
        @Argument direction: String,
    ): Map<String, Any?> {
        val result =
            graphQueryUseCase.impact(
                ImpactSpec(nodeIdParameter("nodeId", nodeId), depth, minConfidence, ImpactDirection.parse(direction)),
            )
        return mapOf(
            "root" to GraphNodeView.of(result.root),
            "depth" to result.depth,
            "direction" to result.direction.name,
            "minConfidence" to result.minConfidence,
            "truncated" to result.truncated,
            "affected" to
                result.affected.map { affected ->
                    mapOf(
                        "node" to GraphNodeView.of(affected.node),
                        "distance" to affected.distance,
                        "confidence" to affected.confidence,
                        "inferred" to affected.inferred,
                        "path" to affected.path,
                    )
                },
            "byType" to result.byType.map { (type, count) -> mapOf("type" to type, "count" to count) },
        )
    }

    /** Why a deployment failed (#21, FR10): the same answer as `GET /api/v1/graph/why-failed`. */
    @QueryMapping
    fun whyDeploymentFailed(
        @Argument id: String,
    ): Map<String, Any?> {
        val result = graphQueryUseCase.whyDeploymentFailed(id)
        return mapOf(
            "deployment" to GraphNodeView.of(result.deployment),
            "status" to result.status,
            "deployedAt" to result.deployedAt.toString(),
            "artifact" to result.artifact?.let(GraphNodeView::of),
            "commitSha" to result.commitSha,
            "repository" to result.repository?.let(GraphNodeView::of),
            "pipeline" to result.pipeline?.let(GraphNodeView::of),
            "environment" to result.environment?.let(GraphNodeView::of),
            "changedDependencies" to result.changedDependencies.map(::record),
            "precedingSuccessfulDeployment" to result.precedingSuccessfulDeployment?.let(::record),
            "reasons" to result.reasons,
        )
    }

    /** What a change reaches, ranked (#87): the same answer as `POST /api/v1/impact`. */
    @QueryMapping
    fun changeImpact(
        @Argument input: Map<String, Any?>,
    ): Map<String, Any?> {
        val query =
            ChangeImpactQuery.of(
                repositoryKey = input["repositoryKey"] as? String,
                paths = (input["paths"] as? List<*>)?.map { it as? String },
                sha = input["sha"] as? String,
                depth = input["depth"] as? Int,
                limit = input["limit"] as? Int,
            )
        val result = changeImpactUseCase.changeImpact(query)
        return mapOf(
            "repository" to GraphNodeView.of(result.repository),
            "depth" to result.depth,
            "limit" to result.limit,
            "scoring" to
                mapOf(
                    "version" to ImpactScoring.VERSION,
                    "formula" to ImpactScoring.FORMULA,
                    "tierWeights" to
                        ImpactScoring.TIER_WEIGHTS.entries
                            .sortedBy { it.key.wire }
                            .map { (tier, weight) -> mapOf("tier" to tier.wire, "weight" to weight) },
                    "pathMatchBoost" to ImpactScoring.PATH_MATCH_BOOST,
                ),
            "pathFilter" to result.pathFilter.wire,
            "matchedPaths" to result.matchedPaths,
            "changeScope" to result.changeScope.wire,
            "truncated" to result.truncated,
            "hits" to result.hits.map(::hit),
        )
    }

    private fun hit(hit: ImpactHit): Map<String, Any?> =
        mapOf(
            "node" to GraphNodeView.of(hit.node),
            "hops" to hit.hops,
            "score" to hit.score,
            "confidence" to hit.confidence,
            "inferred" to hit.inferred,
            "tier" to hit.tier.wire,
            "environment" to hit.environment?.let { mapOf("id" to it.id, "key" to it.key.key, "tier" to hit.tier.wire) },
            "pathMatched" to hit.pathMatched,
            "owners" to hit.owners.map { mapOf("team" to GraphNodeView.of(it.team), "via" to it.via, "confidence" to it.confidence) },
            "citation" to
                mapOf(
                    "nodeKey" to hit.citation.nodeKey,
                    "edgePath" to hit.citation.edgePath,
                    "provenance" to GraphNodeView.provenance(hit.citation.provenance),
                ),
        )

    private fun record(record: DeploymentRecord): Map<String, Any?> =
        mapOf(
            "id" to record.id,
            "repository" to record.repository?.id,
            "commitSha" to record.commitSha,
            "deployedAt" to record.deployedAt.toString(),
            "status" to record.status,
        )

    @QueryMapping
    fun auditEventsForRepo(
        @Argument repoId: String,
    ) = graphQueryUseCase.getAuditEventsForRepo(repoId)

    @MutationMapping
    fun registerRepository(
        @Argument input: Map<String, Any>,
    ): Repository {
        // The remote is parsed here rather than trusted, so a GraphQL caller lands on the same node
        // a REST caller would for the same repository (#8).
        val remote = gitRemoteParser.parse(input["url"] as String)
        val repo =
            Repository(
                id = UUID.randomUUID().toString(),
                url = remote.canonicalUrl,
                host = remote.host,
                org = remote.org,
                name = remote.name,
                defaultBranch = input["defaultBranch"] as? String ?: "main",
                topics = (input["topics"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                codeowners = (input["codeowners"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                serviceId = input["serviceId"] as? String,
                language = input["language"] as? String,
                description = input["description"] as? String,
            )
        return repositoryUseCase.registerRepository(repo)
    }

    @MutationMapping
    fun deleteRepository(
        @Argument id: String,
    ): Boolean {
        repositoryUseCase.deleteRepository(id)
        return true
    }

    @MutationMapping
    fun linkRepoToTeam(
        @Argument repoId: String,
        @Argument teamId: String,
    ): Boolean {
        repositoryUseCase.linkToTeam(repoId, teamId)
        return true
    }

    @MutationMapping
    fun linkRepoToCloudResource(
        @Argument repoId: String,
        @Argument resourceId: String,
    ): Boolean {
        repositoryUseCase.linkToCloudResource(repoId, resourceId)
        return true
    }

    @MutationMapping
    fun addRepoDependency(
        @Argument fromRepoId: String,
        @Argument toRepoId: String,
    ): Boolean {
        repositoryUseCase.addDependency(fromRepoId, toRepoId)
        return true
    }
}
