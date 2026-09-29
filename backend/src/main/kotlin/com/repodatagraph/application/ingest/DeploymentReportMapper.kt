package com.repodatagraph.application.ingest

import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.springframework.stereotype.Component

/** A report as graph facts, with the keys of the deployments it records. */
data class MappedReport(
    val delta: GraphDelta,
    val deploymentKeys: List<NodeKey>,
)

/**
 * One deploy report as the facts "why did the deployment fail" walks (#7, FR6):
 *
 * `Repository -HAS_PIPELINE-> Pipeline`, and per artifact
 * `Artifact -BUILT_FROM{commitSha}-> Repository`, `Artifact -DEPLOYED_TO-> Deployment -TO_ENVIRONMENT-> Environment`.
 *
 * Keys come from the identity resolver, the same as for every other writer, so a repository the
 * GitHub connector already recorded is the one this report attaches to, and `prod` and `production`
 * are one environment. Every fact is stamped as observed at the deploy and sourced to the run URL.
 */
@Component
class DeploymentReportMapper(
    private val identityResolver: IdentityResolver,
    private val gitRemoteParser: GitRemoteParser,
) {
    fun map(report: DeploymentReport): MappedReport {
        val remote = gitRemoteParser.parse(report.repository)
        val repositoryProps = mapOf("url" to remote.canonicalUrl, "host" to remote.host, "org" to remote.org, "name" to remote.name)
        val repository = identityResolver.keyFor(REPOSITORY, repositoryProps)

        val pipelineProps =
            mapOf(
                "provider" to report.pipeline.provider,
                "repoKey" to repository.key,
                "workflowPath" to report.pipeline.workflowPath,
                "name" to report.pipeline.workflowPath.substringAfterLast('/'),
                "repoId" to repository.key,
                "lastRunStatus" to report.status,
            )
        val pipeline = identityResolver.keyFor(PIPELINE, pipelineProps)

        val environment = identityResolver.keyFor(ENVIRONMENT, mapOf("name" to report.environment))
        val environmentProps = mapOf("name" to environment.key, "type" to environment.key)

        val nodes = mutableListOf(node(REPOSITORY, repositoryProps, report), node(PIPELINE, pipelineProps, report))
        nodes += node(ENVIRONMENT, environmentProps, report)
        val edges = mutableListOf(edge("HAS_PIPELINE", repository, pipeline, report))
        val deployments = mutableListOf<NodeKey>()

        report.artifacts.forEach { reported ->
            val artifactProps = artifactProps(reported, report, repository)
            val artifact = identityResolver.keyFor(ARTIFACT, artifactProps)
            val deploymentProps =
                mapOf(
                    "artifactKey" to artifact.key,
                    "environmentKey" to environment.key,
                    "deployedAt" to report.deployedAt,
                    "artifactId" to artifact.key,
                    "environmentId" to environment.key,
                    "deployedBy" to report.deployedBy,
                    "status" to report.status,
                )
            val deployment = identityResolver.keyFor(DEPLOYMENT, deploymentProps)

            nodes += node(ARTIFACT, artifactProps, report)
            nodes += node(DEPLOYMENT, deploymentProps, report)
            edges += edge("BUILT_FROM", artifact, repository, report, mapOf("commitSha" to report.commitSha))
            edges += edge("DEPLOYED_TO", artifact, deployment, report)
            edges += edge("TO_ENVIRONMENT", deployment, environment, report)
            deployments += deployment
        }
        return MappedReport(GraphDelta(nodes = nodes, edges = edges), deployments)
    }

    /** `ghcr.io/acme/api` is registry `ghcr.io` and name `acme/api`; a name with no host part has no registry. */
    private fun artifactProps(
        reported: ReportedArtifact,
        report: DeploymentReport,
        repository: NodeKey,
    ): Map<String, Any?> {
        val first = reported.name.substringBefore('/')
        val hasRegistry = reported.name.contains('/') && (first.contains('.') || first.contains(':') || first == "localhost")
        return mapOf(
            "registry" to if (hasRegistry) first else null,
            "name" to if (hasRegistry) reported.name.substringAfter('/') else reported.name,
            "digest" to reported.digest,
            "version" to (reported.tag ?: reported.digest),
            "commitSha" to report.commitSha,
            "repoId" to repository.key,
            "artifactType" to CONTAINER_IMAGE,
        )
    }

    private fun node(
        type: String,
        props: Map<String, Any?>,
        report: DeploymentReport,
    ) = NodeUpsert(type = type, props = props, observedAt = report.deployedAt, sourceId = report.runUrl)

    private fun edge(
        type: String,
        from: NodeKey,
        to: NodeKey,
        report: DeploymentReport,
        props: Map<String, Any?> = emptyMap(),
    ) = EdgeUpsert(type = type, from = from, to = to, props = props, observedAt = report.deployedAt)

    private companion object {
        const val REPOSITORY = "Repository"
        const val PIPELINE = "Pipeline"
        const val ARTIFACT = "Artifact"
        const val DEPLOYMENT = "Deployment"
        const val ENVIRONMENT = "Environment"
        const val CONTAINER_IMAGE = "container-image"
    }
}
