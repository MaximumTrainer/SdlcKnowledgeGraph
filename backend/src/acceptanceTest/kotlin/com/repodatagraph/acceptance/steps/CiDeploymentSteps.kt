package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.acceptance.support.SyncWorld
import com.repodatagraph.application.connector.WebhookSignatureVerifier
import com.repodatagraph.support.connector.FakeDeployment
import com.repodatagraph.support.connector.FakeDeploymentStatus
import com.repodatagraph.support.connector.FakeGitHub
import com.repodatagraph.support.connector.FakePackage
import com.repodatagraph.support.connector.FakePackageVersion
import com.repodatagraph.support.connector.FakeRepo
import com.repodatagraph.support.connector.FakeRun
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Plays GitHub Actions for the github-actions connector (#90): a fake GitHub with workflow runs, the
 * packages they published and the deployments they made, and webhooks signed as GitHub signs them.
 *
 * Times are set relative to now, a few minutes in the past, because a poll reads what completed
 * within its lookback and a fixed date would one day fall out of it. What is asserted is read
 * straight from the graph, so a scenario says what was stored rather than what an endpoint chose to
 * show of it.
 */
class CiDeploymentSteps(
    private val world: ApiWorld,
    private val sync: SyncWorld,
    private val github: FakeGitHub,
    private val neo4jClient: Neo4jClient,
) {
    private val verifier = WebhookSignatureVerifier()
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private var run: FakeRun? = null
    private var image: Image? = null
    private var earlierDeployment: Long? = null
    private var newerDeployment: Long? = null

    @Before
    fun awaitTheConnector() {
        sync.awaitIdle(CONNECTOR)
    }

    @Given("the GitHub organisation {string} has repository {string} for the CI connector")
    fun theOrganisationHasRepository(
        org: String,
        repo: String,
    ) {
        assertThat(org).isEqualTo(ORG)
        github.hasRepositories(ORG, listOf(FakeRepo(name = repo)))
    }

    @Given("workflow run {long} on {string} built image {string}")
    fun workflowRunBuiltImage(
        id: Long,
        repository: String,
        reference: String,
    ) {
        assertThat(repository).isEqualTo("$ORG/$REPO")
        val built = Image.of(reference)
        image = built
        run = runPublishing(id, COMMIT, built, startedMinutesAgo = 30)
    }

    @Given("the run deployed that image to environment {string}")
    fun theRunDeployedTo(environment: String) {
        val deployedBy = checkNotNull(run) { "no run has been set up" }
        github.actions.hasDeployment(
            ORG,
            REPO,
            FakeDeployment(
                id = DEPLOYMENT_ID,
                sha = deployedBy.headSha,
                environment = environment,
                createdAt = deployedBy.createdAt.plus(DEPLOY_AFTER),
                statuses =
                    listOf(
                        FakeDeploymentStatus("in_progress", deployedBy.createdAt.plus(DEPLOY_AFTER), deployedBy.id),
                        FakeDeploymentStatus("success", deployedBy.createdAt.plus(DEPLOY_AFTER).plusSeconds(MINUTE), deployedBy.id),
                    ),
            ),
        )
    }

    @When("the CI connector processes run {long}")
    fun theConnectorProcessesRun(id: Long) {
        deliver("workflow_run", workflowRunPayload(id), SECRET)
        assertThat(world.lastStatus()).describedAs(world.lastResponse().body).isEqualTo(ACCEPTED)
    }

    @Given("the CI connector has processed run {long}")
    fun theConnectorHasProcessedRun(id: Long) {
        theConnectorProcessesRun(id)
    }

    @When("run {long}'s completion arrives signed with {string}")
    fun runCompletionArrivesSignedWith(
        id: Long,
        secret: String,
    ) {
        deliver("workflow_run", workflowRunPayload(id), secret)
    }

    @When("the CI connector polls with nothing new upstream")
    fun theConnectorPolls() {
        sync.startSync(CONNECTOR, "incremental")
        sync.awaitRun("SUCCESS")
    }

    @Given("a current Deployment of {string} to {string} with validTo null")
    fun aCurrentDeployment(
        name: String,
        environment: String,
    ) {
        val built = Image.of("ghcr.io/$ORG/$name@sha256:aaa")
        val earlier = runPublishing(EARLIER_RUN, "1111111" + PADDING, built, startedMinutesAgo = 120)
        earlierDeployment = EARLIER_DEPLOYMENT
        hasDeploymentBy(earlier, EARLIER_DEPLOYMENT, environment)
        sync.startSync(CONNECTOR, "full")
        sync.awaitRun("SUCCESS")

        val validTo = deploymentProperty(EARLIER_DEPLOYMENT, "prov_validTo")
        assertThat(deploymentProperty(EARLIER_DEPLOYMENT, "key")).describedAs("the earlier deployment was not recorded").isNotNull()
        assertThat(validTo).describedAs("the earlier deployment is not current").isNull()
    }

    @When("a newer deployment of {string} to {string} is processed")
    fun aNewerDeploymentIsProcessed(
        name: String,
        environment: String,
    ) {
        val built = Image.of("ghcr.io/$ORG/$name@sha256:bbb")
        val newer = runPublishing(NEWER_RUN, "2222222" + PADDING, built, startedMinutesAgo = 30)
        newerDeployment = NEWER_DEPLOYMENT
        hasDeploymentBy(newer, NEWER_DEPLOYMENT, environment)
        deliver("deployment_status", deploymentStatusPayload(NEWER_DEPLOYMENT), SECRET)
        assertThat(world.lastStatus()).describedAs(world.lastResponse().body).isEqualTo(ACCEPTED)
    }

    @Given("a run that published {string} with no digest")
    fun aRunThatPublishedWithNoDigest(coordinates: String) {
        val (name, version) = coordinates.split(":", limit = 2)
        val started = now.minus(NPM_RUN_STARTED)
        github.actions.hasRun(
            ORG,
            REPO,
            FakeRun(id = NPM_RUN, headSha = COMMIT, createdAt = started, completedAt = started.plusSeconds(TEN_MINUTES)),
        )
        github.actions.hasPackage(
            ORG,
            FakePackage(name, "npm", REPO, listOf(FakePackageVersion(version, started.plusSeconds(FIVE_MINUTES)))),
        )
    }

    @When("the connector processes it")
    fun theConnectorProcessesIt() {
        sync.startSync(CONNECTOR, "full")
        sync.awaitRun("SUCCESS")
    }

    @Then("an Artifact {string} exists with BUILT_FROM commitSha {string}")
    fun anArtifactExistsBuiltFrom(
        key: String,
        commit: String,
    ) {
        val commits =
            neo4jClient
                .query("MATCH (:Artifact { key: \$key })-[b:BUILT_FROM]->(:Repository { key: \$repo }) RETURN b.commitSha AS sha")
                .bindAll(mapOf("key" to key, "repo" to "github.com/$ORG/$REPO"))
                .fetch()
                .all()
                .map { it["sha"].toString() }
        // The step names the commit as people do, by its abbreviation; the edge holds all of it.
        assertThat(commits).describedAs("BUILT_FROM of $key").singleElement().satisfies({ assertThat(it).startsWith(commit) })
    }

    @Then("a Deployment exists TO_ENVIRONMENT {string}")
    fun aDeploymentExistsTo(environment: String) {
        val environments =
            neo4jClient
                .query(
                    """
                    MATCH (:Artifact)-[:DEPLOYED_TO]->(:Deployment)-[t:TO_ENVIRONMENT]->(e:Environment)
                    WHERE t.prov_validTo IS NULL RETURN e.key AS key
                    """.trimIndent(),
                ).fetch()
                .all()
                .map { it["key"] }
        assertThat(environments).containsExactly(environment)
    }

    @Then("the Deployment provenance has sourceSystem {string} and sourceId {string}")
    fun theDeploymentProvenance(
        sourceSystem: String,
        sourceId: String,
    ) {
        val provenance =
            neo4jClient
                .query("MATCH (d:Deployment) RETURN d.prov_sourceSystem AS system, d.prov_sourceId AS id")
                .fetch()
                .all()
                .toList()
        assertThat(provenance).hasSize(1)
        assertThat(provenance[0]["system"]).isEqualTo(sourceSystem)
        assertThat(provenance[0]["id"]).isEqualTo(sourceId)
    }

    @Then("the earlier Deployment has a non-null validTo equal to the newer deployedAt")
    fun theEarlierDeploymentIsClosed() {
        val validTo = deploymentProperty(checkNotNull(earlierDeployment), "prov_validTo")
        val deployedAt = deploymentProperty(checkNotNull(newerDeployment), "deployedAt")
        assertThat(validTo).describedAs("the earlier deployment is still current").isNotNull()
        assertThat(instant(validTo)).isEqualTo(instant(deployedAt))
        assertThat(
            deploymentProperty(checkNotNull(newerDeployment), "prov_validTo"),
        ).describedAs("the newer deployment was closed").isNull()
    }

    @Then("the earlier Deployment was retired as {string}")
    fun theEarlierDeploymentWasRetiredAs(reason: String) {
        assertThat(deploymentProperty(checkNotNull(earlierDeployment), "prov_retiredReason")).isEqualTo(reason)
    }

    @Then("the Artifact key is {string} and confidence is {double}")
    fun theArtifactKeyAndConfidence(
        key: String,
        confidence: Double,
    ) {
        val rows =
            neo4jClient
                .query("MATCH (a:Artifact) RETURN a.key AS key, a.prov_confidence AS confidence")
                .fetch()
                .all()
                .toList()
        assertThat(rows.map { it["key"] }).containsExactly(key)
        assertThat((rows[0]["confidence"] as Number).toDouble()).isEqualTo(confidence)
    }

    @Then(EVERY_FACT_CARRIES_THE_RUN)
    fun everyFactCarriesTheRun(
        sourceSystem: String,
        sourceId: String,
    ) {
        val completedAt = checkNotNull(run).completedAt
        val facts =
            neo4jClient
                .query(
                    """
                    MATCH (a:Artifact)-[d:DEPLOYED_TO]->(x:Deployment)-[t:TO_ENVIRONMENT]->(:Environment)
                    MATCH (a)-[b:BUILT_FROM]->(:Repository)
                    UNWIND [a, d, x, t, b] AS fact
                    RETURN fact.prov_sourceSystem AS system, fact.prov_sourceId AS id, fact.prov_observedAt AS observedAt
                    """.trimIndent(),
                ).fetch()
                .all()
        assertThat(facts).hasSize(FACTS)
        facts.forEach { fact ->
            assertThat(fact["system"]).isEqualTo(sourceSystem)
            assertThat(fact["id"]).isEqualTo(sourceId)
            assertThat(instant(fact["observedAt"])).isEqualTo(completedAt)
        }
    }

    @Then("the CI connector's run reports zero written")
    fun theRunReportsZeroWritten() {
        assertThat(
            sync
                .runNode()
                .path("props")
                .path("written")
                .asInt(-1),
        ).isZero()
    }

    @Then("there is exactly {int} Deployment and {int} Artifact in the graph")
    fun exactlyInTheGraph(
        deployments: Int,
        artifacts: Int,
    ) {
        assertThat(count("MATCH (d:Deployment) RETURN count(d) AS n")).isEqualTo(deployments.toLong())
        assertThat(count("MATCH (a:Artifact) RETURN count(a) AS n")).isEqualTo(artifacts.toLong())
    }

    @Then("the CI webhook is refused with {int}")
    fun theWebhookIsRefused(status: Int) {
        assertThat(world.lastStatus()).isEqualTo(status)
    }

    /** A run of [REPO] that pushed [image] to GitHub Packages while it ran. */
    private fun runPublishing(
        id: Long,
        commit: String,
        image: Image,
        startedMinutesAgo: Long,
    ): FakeRun {
        val started = now.minus(startedMinutesAgo, ChronoUnit.MINUTES)
        val run = FakeRun(id = id, headSha = commit, createdAt = started, completedAt = started.plusSeconds(TEN_MINUTES))
        github.actions.hasRun(ORG, REPO, run)
        github.actions.hasPackage(
            ORG,
            FakePackage(
                image.packageName,
                "container",
                REPO,
                listOf(FakePackageVersion(image.digest, started.plusSeconds(FIVE_MINUTES), tags = listOf(commit.take(SHORT_SHA)))),
            ),
        )
        return run
    }

    private fun hasDeploymentBy(
        run: FakeRun,
        id: Long,
        environment: String,
    ) {
        val created = run.createdAt.plus(DEPLOY_AFTER)
        github.actions.hasDeployment(
            ORG,
            REPO,
            FakeDeployment(
                id = id,
                sha = run.headSha,
                environment = environment,
                createdAt = created,
                statuses = listOf(FakeDeploymentStatus("success", created.plusSeconds(MINUTE), run.id)),
            ),
        )
    }

    private fun deliver(
        event: String,
        body: String,
        secret: String,
    ) {
        world.postSigned(
            WEBHOOK_PATH,
            body,
            mapOf(
                "X-GitHub-Event" to event,
                "X-GitHub-Delivery" to UUID.randomUUID().toString(),
                "X-Hub-Signature-256" to "sha256=" + verifier.sign(body.toByteArray(), secret),
            ),
        )
        if (world.lastStatus() == ACCEPTED) sync.recordRun(world.lastBody().path("syncRunId").asText())
    }

    private fun workflowRunPayload(id: Long): String =
        """
        {
          "action": "completed",
          "workflow_run": { "id": $id, "status": "completed" },
          "organization": { "login": "$ORG" },
          "repository": { "name": "$REPO", "full_name": "$ORG/$REPO", "owner": { "login": "$ORG" } }
        }
        """.trimIndent()

    private fun deploymentStatusPayload(id: Long): String =
        """
        {
          "action": "created",
          "deployment_status": { "state": "success" },
          "deployment": { "id": $id },
          "organization": { "login": "$ORG" },
          "repository": { "name": "$REPO", "full_name": "$ORG/$REPO", "owner": { "login": "$ORG" } }
        }
        """.trimIndent()

    /** A property of the Deployment the fake's deployment [id] became, found through the run that made it. */
    private fun deploymentProperty(
        id: Long,
        property: String,
    ): Any? {
        val digest = if (id == EARLIER_DEPLOYMENT) "sha256:aaa" else "sha256:bbb"
        return neo4jClient
            .query("MATCH (d:Deployment) WHERE d.artifactKey ENDS WITH \$digest RETURN d[\$property] AS value")
            .bindAll(mapOf("digest" to "@$digest", "property" to property))
            .fetch()
            .one()
            .map { it["value"] }
            .orElse(null)
    }

    private fun count(query: String): Long =
        neo4jClient
            .query(query)
            .fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)

    private fun instant(value: Any?): Instant? =
        when (value) {
            null -> null
            is ZonedDateTime -> value.toInstant()
            is Instant -> value
            else -> Instant.parse(value.toString())
        }

    /** An image as a registry names it: `ghcr.io/acme/payments@sha256:abc`. */
    private data class Image(
        val packageName: String,
        val digest: String,
    ) {
        companion object {
            fun of(reference: String): Image {
                val (name, digest) = reference.split("@", limit = 2)
                return Image(name.substringAfterLast('/'), digest)
            }
        }
    }

    private companion object {
        const val CONNECTOR = "github-actions"
        const val ORG = "acme"
        const val REPO = "payments"
        const val SECRET = "github-actions-secret"
        const val WEBHOOK_PATH = "/api/v1/webhooks/github-actions"
        const val PADDING = "e4b5d6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c"
        const val COMMIT = "3c9a1f2$PADDING"
        const val DEPLOYMENT_ID = 9001L
        const val EARLIER_RUN = 100L
        const val NEWER_RUN = 101L
        const val NPM_RUN = 4800L
        const val EARLIER_DEPLOYMENT = 9100L
        const val NEWER_DEPLOYMENT = 9101L
        const val SHORT_SHA = 7
        const val MINUTE = 60L
        const val FIVE_MINUTES = 300L
        const val TEN_MINUTES = 600L
        const val ACCEPTED = 202
        const val EVERY_FACT_CARRIES_THE_RUN =
            "the Artifact, the Deployment and the edges between them carry sourceSystem {string}, " +
                "sourceId {string} and the run's completion as observedAt"

        /** The Artifact, DEPLOYED_TO, the Deployment, TO_ENVIRONMENT and BUILT_FROM. */
        const val FACTS = 5
        val DEPLOY_AFTER: Duration = Duration.ofMinutes(6)
        val NPM_RUN_STARTED: Duration = Duration.ofMinutes(30)
    }
}
