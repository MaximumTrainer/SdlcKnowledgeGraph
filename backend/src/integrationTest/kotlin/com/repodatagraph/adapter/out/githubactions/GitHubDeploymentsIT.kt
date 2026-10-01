package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.adapter.out.github.GitHubClient
import com.repodatagraph.adapter.out.github.GitHubHttp
import com.repodatagraph.adapter.out.github.GitHubProperties
import com.repodatagraph.adapter.out.github.GitHubRepositoryMapper
import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.lifecycle.DeploymentRecord
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.connector.DeploymentHistory
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import com.repodatagraph.domain.port.out.connector.plus
import com.repodatagraph.support.connector.FakeDeployment
import com.repodatagraph.support.connector.FakeDeploymentStatus
import com.repodatagraph.support.connector.FakeGitHub
import com.repodatagraph.support.connector.FakePackage
import com.repodatagraph.support.connector.FakePackageVersion
import com.repodatagraph.support.connector.FakeRepo
import com.repodatagraph.support.connector.FakeRun
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Reading runs, packages and deployments from GitHub over real HTTP (#90): from a polling cursor and
 * from a webhook, which must arrive at the same facts for the same run, and neither of which may read
 * an owner nobody configured.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitHubDeploymentsIT {
    private val github = FakeGitHub()
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val resolver = IdentityResolver(environmentAliases = registry.environmentAliases)
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val started = now.minusSeconds(3600)
    private val completed = started.plusSeconds(600)
    private val commit = "3c9a1f2e4b5d6a7b8c9d0e1f2a3b4c5d6e7f8a9b"
    private val current = mutableListOf<DeploymentRecord>()
    private lateinit var deployments: GitHubDeployments

    @BeforeAll
    fun startFake() {
        github.start()
    }

    @AfterAll
    fun stopFake() {
        github.stop()
    }

    @BeforeEach
    fun reset() {
        github.reset()
        current.clear()
        deployments =
            deploymentsFor(GitHubProperties(orgs = listOf(ORG), baseUrl = github.baseUrl, token = "fake-token", waitForResetSeconds = 5))
    }

    private fun deploymentsFor(properties: GitHubProperties): GitHubDeployments {
        val http = GitHubHttp(properties, RestClient.builder())
        val history =
            DeploymentHistory {
                    family,
                    environment,
                ->
                current.filter { it.family == family && it.environmentKey == environment }
            }
        return GitHubDeployments(
            properties,
            GitHubClient(http, properties),
            ActionsClient(http, properties),
            CiFactsMapper(resolver, registry, history),
            GitHubRepositoryMapper(resolver),
            Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    /** Run 4711 of `acme/payments` pushed an image and deployed it to `prod`. */
    private fun aRunThatDeployed(userAccount: Boolean = false) {
        github.hasRepositories(ORG, listOf(FakeRepo(name = REPO)), userAccount = userAccount)
        github.actions.hasRun(ORG, REPO, FakeRun(id = RUN, headSha = commit, createdAt = started, completedAt = completed))
        github.actions.hasPackage(
            ORG,
            FakePackage("payments", "container", REPO, listOf(FakePackageVersion("sha256:abc", started.plusSeconds(300), listOf("1.4.2")))),
            userAccount = userAccount,
        )
        github.actions.hasDeployment(
            ORG,
            REPO,
            FakeDeployment(
                id = DEPLOYMENT,
                sha = commit,
                environment = "prod",
                createdAt = started.plusSeconds(360),
                statuses = listOf(FakeDeploymentStatus("success", started.plusSeconds(420), RUN)),
            ),
        )
    }

    private fun Sequence<GraphDelta>.combined(): GraphDelta = fold(GraphDelta()) { all, page -> all + page }

    private fun GraphDelta.keys(type: String): List<NodeKey> = nodes.filter { it.type == type }.map { resolver.keyFor(type, it.props) }

    private fun event(
        type: String,
        body: String,
    ) = WebhookEvent("github-actions", mapOf("X-GitHub-Event" to type, "X-GitHub-Delivery" to "d-1"), body.toByteArray())

    private fun workflowRun(
        id: Long,
        owner: String = ORG,
    ) = event(
        "workflow_run",
        """
        {"action":"completed","workflow_run":{"id":$id},
         "repository":{"name":"$REPO","full_name":"$owner/$REPO","owner":{"login":"$owner"}}}
        """.trimIndent(),
    )

    @Test
    fun `a poll records what each completed run published and where each deployment put it`() {
        aRunThatDeployed()

        val read = deployments.sync(since = null, watermark = now).combined()

        assertThat(read.keys("Artifact")).containsExactly(NodeKey("Artifact", "ghcr.io/acme/payments@sha256:abc"))
        assertThat(read.keys("Deployment"))
            .containsExactly(NodeKey("Deployment", "ghcr.io/acme/payments@sha256:abc#production#${started.plusSeconds(360).epochSecond}"))
        assertThat(read.watermark).isEqualTo(now)
    }

    @Test
    fun `a poll from a cursor after the run and its deployment reads nothing again`() {
        aRunThatDeployed()

        val read = deployments.sync(since = completed.plusSeconds(1), watermark = now).combined()

        assertThat(read.nodes).isEmpty()
        assertThat(read.edges).isEmpty()
        assertThat(read.watermark).isEqualTo(now)
    }

    @Test
    fun `a webhook for a run arrives at the same facts as a poll`() {
        aRunThatDeployed()

        val polled = deployments.sync(since = null, watermark = now).combined()
        val pushed = checkNotNull(deployments.handle(workflowRun(RUN)))

        assertThat(pushed.nodes.toSet()).isEqualTo(polled.nodes.toSet())
        assertThat(pushed.edges.toSet()).isEqualTo(polled.edges.toSet())
    }

    @Test
    fun `a deployment status webhook reads the deployment and the run that made it back from GitHub`() {
        aRunThatDeployed()
        val body =
            """
            {"action":"created","deployment_status":{"state":"success"},"deployment":{"id":$DEPLOYMENT},
             "repository":{"name":"$REPO","full_name":"$ORG/$REPO","owner":{"login":"$ORG"}}}
            """.trimIndent()

        val pushed = checkNotNull(deployments.handle(event("deployment_status", body)))

        assertThat(pushed.keys("Deployment")).hasSize(1)
        assertThat(pushed.keys("Artifact")).containsExactly(NodeKey("Artifact", "ghcr.io/acme/payments@sha256:abc"))
    }

    @Test
    fun `an inactive status, which only says a later deployment replaced this one, writes nothing`() {
        aRunThatDeployed()
        val body =
            """
            {"action":"created","deployment_status":{"state":"inactive"},"deployment":{"id":$DEPLOYMENT},
             "repository":{"name":"$REPO","full_name":"$ORG/$REPO","owner":{"login":"$ORG"}}}
            """.trimIndent()

        assertThat(deployments.handle(event("deployment_status", body))).isNull()
    }

    @Test
    fun `an event about an owner nobody configured is not read`() {
        aRunThatDeployed()

        assertThat(deployments.handle(workflowRun(RUN, owner = "someone-else"))).isNull()
    }

    @Test
    fun `an event of a kind it does not read is ignored`() {
        assertThat(deployments.handle(event("star", """{"repository":{"name":"$REPO","full_name":"$ORG/$REPO"}}"""))).isNull()
    }

    @Test
    fun `a user account's packages are read when the owner is not an organisation`() {
        aRunThatDeployed(userAccount = true)

        val read = deployments.sync(since = null, watermark = now).combined()

        assertThat(read.keys("Artifact")).containsExactly(NodeKey("Artifact", "ghcr.io/acme/payments@sha256:abc"))
    }

    @Test
    fun `a repository without workflow runs or deployments costs nothing and fails nothing`() {
        github.hasRepositories(ORG, listOf(FakeRepo(name = "docs")))

        val read = deployments.sync(since = null, watermark = now).toList()

        assertThat(read.flatMap { it.nodes }).isEmpty()
        assertThat(read.last().watermark).isEqualTo(now)
    }

    @Test
    fun `the token is sent as a bearer credential`() {
        aRunThatDeployed()

        deployments.sync(since = null, watermark = now).toList()

        assertThat(github.lastRequest().getHeader("Authorization")).isEqualTo("Bearer fake-token")
    }

    private companion object {
        const val ORG = "acme"
        const val REPO = "payments"
        const val RUN = 4711L
        const val DEPLOYMENT = 9001L
    }
}
