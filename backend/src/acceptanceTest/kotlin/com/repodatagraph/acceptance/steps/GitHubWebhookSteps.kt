package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.acceptance.support.SyncWorld
import com.repodatagraph.application.connector.WebhookSignatureVerifier
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import java.util.UUID

/**
 * GitHub pushing what it knows, and the two things that have to hold before that is worth having.
 *
 * An event nobody can prove came from GitHub never reaches the connector, and the same event
 * delivered twice - which GitHub does by design whenever it is unsure the first attempt landed - is
 * applied once. Everything else about a webhook is the same reading a scheduled run does, which is
 * why these scenarios assert on the graph rather than on the payload.
 */
class GitHubWebhookSteps(
    private val world: ApiWorld,
    private val sync: SyncWorld,
) {
    private val verifier = WebhookSignatureVerifier()
    private var lastDelivery: String? = null
    private var lastBody: String? = null
    private var firstRunId: String? = null

    @When("a GitHub {string} event for {string} arrives, correctly signed")
    fun anEventArrivesSigned(
        type: String,
        repo: String,
    ) {
        deliver(type, payloadFor(repo), SECRET, UUID.randomUUID().toString())
    }

    @When("a GitHub {string} event for {string} arrives with no signature")
    fun anEventArrivesUnsigned(
        type: String,
        repo: String,
    ) {
        world.postSigned(WEBHOOK_PATH, payloadFor(repo), mapOf(EVENT_HEADER to type))
    }

    @When("a GitHub {string} event for {string} arrives signed with {string}")
    fun anEventArrivesSignedWith(
        type: String,
        repo: String,
        secret: String,
    ) {
        deliver(type, payloadFor(repo), secret, UUID.randomUUID().toString())
    }

    @When("a GitHub {string} event for {string} touching {string} arrives, correctly signed")
    fun aPushTouching(
        type: String,
        repo: String,
        path: String,
    ) {
        deliver(type, pushPayload(repo, path), SECRET, UUID.randomUUID().toString())
    }

    @When("GitHub redelivers that same event")
    fun gitHubRedelivers() {
        firstRunId = sync.runId
        deliver(
            checkNotNull(lastType),
            checkNotNull(lastBody),
            SECRET,
            checkNotNull(lastDelivery) { "no delivery has been sent yet" },
        )
    }

    @Then("the webhook is accepted")
    fun theWebhookIsAccepted() {
        assertEquals(ACCEPTED, world.lastStatus()) { "the webhook was not accepted: " + world.lastResponse().body }
    }

    @Then("the webhook is accepted with nothing to do")
    fun theWebhookIsAcceptedWithNothingToDo() {
        // 204, not 401 and not 500: the event was genuine, it simply says nothing this graph records.
        assertEquals(NO_CONTENT, world.lastStatus()) { "expected nothing to do, got: " + world.lastResponse().body }
    }

    @Then("the webhook is refused as unverified")
    fun theWebhookIsRefused() {
        assertEquals(UNAUTHORIZED, world.lastStatus()) { "an unverifiable webhook was not refused" }
    }

    @Then("the webhook run finishes with status {string}")
    fun theWebhookRunFinishesWith(status: String) {
        sync.awaitRun(status)
    }

    @Then("no Repository {string} was recorded")
    fun noRepositoryWasRecorded(key: String) {
        world.get("/api/v1/nodes/Repository/$key")
        assertEquals(NOT_FOUND, world.lastStatus()) { "a refused webhook still wrote to the graph" }
    }

    @Then("both deliveries name the same sync run")
    fun bothDeliveriesNameTheSameRun() {
        val second = world.lastBody().path("syncRunId").asText()
        assertNotNull(firstRunId) { "the first delivery produced no run" }
        assertEquals(firstRunId, second) { "a redelivery produced a second run; the runs were " + runsWithSourceIds() }
    }

    /** In the failure message, because "which run had which delivery id" is the whole question here. */
    private fun runsWithSourceIds(): String {
        world.get("/api/v1/nodes/SyncRun?limit=200")
        return world
            .lastBody()
            .path("items")
            .joinToString(", ") { it.path("key").asText() + "=" + it.path("props").path("sourceId").asText() }
    }

    @Then("exactly {int} sync run names that delivery")
    fun exactlyNRunsNameThatDelivery(expected: Int) {
        world.get("/api/v1/nodes/SyncRun?limit=200")
        val matching =
            world
                .lastBody()
                .path("items")
                .count { it.path("props").path("sourceId").asText() == lastDelivery }
        assertEquals(expected, matching)
    }

    private var lastType: String? = null

    private fun deliver(
        type: String,
        body: String,
        secret: String,
        delivery: String,
    ) {
        lastType = type
        lastBody = body
        lastDelivery = delivery
        world.postSigned(
            WEBHOOK_PATH,
            body,
            mapOf(
                EVENT_HEADER to type,
                DELIVERY_HEADER to delivery,
                SIGNATURE_HEADER to "sha256=" + verifier.sign(body.toByteArray(), secret),
            ),
        )
        // Recorded here rather than in an assertion step, so a scenario can wait for the run it just
        // caused without first having to say it was accepted.
        if (world.lastStatus() == ACCEPTED) sync.recordRun(world.lastBody().path("syncRunId").asText())
    }

    /** The fields this connector reads, in the shape GitHub sends them. */
    private fun payloadFor(repo: String): String =
        """
        {
          "action": "edited",
          "organization": { "login": "$ORG" },
          "repository": { "name": "$repo", "full_name": "$ORG/$repo", "default_branch": "main" }
        }
        """.trimIndent()

    private fun pushPayload(
        repo: String,
        path: String,
    ): String =
        """
        {
          "ref": "refs/heads/main",
          "organization": { "login": "$ORG" },
          "repository": { "name": "$repo", "full_name": "$ORG/$repo", "default_branch": "main" },
          "commits": [ { "added": [], "modified": ["$path"], "removed": [] } ]
        }
        """.trimIndent()

    private companion object {
        const val ORG = "acme"
        const val SECRET = "github-secret"
        const val WEBHOOK_PATH = "/api/v1/webhooks/github"
        const val EVENT_HEADER = "X-GitHub-Event"
        const val DELIVERY_HEADER = "X-GitHub-Delivery"
        const val SIGNATURE_HEADER = "X-Hub-Signature-256"
        const val ACCEPTED = 202
        const val NO_CONTENT = 204
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
    }
}
