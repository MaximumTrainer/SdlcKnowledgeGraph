package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.support.TestPrincipalConfig
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * The link engine (#28), driven through the API as an operator or the review page would: the
 * evidence is stated as nodes, a resolution is asked for, and its answer is read back as edges.
 *
 * A resolution runs in the background and answers 202 straight away, so every assertion about what
 * it wrote polls until the run has finished or the scenario's deadline passes.
 */
class LinkResolutionSteps(
    private val world: ApiWorld,
    private val neo4jClient: Neo4jClient,
) {
    /** The resource the scenario is about: the last one it created. */
    private var resource: String? = null
    private var lastRunId: String? = null
    private val candidates = mutableMapOf<String, String>()
    private var countBefore: Long? = null

    @Given("Repository {string} exists with packageNames {string}")
    fun repositoryExistsWithPackageNames(
        key: String,
        packageNames: String,
    ) {
        createRepository(key, packageNames.split(',').map { it.trim() })
    }

    @Given("Repository {string} exists")
    fun repositoryExists(key: String) {
        createRepository(key, emptyList())
    }

    @Given("CloudResource {string} has tag_repo {string} and name {string}")
    fun cloudResourceHasTagRepoAndName(
        key: String,
        repository: String,
        name: String,
    ) {
        createResource(key, name, listOf("repo=$repository"))
    }

    @Given("CloudResource {string} has tag_repo {string}")
    fun cloudResourceHasTagRepo(
        key: String,
        repository: String,
    ) {
        createResource(key, nameOf(key), listOf("repo=$repository"))
    }

    @Given("CloudResource {string} with no repo tag")
    fun cloudResourceWithNoRepoTag(key: String) {
        createResource(key, nameOf(key), emptyList())
    }

    @Given("CloudResource {string} with name {string} and no tag or IaC evidence")
    fun cloudResourceWithNameOnly(
        key: String,
        name: String,
    ) {
        createResource(key, name, emptyList())
    }

    @Given("IacFile {string} with resourceRefs containing {string}")
    fun iacFileWithResourceRefs(
        id: String,
        reference: String,
    ) {
        val repository = id.substringBefore(':')
        val path = id.substringAfter(':')
        val format = if (path.endsWith(".bicep")) "bicep" else "terraform"
        world.post(
            NODES + "IacFile",
            mapOf("props" to mapOf("repoKey" to repository, "path" to path, "format" to format, "resourceRefs" to listOf(reference))),
        )
        assertEquals(201, world.lastStatus()) { "creating the IaC file failed: " + world.lastResponse().body }
        val fileId = world.lastBody().path("id").asText()
        world.post(EDGES, mapOf("type" to "CONTAINS_IAC", "fromId" to "Repository:$repository", "toId" to fileId))
        assertTrue(world.lastStatus() in 200..299) { "linking the IaC file failed: " + world.lastResponse().body }
    }

    @When("I POST \\/api\\/v1\\/links\\/resolve")
    fun iPostResolve() {
        resolve()
    }

    @When("I POST \\/api\\/v1\\/links\\/resolve again")
    fun iPostResolveAgain() {
        countBefore = linkEdgeCount()
        resolve()
        awaitRunFinished()
    }

    @Then(
        "within {int} seconds an OWNS_RESOURCE edge from {string} to the resource exists with rule {string}, " +
            "confidence {double}, inferred true",
    )
    fun withinSecondsAnInferredOwnsResourceEdge(
        seconds: Int,
        repository: String,
        rule: String,
        confidence: Double,
    ) {
        val edge = await(seconds) { owner(repository)?.takeIf { it.path("props").path("rule").asText() == rule } }
        assertEquals(confidence, edge.path("provenance").path("confidence").asDouble(), DELTA) { edge.toString() }
        assertTrue(edge.path("provenance").path("inferred").asBoolean()) { edge.toString() }
        assertEquals("link-engine", edge.path("provenance").path("sourceSystem").asText()) { edge.toString() }
        assertTrue(edge.path("provenance").path("validTo").let { it.isMissingNode || it.isNull }) { edge.toString() }
    }

    @Then("that OWNS_RESOURCE edge has evidence {string} {string}")
    fun thatOwnsResourceEdgeHasEvidence(
        name: String,
        value: String,
    ) {
        val edges = incoming("OWNS_RESOURCE").filter { current(it) }
        assertEquals(1, edges.size) { edges.toString() }
        val evidence =
            edges
                .single()
                .path("props")
                .path("evidence")
                .map { it.asText() }
        assertTrue("$name=$value" in evidence) { evidence.toString() }
    }

    @Then("no CANDIDATE_LINK exists for the resource")
    fun noCandidateLinkExistsForTheResource() {
        awaitRunFinished()
        assertEquals(emptyList<JsonNode>(), outgoing("CANDIDATE_LINK"))
    }

    @Then(
        "within {int} seconds a CANDIDATE_LINK from the resource to {string} exists with rule {string}, " +
            "confidence {double}, status {string}",
    )
    fun withinSecondsACandidateLink(
        seconds: Int,
        repository: String,
        rule: String,
        confidence: Double,
        status: String,
    ) {
        val edge = await(seconds) { candidateTo(repository) }
        assertEquals(rule, edge.path("props").path("rule").asText()) { edge.toString() }
        assertEquals(confidence, edge.path("provenance").path("confidence").asDouble(), DELTA) { edge.toString() }
        assertEquals(status, edge.path("props").path("status").asText()) { edge.toString() }
    }

    @Then("a CANDIDATE_LINK from the resource to {string} exists with status {string}")
    fun aCandidateLinkExistsWithStatus(
        repository: String,
        status: String,
    ) {
        awaitRunFinished()
        val edge = candidateTo(repository)
        assertNotNull(edge) { outgoing("CANDIDATE_LINK").toString() }
        assertEquals(status, edge!!.path("props").path("status").asText()) { edge.toString() }
    }

    @Then("no OWNS_RESOURCE edge exists for the resource")
    fun noOwnsResourceEdgeExistsForTheResource() {
        awaitRunFinished()
        assertEquals(emptyList<JsonNode>(), incoming("OWNS_RESOURCE"))
    }

    @Then("within {int} seconds the sync run it answered with is a FULL run of {string} that ended SUCCESS")
    fun theSyncRunItAnsweredWith(
        seconds: Int,
        connector: String,
    ) {
        val runId = checkNotNull(lastRunId) { "no resolution was asked for" }
        val run = await(seconds) { runOf(runId)?.takeIf { it.path("status").asText() == "SUCCESS" } }
        assertEquals(connector, run.path("connector").asText()) { run.toString() }
        assertEquals("FULL", run.path("mode").asText()) { run.toString() }
    }

    @Given("resolution has already run over a tagged Lambda, a referenced bucket and a named queue")
    fun resolutionHasAlreadyRun() {
        createResource(LAMBDA, "payments-api", listOf("repo=github.com/acme/payments"))
        iacFileWithResourceRefs("github.com/acme/payments:infra/main.tf", "acme-payments-logs")
        createResource(BUCKET, "acme-payments-logs", emptyList())
        createResource(QUEUE, "billing-prod", emptyList())
        resolve()
        awaitRunFinished()
        assertEquals(3L, linkEdgeCount()) { "the first run should have written two owners and one candidate" }
    }

    @Then("the count of OWNS_RESOURCE and CANDIDATE_LINK edges is unchanged")
    fun theCountIsUnchanged() {
        assertEquals(checkNotNull(countBefore), linkEdgeCount())
    }

    @Given("a pending CANDIDATE_LINK {string} from the SQS resource to {string}")
    fun aPendingCandidateLink(
        label: String,
        repository: String,
    ) {
        createResource(QUEUE, "billing-prod", emptyList())
        resolve()
        awaitRunFinished()
        val listed = listCandidates("pending")
        val item =
            listed.firstOrNull {
                it.path("resource").path("key").asText() == QUEUE &&
                    it.path("repository").path("key").asText() == repository
            }
        assertNotNull(item) { listed.toString() }
        candidates[label] = item!!.path("id").asText()
    }

    @Given("candidate {string} has been rejected")
    fun candidateHasBeenRejected(label: String) {
        decide(label, "reject", TestPrincipalConfig.AUTHORIZATION)
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
    }

    @When("I POST \\/api\\/v1\\/links\\/candidates\\/{word}\\/accept as an admin")
    fun iAcceptAsAnAdmin(label: String) {
        decide(label, "accept", TestPrincipalConfig.AUTHORIZATION)
    }

    @When("I POST \\/api\\/v1\\/links\\/candidates\\/{word}\\/reject as an admin")
    fun iRejectAsAnAdmin(label: String) {
        decide(label, "reject", TestPrincipalConfig.AUTHORIZATION)
    }

    @When("I POST \\/api\\/v1\\/links\\/candidates\\/{word}\\/accept as a user without admin or curator role")
    fun iAcceptAsAReader(label: String) {
        decide(label, "accept", TestPrincipalConfig.READER_AUTHORIZATION)
    }

    @Then(
        "an OWNS_RESOURCE edge from {string} to the resource exists with rule {string}, confidence {double}, " +
            "inferred false, acceptedBy the admin subject",
    )
    fun anAcceptedOwnsResourceEdge(
        repository: String,
        rule: String,
        confidence: Double,
    ) {
        val edge = assertStatedOwner(repository, rule, confidence)
        assertEquals(TestPrincipalConfig.SUBJECT, edge.path("props").path("acceptedBy").asText()) { edge.toString() }
    }

    @Then("an OWNS_RESOURCE edge from {string} to the resource exists with rule {string}, confidence {double}, inferred false")
    fun aStatedOwnsResourceEdge(
        repository: String,
        rule: String,
        confidence: Double,
    ) {
        assertStatedOwner(repository, rule, confidence)
    }

    @Then("a later resolve run does not change it")
    fun aLaterResolveRunDoesNotChangeIt() {
        val before = incoming("OWNS_RESOURCE")
        resolve()
        awaitRunFinished()
        val after = incoming("OWNS_RESOURCE")
        assertEquals(before.map { it.path("props") }, after.map { it.path("props") })
        assertEquals(before.map { it.path("provenance").path("validTo") }, after.map { it.path("provenance").path("validTo") })
        assertEquals(1, after.count { current(it) }) { after.toString() }
    }

    @Then("after a later resolve run the CANDIDATE_LINK {string} still has status {string}, rejected by the admin subject")
    fun afterALaterRunTheCandidateIsStillRejected(
        label: String,
        status: String,
    ) {
        resolve()
        awaitRunFinished()
        val rejected = listCandidates("rejected").firstOrNull { it.path("id").asText() == candidates[label] }
        assertNotNull(rejected) { "candidate $label is not listed as rejected" }
        assertEquals(status, rejected!!.path("status").asText())
        val edge = outgoing("CANDIDATE_LINK").single()
        assertEquals(TestPrincipalConfig.SUBJECT, edge.path("props").path("rejectedBy").asText()) { edge.toString() }
    }

    @Then("GET \\/api\\/v1\\/links\\/candidates does not list {string}")
    fun getCandidatesDoesNotList(label: String) {
        world.get(CANDIDATES)
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        assertFalse(world.lastBody().path("items").any { it.path("id").asText() == candidates[label] }) { world.lastResponse().body }
    }

    @Given("an OWNS_RESOURCE edge with rule {string} exists for the Lambda")
    fun anOwnsResourceEdgeExistsForTheLambda(rule: String) {
        createResource(LAMBDA, "payments-api", listOf("repo=github.com/acme/payments"))
        resolve()
        withinSecondsAnInferredOwnsResourceEdge(TIMEOUT_SECONDS, "github.com/acme/payments", rule, TAG_CONFIDENCE)
    }

    @Given("the Lambda's tag_repo has been removed")
    fun theLambdasTagRepoHasBeenRemoved() {
        world.put(byKey("CloudResource", LAMBDA), mapOf("props" to resourceProps(LAMBDA, "payments-api", emptyList())))
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
    }

    @Then("within {int} seconds the OWNS_RESOURCE edge has provenance.validTo set")
    fun withinSecondsTheEdgeIsClosed(seconds: Int) {
        await(seconds) { incoming("OWNS_RESOURCE").singleOrNull()?.takeUnless { current(it) } }
    }

    @Then("the OWNS_RESOURCE edge has provenance.validTo set")
    fun theEdgeIsClosed() {
        val edges = incoming("OWNS_RESOURCE")
        assertEquals(1, edges.size) { edges.toString() }
        assertFalse(current(edges.single())) { edges.toString() }
    }

    @When("I POST \\/api\\/v1\\/links\\/manual from {string} to the resource")
    fun iPostAManualLink(repository: String) {
        world.post(LINKS + "manual", mapOf("resourceKey" to requireResource(), "repoKey" to repository))
    }

    @When("I DELETE the manual link from {string} to the resource")
    fun iDeleteTheManualLink(repository: String) {
        world.delete(
            UriComponentsBuilder
                .fromPath(LINKS + "manual")
                .queryParam("resourceKey", requireResource())
                .queryParam("repoKey", repository)
                .build()
                .encode()
                .toUriString(),
        )
    }

    private fun assertStatedOwner(
        repository: String,
        rule: String,
        confidence: Double,
    ): JsonNode {
        val edge = owner(repository)
        assertNotNull(edge) { incoming("OWNS_RESOURCE").toString() }
        assertEquals(rule, edge!!.path("props").path("rule").asText()) { edge.toString() }
        assertEquals(confidence, edge.path("provenance").path("confidence").asDouble(), DELTA) { edge.toString() }
        assertFalse(edge.path("provenance").path("inferred").asBoolean()) { edge.toString() }
        assertEquals("manual", edge.path("provenance").path("sourceSystem").asText()) { edge.toString() }
        return edge
    }

    private fun createRepository(
        key: String,
        packageNames: List<String>,
    ) {
        val props =
            mapOf(
                "url" to "https://$key",
                "defaultBranch" to "main",
                "topics" to emptyList<String>(),
                "codeowners" to emptyList<String>(),
            ) + if (packageNames.isEmpty()) emptyMap() else mapOf("packageNames" to packageNames)
        world.post(NODES + "Repository", mapOf("props" to props))
        assertEquals(201, world.lastStatus()) { "creating the repository failed: " + world.lastResponse().body }
    }

    private fun createResource(
        key: String,
        name: String,
        tags: List<String>,
    ) {
        world.post(NODES + "CloudResource", mapOf("props" to resourceProps(key, name, tags)))
        assertEquals(201, world.lastStatus()) { "creating the resource failed: " + world.lastResponse().body }
        resource = key
    }

    private fun resourceProps(
        key: String,
        name: String,
        tags: List<String>,
    ): Map<String, Any?> {
        val provider = key.substringBefore(':')
        val resourceId = key.substringAfter(':')
        return mapOf(
            "provider" to provider,
            "resourceId" to resourceId,
            "resourceType" to typeOf(resourceId),
            "name" to name,
            "tags" to tags,
        )
    }

    private fun resolve() {
        world.post(LINKS + "resolve", emptyMap<String, Any>())
        if (world.lastStatus() == 202) lastRunId = world.lastBody().path("syncRunId").asText()
    }

    private fun awaitRunFinished() {
        val runId = lastRunId ?: return
        await(TIMEOUT_SECONDS) { runOf(runId)?.takeIf { it.path("status").asText() in FINISHED } }
    }

    private fun runOf(id: String): JsonNode? {
        val response = world.get("/api/v1/sync-runs/$id")
        return if (response.statusCode.value() == 200) world.lastBody() else null
    }

    private fun decide(
        label: String,
        decision: String,
        authorization: String,
    ) {
        val id = candidates[label] ?: label
        world.postSigned("${LINKS}candidates/$id/$decision", "{}", mapOf("Authorization" to authorization))
    }

    private fun listCandidates(status: String): List<JsonNode> {
        world.get("$CANDIDATES?status=$status&size=100")
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        return world.lastBody().path("items").toList()
    }

    private fun owner(repository: String): JsonNode? =
        incoming("OWNS_RESOURCE").firstOrNull { it.path("other").path("key").asText() == repository && current(it) }

    private fun candidateTo(repository: String): JsonNode? =
        outgoing("CANDIDATE_LINK").firstOrNull { it.path("other").path("key").asText() == repository }

    private fun incoming(type: String): List<JsonNode> = edges("in", type)

    private fun outgoing(type: String): List<JsonNode> = edges("out", type)

    private fun edges(
        direction: String,
        type: String,
    ): List<JsonNode> {
        val path =
            UriComponentsBuilder
                .fromPath(EDGES)
                .queryParam("nodeId", "CloudResource:" + requireResource())
                .queryParam("direction", direction)
                .queryParam("edgeType", type)
                .build()
                .encode()
                .toUriString()
        world.get(path)
        return if (world.lastStatus() == 200) world.lastBody().path("items").toList() else emptyList()
    }

    private fun current(edge: JsonNode): Boolean = edge.path("provenance").path("validTo").let { it.isMissingNode || it.isNull }

    private fun linkEdgeCount(): Long =
        neo4jClient
            .query("MATCH ()-[r]->() WHERE type(r) IN ['OWNS_RESOURCE', 'CANDIDATE_LINK'] AND r.prov_validTo IS NULL RETURN count(r) AS c")
            .fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)

    private fun requireResource(): String = checkNotNull(resource) { "no resource was created" }

    private fun <T : Any> await(
        seconds: Int,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + seconds * NANOS_PER_SECOND
        while (true) {
            probe()?.let { return it }
            if (System.nanoTime() > deadline) break
            Thread.sleep(POLL_MILLIS)
        }
        val found = probe()
        return checkNotNull(found) { "nothing matched within $seconds seconds: " + world.lastResponse().body }
    }

    private fun byKey(
        type: String,
        key: String,
    ): String =
        UriComponentsBuilder
            .fromPath("$NODES$type/by-key")
            .queryParam("key", key)
            .build()
            .encode()
            .toUriString()

    private companion object {
        const val NODES = "/api/v1/nodes/"
        const val EDGES = "/api/v1/edges"
        const val LINKS = "/api/v1/links/"
        const val CANDIDATES = "/api/v1/links/candidates"
        const val LAMBDA = "aws:arn:aws:lambda:eu-west-1:1:function:payments-api"
        const val BUCKET = "aws:arn:aws:s3:::acme-payments-logs"
        const val QUEUE = "aws:arn:aws:sqs:eu-west-1:1:billing-prod"
        const val TAG_CONFIDENCE = 0.95
        const val TIMEOUT_SECONDS = 5
        const val DELTA = 1e-9
        const val POLL_MILLIS = 100L
        const val NANOS_PER_SECOND = 1_000_000_000L
        val FINISHED = setOf("SUCCESS", "PARTIAL", "FAILED")

        /** The last segment of an ARN or an Azure id: what a cloud connector names a resource by when it has no Name tag. */
        fun nameOf(key: String): String = key.substringAfterLast('/').substringAfterLast(':')

        fun typeOf(resourceId: String): String =
            when {
                resourceId.startsWith("arn:") -> resourceId.split(':')[2]
                else -> resourceId.substringBeforeLast('/').substringAfterLast('/')
            }
    }
}
