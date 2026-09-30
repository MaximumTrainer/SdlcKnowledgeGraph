package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.acceptance.support.FakeItsmConnector
import com.repodatagraph.acceptance.support.SyncWorld
import com.repodatagraph.acceptance.support.says
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import com.repodatagraph.support.connector.FakeConnector
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.core.env.Environment
import org.springframework.data.neo4j.core.Neo4jClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The data lifecycle (#33): property history read as of an instant, per-connector retirement rules,
 * the archive, and ontology migrations.
 *
 * What the API cannot be asked to state - when a value was stated, when a source last stated a fact,
 * which ontology version a graph is on - is written straight into the graph, as the freshness steps
 * do (#93). Everything a user or an operator does goes through HTTP, as the test principal, which
 * holds graph:admin in this suite.
 */
class LifecycleSteps(
    private val world: ApiWorld,
    private val sync: SyncWorld,
    private val fake: FakeConnector,
    private val itsm: FakeItsmConnector,
    private val graphStore: GraphStore,
    private val neo4jClient: Neo4jClient,
    private val registry: OntologyRegistry,
    private val environment: Environment,
) {
    private val objectMapper = ObjectMapper()

    private var archiveResponse: JsonNode? = null

    @Before
    fun resetTheItsmScript() {
        itsm.script.reset()
        sync.awaitIdle(FakeItsmConnector.NAME)
    }

    /**
     * The graph's Ontology node outlives every scenario (the graph is emptied around it), so one moved
     * back to an older version is put where the build left it, whatever the scenario did.
     */
    @After
    fun restoreTheOntologyVersion() {
        neo4jClient
            .query("MATCH (o:Ontology) SET o.version = \$version")
            .bindAll(mapOf("version" to registry.version))
            .run()
    }

    @Given("Repository {string} was stated with description {string} at {string}")
    fun aRepositoryWasStated(
        key: String,
        description: String,
        at: String,
    ) = stateRepository(key, description, Instant.parse(at))

    @When("Repository {string} is stated with description {string} at {string}")
    fun aRepositoryIsStated(
        key: String,
        description: String,
        at: String,
    ) = stateRepository(key, description, Instant.parse(at))

    @Then("the Repository {string} reads with description {string}")
    fun theRepositoryReads(
        key: String,
        description: String,
    ) {
        world.get("/api/v1/nodes/Repository/$key")
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        assertEquals(
            description,
            world
                .lastBody()
                .path("props")
                .path("description")
                .asText(null),
        ) { world.lastResponse().body }
    }

    @Then("the Repository {string} reads as of {string} with description {string}")
    fun theRepositoryReadsAsOf(
        key: String,
        asOf: String,
        description: String,
    ) {
        world.getExpanding("/api/v1/nodes/Repository/$key?asOf={asOf}", asOf)
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        assertEquals(
            description,
            world
                .lastBody()
                .path("props")
                .path("description")
                .asText(null),
        ) { world.lastResponse().body }
    }

    @Then("the history of Repository {string} holds {int} earlier version, ending at {string}")
    fun theHistoryHoldsOneVersion(
        key: String,
        count: Int,
        endingAt: String,
    ) {
        val versions = history("Repository", key).path("versions")
        assertEquals(count, versions.size()) { "versions: $versions" }
        assertEquals(Instant.parse(endingAt), Instant.parse(versions[0].path("validTo").asText())) { "versions: $versions" }
    }

    @Then("the history of Repository {string} holds {int} earlier versions")
    fun theHistoryHoldsVersions(
        key: String,
        count: Int,
    ) {
        val versions = history("Repository", key).path("versions")
        assertEquals(count, versions.size()) { "versions: $versions" }
    }

    @Given("the lifecycle status shows connector {string} with grace period {string}")
    fun theLifecycleStatusShowsGrace(
        connector: String,
        grace: String,
    ) {
        world.get("/api/v1/lifecycle")
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        val entry = world.lastBody().path("connectors").firstOrNull { it.path("name").asText() == connector }
        assertNotNull(entry) { "no connector $connector in " + world.lastResponse().body }
        assertEquals(grace, entry!!.path("gracePeriod").asText(null)) { "connector: $entry" }
        assertEquals("tombstone", entry.path("missingFromFullSync").asText(null)) { "connector: $entry" }
    }

    @Given("a full sync of {string} recorded Teams {string} and {string}")
    fun aFullSyncRecordedTeams(
        connector: String,
        first: String,
        second: String,
    ) {
        requireItsm(connector)
        itsm.script.pages = listOf({ GraphDelta(nodes = listOf(team(first), team(second))) })
        sync.awaitRun("SUCCESS", sync.startSync(connector, "full"))
    }

    @Given("{string} last stated Team {string} {int} days ago and Team {string} {int} days ago")
    fun lastStatedDaysAgo(
        connector: String,
        first: String,
        firstDays: Int,
        second: String,
        secondDays: Int,
    ) {
        requireItsm(connector)
        lastStated("Team", first, Instant.now().minus(Duration.ofDays(firstDays.toLong())))
        lastStated("Team", second, Instant.now().minus(Duration.ofDays(secondDays.toLong())))
    }

    // Given or When alike: Cucumber ignores the keyword, so one annotation serves both.
    @When("a full sync of {string} succeeds without mentioning them")
    fun aFullSyncSucceedsWithoutThem(connector: String) {
        requireItsm(connector)
        // Something else, so the run wrote a page and succeeded rather than saw nothing at all.
        itsm.script.pages = listOf({ GraphDelta(nodes = listOf(team("unrelated"))) })
        sync.awaitRun("SUCCESS", sync.startSync(connector, "full"))
    }

    @When("a full sync of {string} ends PARTIAL without mentioning them")
    fun aFullSyncEndsPartial(connector: String) {
        requireItsm(connector)
        itsm.script.pages =
            listOf(
                { GraphDelta(nodes = listOf(team("unrelated"))) },
                { throw IllegalStateException("the source system fell over mid-page") },
            )
        sync.awaitRun("PARTIAL", sync.startSync(connector, "full"))
    }

    @When("a full sync of {string} reports Team {string} again")
    fun aFullSyncReportsAgain(
        connector: String,
        name: String,
    ) {
        requireItsm(connector)
        itsm.script.pages = listOf({ GraphDelta(nodes = listOf(team(name))) })
        sync.awaitRun("SUCCESS", sync.startSync(connector, "full"))
    }

    @Then("Team {string} is retired with reason {string}")
    fun theTeamIsRetired(
        name: String,
        reason: String,
    ) = assertRetired("Team", name, reason)

    @Then("Repository {string} is retired with reason {string}")
    fun theRepositoryIsRetired(
        key: String,
        reason: String,
    ) = assertRetired("Repository", key, reason)

    @Then("Team {string} is still current")
    fun theTeamIsStillCurrent(name: String) {
        val current = history("Team", name).path("current")
        assertFalse(current.says("validTo")) { "Team $name was retired: $current" }
        assertFalse(current.path("retired").asBoolean(true)) { "Team $name: $current" }
    }

    @Then("Team {string} is current again, with resurrectedAt set")
    fun theTeamIsCurrentAgain(name: String) {
        val current = history("Team", name).path("current")
        assertFalse(current.says("validTo")) { "Team $name is still retired: $current" }
        assertTrue(current.says("resurrectedAt")) { "Team $name: $current" }
        assertFalse(current.says("retiredReason")) { "Team $name kept its retirement reason: $current" }
    }

    @Then("the history of Team {string} lists a retired version")
    fun theHistoryListsARetiredVersion(name: String) {
        val versions = history("Team", name).path("versions")
        val retired = versions.firstOrNull { it.path("retired").asBoolean(false) }
        assertNotNull(retired) { "no retired version: $versions" }
        assertEquals("missing-from-sync", retired!!.path("retiredReason").asText(null)) { "version: $retired" }
    }

    @Given("the fake connector recorded Repository {string} OWNED_BY Team {string}")
    fun theFakeConnectorRecordedOwnership(
        key: String,
        team: String,
    ) {
        fake.pages =
            listOf({
                GraphDelta(
                    nodes = listOf(repositoryUpsert(key), team(team)),
                    edges = listOf(EdgeUpsert(type = "OWNED_BY", from = NodeKey("Repository", key), to = NodeKey("Team", team))),
                )
            })
        sync.awaitRun("SUCCESS", sync.startSync("fake", "incremental"))
    }

    @When("the fake connector reports a tombstone for Repository {string}")
    fun theFakeConnectorReportsATombstone(key: String) {
        fake.pages = listOf({ GraphDelta(tombstones = listOf(NodeKey("Repository", key))) })
        sync.awaitRun("SUCCESS", sync.startSync("fake", "incremental"))
    }

    @Then("the OWNED_BY edge from Repository {string} is closed")
    fun theEdgeIsClosed(key: String) {
        world.getExpanding("/api/v1/edges?nodeId={id}&direction=out", "Repository:$key")
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        val edge = world.lastBody().path("items").firstOrNull { it.path("type").asText() == "OWNED_BY" }
        assertNotNull(edge) { "no OWNED_BY edge: " + world.lastResponse().body }
        assertTrue(edge!!.path("provenance").says("validTo")) { "the edge is still current: $edge" }
    }

    @Given("{int} CloudResources retired {int} days ago and {int} retired {int} days ago")
    fun cloudResourcesRetired(
        old: Int,
        oldDays: Int,
        recent: Int,
        recentDays: Int,
    ) {
        for (index in 1..old) retiredCloudResource("old-$index", oldDays)
        for (index in 1..recent) retiredCloudResource("recent-$index", recentDays)
    }

    @When("an admin asks for an archive dry run")
    fun anAdminAsksForADryRun() {
        world.post("/api/v1/lifecycle/archive?dryRun=true", null)
        archiveResponse = world.lastBody()
    }

    @When("an admin runs the archive")
    fun anAdminRunsTheArchive() {
        world.post("/api/v1/lifecycle/archive", null)
        archiveResponse = world.lastBody()
    }

    @Then("the archive would take {int} nodes")
    fun theArchiveWouldTake(nodes: Int) {
        val body = checkNotNull(archiveResponse)
        assertTrue(body.path("dryRun").asBoolean(false)) { "not a dry run: $body" }
        assertEquals(nodes, body.path("wouldArchive").path("nodes").asInt(-1)) { "answer: $body" }
    }

    @Then("all {int} CloudResources are still in the graph")
    fun allCloudResourcesRemain(count: Int) {
        assertEquals(count.toLong(), countOf("CloudResource"))
    }

    @Then("the archive file it names holds {int} nodes")
    fun theArchiveFileHolds(nodes: Int) {
        val body = checkNotNull(archiveResponse)
        assertFalse(body.path("dryRun").asBoolean(true)) { "a dry run: $body" }
        val file = body.path("file").asText(null)
        assertNotNull(file) { "no archive file named: $body" }
        val directory = Path.of(checkNotNull(environment.getProperty("lifecycle.archive.directory")))
        val lines = Files.readAllLines(directory.resolve(file!!)).filter { it.isNotBlank() }.map(objectMapper::readTree)
        assertEquals(nodes, lines.count { it.path("kind").asText() == "node" }) { "lines: $lines" }
        assertTrue(lines.filter { it.path("kind").asText() == "node" }.all { it.path("type").asText() == "CloudResource" })
    }

    @Then("the {int} old CloudResources are gone and the {int} recent ones are still retired")
    fun theOldAreGone(
        old: Int,
        recent: Int,
    ) {
        assertEquals(recent.toLong(), countOf("CloudResource")) { "CloudResources left in the graph" }
        for (index in 1..old) assertEquals(null, graphStore.findNode(cloudResourceKey("old-$index")))
        for (index in 1..recent) {
            val node = graphStore.findNode(cloudResourceKey("recent-$index"))
            assertNotNull(node?.provenance?.validTo) { "recent-$index is not retired: $node" }
        }
    }

    @Then("a sync run of {string} recorded {int} nodes")
    fun aSyncRunRecorded(
        connector: String,
        nodes: Int,
    ) {
        world.getExpanding("/api/v1/sync-runs?connector={connector}", connector)
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        val run = world.lastBody().path("items").firstOrNull()
        assertNotNull(run) { "no run of $connector: " + world.lastResponse().body }
        assertEquals(nodes, run!!.path("nodesUpserted").asInt(-1)) { "run: $run" }
        assertEquals("SUCCESS", run.path("status").asText(null)) { "run: $run" }
    }

    @Given("the graph is on ontology version {string}")
    fun theGraphIsOnVersion(version: String) {
        neo4jClient.query("MATCH (o:Ontology) SET o.version = \$version").bindAll(mapOf("version" to version)).run()
    }

    @Given("a ConfigurationItem {string} holds the legacy name {string}")
    fun aConfigurationItemHoldsTheLegacyName(
        key: String,
        name: String,
    ) {
        neo4jClient
            .query(
                """
                CREATE (c:ConfigurationItem { key: ${'$'}key, id: 'ConfigurationItem:' + ${'$'}key, name: ${'$'}name,
                  sourceSystem: 'servicenow', instance: 'sn.example.test', sysId: 'a1',
                  prov_sourceSystem: 'servicenow', prov_ingestedAt: ${'$'}at, prov_validFrom: ${'$'}at,
                  prov_confidence: 1.0, prov_inferred: false })
                """.trimIndent(),
            ).bindAll(mapOf("key" to key, "name" to name, "at" to Instant.now().atZone(ZoneOffset.UTC)))
            .run()
    }

    @Then("the migration {string} is pending")
    fun theMigrationIsPending(migration: String) {
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        val pending =
            world.lastBody().path("pending").map {
                "V" + it.path("version").asText().replace('.', '_') + "__" +
                    it.path("name").asText()
            }
        assertTrue(migration in pending) { "pending: " + world.lastBody() }
    }

    @Given("migration {string} was recorded as applied with a checksum of another file")
    fun aMigrationWasRecordedWithAnotherChecksum(version: String) {
        neo4jClient
            .query(
                """
                CREATE (m:OntologyMigration { key: ${'$'}version, id: 'OntologyMigration:' + ${'$'}version, version: ${'$'}version,
                  name: 'rename_ci_legacy_name', checksum: ${'$'}checksum, appliedAt: ${'$'}at, durationMs: 1,
                  prov_sourceSystem: 'sdlc-knowledge-graph', prov_ingestedAt: ${'$'}at, prov_validFrom: ${'$'}at,
                  prov_confidence: 1.0, prov_inferred: false })
                """.trimIndent(),
            ).bindAll(mapOf("version" to version, "checksum" to "0".repeat(CHECKSUM_LENGTH), "at" to Instant.now().atZone(ZoneOffset.UTC)))
            .run()
    }

    @When("an admin applies the pending migrations")
    fun anAdminAppliesThePendingMigrations() {
        world.post("/api/v1/lifecycle/migrations/apply", null)
    }

    @Then("the ConfigurationItem {string} has ciName {string} and no name")
    fun theConfigurationItemWasMigrated(
        key: String,
        ciName: String,
    ) {
        val row =
            neo4jClient
                .query("MATCH (c:ConfigurationItem { key: \$key }) RETURN c.ciName AS ciName, c.name AS name")
                .bindAll(mapOf("key" to key))
                .fetch()
                .one()
                .orElseThrow()
        assertEquals(ciName, row["ciName"])
        assertEquals(null, row["name"])
    }

    @Then("the graph is on ontology version {string} with {string} applied and checksummed")
    fun theGraphIsOnVersionWithApplied(
        version: String,
        migration: String,
    ) {
        world.get("/api/v1/lifecycle/migrations")
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        val body = world.lastBody()
        assertEquals(version, body.path("dbVersion").asText(null)) { "migrations: $body" }
        assertEquals(0, body.path("pending").size()) { "migrations: $body" }
        val applied =
            body.path("applied").firstOrNull {
                "V" + it.path("version").asText().replace('.', '_') + "__" +
                    it.path("name").asText() ==
                    migration
            }
        assertNotNull(applied) { "not applied: $body" }
        assertTrue(Regex("^[0-9a-f]{64}$").matches(applied!!.path("checksum").asText())) { "checksum: $applied" }
    }

    @Then("the body names {string} and {string}")
    fun theBodyNames(
        first: String,
        second: String,
    ) {
        val body = world.lastResponse().body.orEmpty()
        assertTrue(first in body && second in body) { "body: $body" }
    }

    private fun stateRepository(
        key: String,
        description: String,
        at: Instant,
    ) {
        val (host, org, name) = key.split("/")
        graphStore.upsertNode(
            GraphNode(
                key = NodeKey("Repository", key),
                props =
                    mapOf(
                        "url" to "https://$key",
                        "host" to host,
                        "org" to org,
                        "name" to name,
                        "defaultBranch" to "main",
                        "topics" to emptyList<String>(),
                        "codeowners" to emptyList<String>(),
                        "description" to description,
                    ),
                provenance = Provenance(sourceSystem = Provenance.MANUAL, ingestedAt = at, validFrom = at),
            ),
        )
    }

    private fun history(
        type: String,
        key: String,
    ): JsonNode {
        world.getExpanding("/api/v1/lifecycle/history?nodeId={id}", "$type:$key")
        assertEquals(200, world.lastStatus()) { world.lastResponse().body }
        return world.lastBody()
    }

    private fun assertRetired(
        type: String,
        key: String,
        reason: String,
    ) {
        val current = history(type, key).path("current")
        assertTrue(current.says("validTo")) { "$type $key is not retired: $current" }
        assertTrue(current.path("retired").asBoolean(false)) { "$type $key: $current" }
        assertEquals(reason, current.path("retiredReason").asText(null)) { "$type $key: $current" }
    }

    private fun lastStated(
        label: String,
        key: String,
        at: Instant,
    ) {
        // The label is this class's own, never a scenario's text.
        val updated =
            neo4jClient
                .query("MATCH (n:$label { key: \$key }) SET n.prov_ingestedAt = \$at RETURN count(n) AS c")
                .bindAll(mapOf("key" to key, "at" to at.atZone(ZoneOffset.UTC)))
                .fetchAs(Long::class.javaObjectType)
                .one()
                .orElse(0L)
        assertEquals(1L, updated) { "no $label with key $key" }
    }

    private fun retiredCloudResource(
        name: String,
        daysAgo: Int,
    ) {
        val retiredAt = Instant.now().minus(Duration.ofDays(daysAgo.toLong()))
        val began = retiredAt.minus(Duration.ofDays(RESOURCE_LIFETIME_DAYS))
        graphStore.upsertNode(
            GraphNode(
                key = cloudResourceKey(name),
                props = mapOf("provider" to "aws", "resourceId" to "arn:aws:s3:::$name", "resourceType" to "s3-bucket", "name" to name),
                provenance = Provenance(sourceSystem = "aws", ingestedAt = began, validFrom = began, validTo = retiredAt),
            ),
        )
    }

    private fun cloudResourceKey(name: String) = NodeKey("CloudResource", "aws:arn:aws:s3:::$name")

    private fun countOf(label: String): Long =
        neo4jClient
            .query("MATCH (n:$label) RETURN count(n) AS c")
            .fetchAs(Long::class.javaObjectType)
            .one()
            .orElse(0L)

    private fun requireItsm(connector: String) {
        assertEquals(FakeItsmConnector.NAME, connector) { "these steps script only ${FakeItsmConnector.NAME}" }
    }

    private fun team(name: String) = NodeUpsert(type = "Team", props = mapOf("name" to name))

    private fun repositoryUpsert(key: String) =
        NodeUpsert(
            type = "Repository",
            props =
                mapOf(
                    "url" to "https://$key",
                    "defaultBranch" to "main",
                    "topics" to emptyList<String>(),
                    "codeowners" to emptyList<String>(),
                ),
        )

    private companion object {
        const val CHECKSUM_LENGTH = 64
        const val RESOURCE_LIFETIME_DAYS = 30L
    }
}
