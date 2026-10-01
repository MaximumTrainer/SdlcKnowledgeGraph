package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.adapter.out.github.GitHubRepoRef
import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.lifecycle.ArtifactFamily
import com.repodatagraph.domain.lifecycle.DeploymentRecord
import com.repodatagraph.domain.lifecycle.Supersession
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.connector.DeploymentHistory
import com.repodatagraph.domain.port.out.connector.GraphDelta
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant

/**
 * A repository's runs and deployments as graph facts (#90): what each run published and from which
 * commit, where each deployment put it, keyed exactly as the deployment ingest (#7) keys the same
 * things, and every fact credited to the run that produced it.
 */
class CiFactsMapperTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val resolver = IdentityResolver(environmentAliases = registry.environmentAliases)
    private val current = mutableListOf<DeploymentRecord>()
    private val history =
        DeploymentHistory { family, environment ->
            current.filter {
                it.family == family &&
                    it.environmentKey == environment
            }
        }
    private val mapper = CiFactsMapper(resolver, registry, history)

    private val started = Instant.parse("2026-09-30T10:00:00Z")
    private val completed = started.plusSeconds(600)
    private val deployedAt = started.plusSeconds(360)
    private val commit = "3c9a1f2e4b5d6a7b8c9d0e1f2a3b4c5d6e7f8a9b"
    private val repository = RepositoryRef(NodeKey("Repository", "github.com/acme/payments"), "https://github.com/acme/payments")
    private val run =
        WorkflowRun(
            id = 4711,
            path = ".github/workflows/release.yml",
            headSha = commit,
            status = "completed",
            conclusion = "success",
            createdAt = started,
            updatedAt = completed,
        )
    private val image = artifact("sha256:abc")
    private val imageKey = NodeKey("Artifact", "ghcr.io/acme/payments@sha256:abc")
    private val published = RunRead(run, listOf(image))

    private fun artifact(
        digest: String,
        name: String = "payments",
    ) = PublishedArtifact.of(
        owner = "acme",
        pkg = GitHubPackage(name, "container", GitHubRepoRef("acme/payments")),
        version = PackageVersion(digest, started.plusSeconds(300), PackageMetadata(container = ContainerMetadata(listOf("1.4.2")))),
        registries = mapOf("container" to "ghcr.io", "npm" to "npm.pkg.github.com"),
    )

    private fun deployment(
        environment: String = "prod",
        state: String? = "success",
        by: RunRead? = published,
        at: Instant = deployedAt,
        id: Long = 9001,
    ) = DeploymentRead(
        deployment = GitHubDeployment(id = id, sha = commit, environment = environment, createdAt = at, creator = GitHubActor("octocat")),
        status =
            state?.let {
                DeploymentStatus(
                    id = 1,
                    state = it,
                    createdAt = at.plusSeconds(60),
                    logUrl = "https://github.com/acme/payments/actions/runs/4711/job/1",
                )
            },
        run = by,
    )

    private fun GraphDelta.node(type: String) = nodes.single { it.type == type }

    private fun GraphDelta.edge(type: String) = edges.single { it.type == type }

    @Test
    fun `a published image is an artifact built from the run's commit`() {
        val delta = mapper.map(repository, listOf(published), emptyList())

        val built = delta.edge("BUILT_FROM")
        assertThat(resolver.keyFor("Artifact", delta.node("Artifact").props)).isEqualTo(imageKey)
        assertThat(built.from).isEqualTo(imageKey)
        assertThat(built.to).isEqualTo(repository.key)
        assertThat(built.props).containsEntry("commitSha", commit)
        assertThat(delta.node("Artifact").props).containsEntry("commitSha", commit).containsEntry("artifactType", "container-image")
    }

    @Test
    fun `the run is the source of every fact, observed when it completed`() {
        val delta = mapper.map(repository, listOf(published), listOf(deployment()))

        (
            delta.nodes.map {
                it.sourceId to it.observedAt
            } + delta.edges.map { it.sourceId to it.observedAt }
        ).forEach { (sourceId, observedAt) ->
            assertThat(sourceId).isEqualTo("4711")
            assertThat(observedAt).isEqualTo(completed)
        }
        assertThat(delta.node("Artifact").confidence).isEqualTo(1.0)
    }

    @Test
    fun `an artifact without a digest is keyed by name and version at confidence 0_8`() {
        val npm =
            PublishedArtifact.of(
                "acme",
                GitHubPackage("payments", "npm", GitHubRepoRef("acme/payments")),
                PackageVersion("1.4.2", started.plusSeconds(300)),
                mapOf("npm" to "npm.pkg.github.com"),
            )

        val delta = mapper.map(repository, listOf(RunRead(run, listOf(npm))), emptyList())

        assertThat(resolver.keyFor("Artifact", delta.node("Artifact").props)).isEqualTo(NodeKey("Artifact", "payments:1.4.2"))
        assertThat(delta.node("Artifact").confidence).isEqualTo(DIGESTLESS)
    }

    @Test
    fun `a deployment to prod is a deployment to production, through the alias table`() {
        val delta = mapper.map(repository, listOf(published), listOf(deployment(environment = "prod")))

        val environment = NodeKey("Environment", "production")
        val deploymentKey = NodeKey("Deployment", "${imageKey.key}#production#${deployedAt.epochSecond}")
        assertThat(resolver.keyFor("Environment", delta.node("Environment").props)).isEqualTo(environment)
        assertThat(delta.node("Environment").props).containsEntry("type", "production")
        assertThat(resolver.keyFor("Deployment", delta.node("Deployment").props)).isEqualTo(deploymentKey)
        assertThat(delta.edge("DEPLOYED_TO").from).isEqualTo(imageKey)
        assertThat(delta.edge("DEPLOYED_TO").to).isEqualTo(deploymentKey)
        assertThat(delta.edge("TO_ENVIRONMENT").to).isEqualTo(environment)
    }

    @Test
    fun `an environment the enum does not name is typed other`() {
        val delta = mapper.map(repository, listOf(published), listOf(deployment(environment = "dogfood")))

        assertThat(delta.node("Environment").props).containsEntry("name", "dogfood").containsEntry("type", "other")
    }

    @Test
    fun `a deployment carries who made it, when, and how it ended, and began when it was deployed`() {
        val delta = mapper.map(repository, listOf(published), listOf(deployment()))

        val node = delta.node("Deployment")
        assertThat(node.props)
            .containsEntry("deployedAt", deployedAt)
            .containsEntry("deployedBy", "octocat")
            .containsEntry("status", "SUCCESS")
            .containsEntry("artifactId", imageKey.key)
            .containsEntry("environmentId", "production")
        assertThat(node.validFrom).isEqualTo(deployedAt)
        assertThat(delta.edge("DEPLOYED_TO").validFrom).isEqualTo(deployedAt)
        assertThat(delta.edge("TO_ENVIRONMENT").validFrom).isEqualTo(deployedAt)
    }

    @Test
    fun `a deployment's status is the latest GitHub gave it, in the words the graph uses`() {
        val statuses =
            mapOf(
                "success" to "SUCCESS",
                "failure" to "FAILED",
                "error" to "FAILED",
                "in_progress" to "IN_PROGRESS",
                "queued" to "PENDING",
                "pending" to "PENDING",
            )
        statuses.forEach { (state, expected) ->
            val delta = mapper.map(repository, listOf(published), listOf(deployment(state = state)))
            assertThat(delta.node("Deployment").props).describedAs(state).containsEntry("status", expected)
        }
        val unstarted = mapper.map(repository, listOf(published), listOf(deployment(state = null)))
        assertThat(unstarted.node("Deployment").props).containsEntry("status", "PENDING")
    }

    @Test
    fun `a deployment of nothing a run published is not recorded`() {
        val delta = mapper.map(repository, emptyList(), listOf(deployment(by = RunRead(run, emptyList()))))

        assertThat(delta.nodes.filter { it.type == "Deployment" }).isEmpty()
        assertThat(delta.edges.filter { it.type == "TO_ENVIRONMENT" }).isEmpty()
    }

    @Test
    fun `the workflow that ran is a pipeline of the repository, keyed as the ingest keys it`() {
        val delta = mapper.map(repository, listOf(published), emptyList())

        assertThat(resolver.keyFor("Pipeline", delta.node("Pipeline").props))
            .isEqualTo(NodeKey("Pipeline", "github-actions:github.com/acme/payments:.github/workflows/release.yml"))
        assertThat(delta.edge("HAS_PIPELINE").from).isEqualTo(repository.key)
        assertThat(delta.node("Pipeline").props).doesNotContainKey("lastRunStatus")
    }

    @Test
    fun `a successful deployment supersedes the current one of the same family in the same environment`() {
        val earlierKey = NodeKey("Deployment", "ghcr.io/acme/payments@sha256:old#production#1790000000")
        current +=
            DeploymentRecord(earlierKey, ArtifactFamily("ghcr.io", "acme/payments"), "production", started.minusSeconds(86_400), true)

        val delta = mapper.map(repository, listOf(published), listOf(deployment()))

        assertThat(delta.supersessions).containsExactly(Supersession(earlierKey, deployedAt))
    }

    @Test
    fun `a failed deployment supersedes nothing`() {
        current +=
            DeploymentRecord(
                NodeKey("Deployment", "ghcr.io/acme/payments@sha256:old#production#1790000000"),
                ArtifactFamily("ghcr.io", "acme/payments"),
                "production",
                started.minusSeconds(86_400),
                true,
            )

        val delta = mapper.map(repository, listOf(published), listOf(deployment(state = "failure")))

        assertThat(delta.supersessions).isEmpty()
    }

    @Test
    fun `a run and the deployment it made are written once however both are read`() {
        val delta = mapper.map(repository, listOf(published), listOf(deployment()))

        assertThat(delta.nodes.filter { it.type == "Artifact" }).hasSize(1)
        assertThat(delta.nodes.filter { it.type == "Repository" }).hasSize(1)
        assertThat(delta.edges.filter { it.type == "BUILT_FROM" }).hasSize(1)
    }

    private companion object {
        const val DIGESTLESS = 0.8
    }
}
