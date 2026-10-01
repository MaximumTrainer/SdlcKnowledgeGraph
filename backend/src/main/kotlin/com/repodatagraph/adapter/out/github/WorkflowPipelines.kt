package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * A `Pipeline` per GitHub Actions workflow file, and a `HAS_PIPELINE` edge to it (#86, FR-3).
 *
 * Read from the branch's file listing the connector already has, so a pipeline costs no request of
 * its own and needs no permission beyond the contents the token already reads. Only a file directly
 * under `.github/workflows/` is one: GitHub runs nothing from a subdirectory, and a YAML file
 * anywhere else is configuration of some other kind.
 *
 * Keyed as the deployment reports and the dogfood seed already key a pipeline, provider
 * `github-actions`, the repository's key and the workflow's path, so the same workflow seen by all
 * three is one node rather than three. Named after its file, as the seed names it, so the name does
 * not change with whichever of them wrote last. The status of its last run is the deployment
 * report's to say: the connector leaves `lastRunStatus` alone.
 */
@Component
class WorkflowPipelines(
    private val identityResolver: IdentityResolver,
) {
    fun isWorkflow(path: String): Boolean = WORKFLOW.matches(path)

    /**
     * @param source the repository as GitHub names it, `org/name`, for the evidence each fact names
     */
    fun map(
        repositoryKey: NodeKey,
        source: String,
        workflowPaths: List<String>,
        observedAt: Instant?,
    ): GraphDelta {
        val pipelines =
            workflowPaths.filter(::isWorkflow).distinct().map { path ->
                path to
                    NodeUpsert(
                        type = PIPELINE,
                        props =
                            mapOf(
                                "provider" to PROVIDER,
                                "repoKey" to repositoryKey.key,
                                "workflowPath" to path,
                                "name" to path.substringAfterLast('/'),
                                // Deprecated and still required of a writer (docs/ONTOLOGY.md).
                                "repoId" to repositoryKey.key,
                            ),
                        observedAt = observedAt,
                        sourceId = "$source:$path",
                    )
            }
        return GraphDelta(
            nodes = pipelines.map { it.second },
            edges =
                pipelines.map { (path, pipeline) ->
                    EdgeUpsert(
                        type = HAS_PIPELINE,
                        from = repositoryKey,
                        to = identityResolver.keyFor(PIPELINE, pipeline.props),
                        observedAt = observedAt,
                        sourceId = "$source:$path",
                    )
                },
        )
    }

    private companion object {
        const val PIPELINE = "Pipeline"
        const val HAS_PIPELINE = "HAS_PIPELINE"
        const val PROVIDER = "github-actions"
        val WORKFLOW = Regex("""^\.github/workflows/[^/]+\.ya?ml$""")
    }
}
