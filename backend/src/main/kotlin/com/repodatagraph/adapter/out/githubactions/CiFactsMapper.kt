package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.application.ingest.EnvironmentTypes
import com.repodatagraph.domain.lifecycle.DeploymentRecord
import com.repodatagraph.domain.lifecycle.DeploymentSupersession
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.connector.DeploymentHistory
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import com.repodatagraph.observability.LogEvents
import org.springframework.stereotype.Component

/** A repository as the connector names it in its facts: its key, and the address it is read from. */
data class RepositoryRef(
    val key: NodeKey,
    val htmlUrl: String,
)

/** A workflow run, and what it published. */
data class RunRead(
    val run: WorkflowRun,
    val artifacts: List<PublishedArtifact>,
)

/**
 * A deployment, its latest status - the newest that is not GitHub marking it replaced - and the run
 * that made it, with what that run published, when the connector could tell.
 */
data class DeploymentRead(
    val deployment: GitHubDeployment,
    val status: DeploymentStatus?,
    val run: RunRead?,
)

/**
 * A repository's runs and deployments as graph facts (#90).
 *
 * Per run that published something: `Repository -HAS_PIPELINE-> Pipeline` for its workflow, and per
 * artifact `Artifact -BUILT_FROM{commitSha}-> Repository`. Per deployment, and per artifact the run
 * that made it published, `Artifact -DEPLOYED_TO-> Deployment -TO_ENVIRONMENT-> Environment`. Keys come
 * from the identity resolver and properties are the deployment ingest's (#7), so a deployment both of
 * them see shares its Artifact, Environment and Pipeline with the ingest's, and `prod` is production.
 *
 * Every fact is the run's (FR-6): its id is the sourceId, and its completion is observedAt. A
 * deployment, and its edges, began when it was deployed; a successful one ends the deployment of the
 * same artifact family it replaced in its environment (FR-4), which [DeploymentSupersession] decides
 * against what [history] says is current. A deployment of nothing the connector can name is not
 * recorded, since a Deployment needs an artifact to be one of, and is logged instead.
 */
