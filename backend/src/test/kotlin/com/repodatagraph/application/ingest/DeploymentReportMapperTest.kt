package com.repodatagraph.application.ingest

import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * One deploy report as graph facts (#7, FR6): the repository, its pipeline, each artifact and where
 * each was deployed, joined by the edges "why did the deployment fail" has to walk.
 */
class DeploymentReportMapperTest {
    private val mapper = DeploymentReportMapper(IdentityResolver(), GitRemoteParser())

    private val deployedAt = Instant.parse("2026-09-29T12:00:00Z")
    private val runUrl = "https://github.com/maximumtrainer/sdlcknowledgegraph/actions/runs/42"
    private val report =
        DeploymentReport(
            repository = "github.com/MaximumTrainer/SdlcKnowledgeGraph",
            commitSha = "c1",
            artifacts =
                listOf(
                    ReportedArtifact("ghcr.io/maximumtrainer/sdlc-graph-backend", "sha256:abc", "v1"),
                    ReportedArtifact("ghcr.io/maximumtrainer/sdlc-graph-frontend", "sha256:def", "v1"),
                ),
            environment = "prod",
            status = "FAILED",
            deployedAt = deployedAt,
            deployedBy = "dan",
            runUrl = runUrl,
            pipeline = ReportedPipeline("github-actions", ".github/workflows/deploy-dogfood.yml"),
        )

    private val repository = NodeKey("Repository", "github.com/maximumtrainer/sdlcknowledgegraph")
    private val backend = NodeKey("Artifact", "ghcr.io/maximumtrainer/sdlc-graph-backend@sha256:abc")
    private val production = NodeKey("Environment", "production")
    private val backendDeployment = NodeKey("Deployment", "${backend.key}#production#${deployedAt.epochSecond}")

    private val mapped = mapper.map(report)

    private fun nodes(type: String) = mapped.delta.nodes.filter { it.type == type }

    @Test
    fun `records one deployment per artifact, keyed as the registry keys them`() {
        assertThat(mapped.deploymentKeys).containsExactly(
            backendDeployment,
            NodeKey("Deployment", "ghcr.io/maximumtrainer/sdlc-graph-frontend@sha256:def#production#${deployedAt.epochSecond}"),
        )
        val deployment = nodes("Deployment").first().props
        assertThat(deployment).containsEntry("status", "FAILED")
        assertThat(deployment).containsEntry("deployedBy", "dan")
        assertThat(deployment).containsEntry("artifactId", backend.key)
        assertThat(deployment).containsEntry("environmentId", "production")
    }

    @Test
    fun `splits the registry from the image name`() {
        val artifact = nodes("Artifact").first().props

        assertThat(artifact).containsEntry("registry", "ghcr.io")
        assertThat(artifact).containsEntry("name", "maximumtrainer/sdlc-graph-backend")
        assertThat(artifact).containsEntry("digest", "sha256:abc")
        assertThat(artifact).containsEntry("version", "v1")
        assertThat(artifact).containsEntry("commitSha", "c1")
        assertThat(artifact).containsEntry("artifactType", "container-image")
    }

    @Test
    fun `resolves an environment alias to the one environment it names`() {
        assertThat(nodes("Environment").map { it.props["name"] }).containsExactly("production")
    }

    @Test
    fun `records the pipeline that did the deploying, with how its run ended`() {
        val pipeline = nodes("Pipeline").single().props

        assertThat(pipeline).containsEntry("provider", "github-actions")
        assertThat(pipeline).containsEntry("repoKey", repository.key)
        assertThat(pipeline).containsEntry("workflowPath", ".github/workflows/deploy-dogfood.yml")
        assertThat(pipeline).containsEntry("lastRunStatus", "FAILED")
    }

    @Test
    fun `joins them with the edges a failed deployment is traced along`() {
        val edges = mapped.delta.edges.map { Triple(it.type, it.from, it.to) }

        assertThat(edges).contains(
            Triple("BUILT_FROM", backend, repository),
            Triple("DEPLOYED_TO", backend, backendDeployment),
            Triple("TO_ENVIRONMENT", backendDeployment, production),
        )
        assertThat(edges.filter { it.first == "HAS_PIPELINE" }.single().second).isEqualTo(repository)
        assertThat(
            mapped.delta.edges
                .first { it.type == "BUILT_FROM" }
                .props,
        ).containsEntry("commitSha", "c1")
    }

    @Test
    fun `says every fact came from the run that reported it, as of the deploy`() {
        assertThat(mapped.delta.nodes).allSatisfy {
            assertThat(it.sourceId).isEqualTo(runUrl)
            assertThat(it.observedAt).isEqualTo(deployedAt)
        }
        assertThat(mapped.delta.edges).allSatisfy { assertThat(it.observedAt).isEqualTo(deployedAt) }
    }
}