@Component
class CiFactsMapper(
    private val identityResolver: IdentityResolver,
    registry: OntologyRegistry,
    private val history: DeploymentHistory,
) {
    private val environmentTypes = EnvironmentTypes(registry)

    fun map(
        repository: RepositoryRef,
        runs: List<RunRead>,
        deployments: List<DeploymentRead>,
    ): GraphDelta {
        val facts = Facts()
        val published = (runs + deployments.mapNotNull { it.run }).distinctBy { it.run.id }.filter { it.artifacts.isNotEmpty() }
        published.forEach { facts.run(repository, it) }

        val recorded = mutableListOf<DeploymentRecord>()
        deployments.sortedBy { it.deployment.createdAt }.forEach { read ->
            val run = read.run?.takeIf { it.artifacts.isNotEmpty() }
            if (run == null) {
                LogEvents.actionsDeploymentUnattributed(repository.key.key, read.deployment.id.toString())
            } else {
                recorded += facts.deployment(read, run)
            }
        }

        val credited = (published.map { it.run } + deployments.mapNotNull { it.run?.run }).maxWithOrNull(NEWEST)
        credited?.takeIf { facts.isNotEmpty() }?.let { facts.repository(repository, it) }

        val current =
            recorded
                .map { it.family to it.environmentKey }
                .distinct()
                .flatMap { (family, environment) -> history.currentDeployments(family, environment) }
        return facts.delta().copy(supersessions = DeploymentSupersession.closures(recorded, current))
    }

    /** The facts of one read, each once: a run and the deployment it made are often read together. */
    private inner class Facts {
        private val nodes = linkedMapOf<NodeKey, NodeUpsert>()
        private val edges = linkedMapOf<Triple<String, NodeKey, NodeKey>, EdgeUpsert>()
        private var repositoryNode: NodeUpsert? = null

        fun isNotEmpty() = nodes.isNotEmpty()

        fun run(
            repository: RepositoryRef,
            read: RunRead,
        ) {
            val run = read.run
            val source = run.id.toString()
            run.path?.takeIf { WORKFLOW.matches(it) }?.let { path ->
                val pipeline =
                    node(
                        PIPELINE,
                        mapOf(
                            "provider" to PROVIDER,
                            "repoKey" to repository.key.key,
                            "workflowPath" to path,
                            "name" to path.substringAfterLast('/'),
                            "repoId" to repository.key.key,
                        ),
                        run,
                    )
                edge(EdgeUpsert(HAS_PIPELINE, repository.key, pipeline, observedAt = run.updatedAt, sourceId = source))
            }
            read.artifacts.forEach { artifact ->
                val key = node(ARTIFACT, artifactProps(artifact, run, repository), run, confidence = artifact.confidence)
                edge(
                    EdgeUpsert(
                        BUILT_FROM,
                        key,
                        repository.key,
                        props = mapOf("commitSha" to run.headSha),
                        observedAt = run.updatedAt,
                        sourceId = source,
                    ),
                )
            }
        }

        fun deployment(
            read: DeploymentRead,
            published: RunRead,
        ): List<DeploymentRecord> {
            val deployment = read.deployment
            val run = published.run
            val source = run.id.toString()
            // The run's completion, as for every fact; a run still going has not completed, so the
            // status it reported says when this was seen instead.
            val observedAt = if (run.completed) run.updatedAt else read.status?.createdAt ?: deployment.createdAt
            val environment = identityResolver.keyFor(ENVIRONMENT, mapOf("name" to deployment.environment))
            val status = statusOf(read.status)
            put(
                NodeUpsert(
                    ENVIRONMENT,
                    mapOf("name" to environment.key, "type" to environmentTypes.typeOf(environment.key)),
                    observedAt = observedAt,
                    sourceId = source,
                ),
            )
            return published.artifacts.map { artifact ->
                val artifactKey = identityResolver.keyFor(ARTIFACT, artifact.props())
                val props =
                    listOfNotNull(
                        "artifactKey" to artifactKey.key,
                        "environmentKey" to environment.key,
                        "deployedAt" to deployment.createdAt,
                        "artifactId" to artifactKey.key,
                        "environmentId" to environment.key,
                        deployment.creator?.login?.let { "deployedBy" to it },
                        "status" to status,
                    ).toMap()
                val key = put(NodeUpsert(DEPLOYMENT, props, observedAt = observedAt, sourceId = source, validFrom = deployment.createdAt))
                val began = deployment.createdAt
                edge(EdgeUpsert(DEPLOYED_TO, artifactKey, key, observedAt = observedAt, sourceId = source, validFrom = began))
                edge(EdgeUpsert(TO_ENVIRONMENT, key, environment, observedAt = observedAt, sourceId = source, validFrom = began))
                DeploymentRecord(key, artifact.family, environment.key, deployment.createdAt, succeeded = status == SUCCESS)
            }
        }

        /** Once per read, credited to its newest run, so a webhook and a poll of one run state it alike. */
        fun repository(
            repository: RepositoryRef,
            run: WorkflowRun,
        ) {
            repositoryNode =
                NodeUpsert(REPOSITORY, mapOf("url" to repository.htmlUrl), observedAt = run.updatedAt, sourceId = run.id.toString())
        }

        /** The repository first, since every BUILT_FROM and HAS_PIPELINE needs it to exist. */
        fun delta() = GraphDelta(nodes = listOfNotNull(repositoryNode) + nodes.values, edges = edges.values.toList())

        private fun node(
            type: String,
            props: Map<String, Any?>,
            run: WorkflowRun,
            confidence: Double = FULL,
        ): NodeKey = put(NodeUpsert(type, props, observedAt = run.updatedAt, sourceId = run.id.toString(), confidence = confidence))

        private fun put(node: NodeUpsert): NodeKey = identityResolver.keyFor(node.type, node.props).also { nodes[it] = node }

        private fun edge(edge: EdgeUpsert) {
            edges[Triple(edge.type, edge.from, edge.to)] = edge
        }
    }

    private fun artifactProps(
        artifact: PublishedArtifact,
        run: WorkflowRun,
        repository: RepositoryRef,
    ): Map<String, Any?> = artifact.props() + mapOf("commitSha" to run.headSha, "repoId" to repository.key.key)

    /** GitHub's state, in the words the Deployment's enum and why-failed already speak. */
    private fun statusOf(status: DeploymentStatus?): String = STATUSES[status?.state] ?: PENDING

    private companion object {
        const val REPOSITORY = "Repository"
        const val PIPELINE = "Pipeline"
        const val ARTIFACT = "Artifact"
        const val DEPLOYMENT = "Deployment"
        const val ENVIRONMENT = "Environment"
        const val HAS_PIPELINE = "HAS_PIPELINE"
        const val BUILT_FROM = "BUILT_FROM"
        const val DEPLOYED_TO = "DEPLOYED_TO"
        const val TO_ENVIRONMENT = "TO_ENVIRONMENT"
        const val PROVIDER = "github-actions"
        const val SUCCESS = "SUCCESS"
        const val PENDING = "PENDING"
        const val FULL = 1.0

        /** Only a file directly under `.github/workflows/` is a workflow GitHub runs (#86). */
        val WORKFLOW = Regex("""^\.github/workflows/[^/]+\.ya?ml$""")

        val STATUSES =
            mapOf(
                "success" to SUCCESS,
                "failure" to "FAILED",
                "error" to "FAILED",
                "in_progress" to "IN_PROGRESS",
                "queued" to PENDING,
                "pending" to PENDING,
            )

        /** The newest run by when it last changed, then by id, so the choice never depends on order. */
        val NEWEST: Comparator<WorkflowRun> = compareBy<WorkflowRun>({ it.updatedAt }, { it.id })
    }
}
