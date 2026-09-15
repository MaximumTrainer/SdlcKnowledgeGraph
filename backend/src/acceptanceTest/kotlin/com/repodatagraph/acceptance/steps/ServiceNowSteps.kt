package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.acceptance.support.SyncWorld
import com.repodatagraph.acceptance.support.says
import com.repodatagraph.adapter.out.servicenow.ServiceNowTime
import com.repodatagraph.support.connector.FakeServiceNow
import com.repodatagraph.support.connector.ServiceNowRows
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Instant

/**
 * Drives the ServiceNow connector against a fake CMDB over real HTTP.
 *
 * What is being proved is not that the Table API works. It is that a CI, a change and an incident end
 * up pointing at each other the way the CMDB says they do, that a relationship to something outside
 * the synced tables is skipped rather than written as a dangling edge, and that a repository nobody
 * has ever ingested is not invented from a text field.
 */
class ServiceNowSteps(
    private val world: ApiWorld,
    private val sync: SyncWorld,
    private val servicenow: FakeServiceNow,
) {
    /** Rows accumulate per scenario, so a CI added after its relationship still ends up in the table. */
    private var lastCi: Pair<String, String>? = null

    /** The watermark the previous run left, which is what the next one should query from. */
    private var watermarkBefore: String? = null

    @Before
    fun resetTheFake() {
        servicenow.reset()
        sync.awaitIdle(CONNECTOR)
    }

    @Given("the ServiceNow connector is registered")
    fun theConnectorIsRegistered() {
        world.get("/api/v1/connectors/$CONNECTOR")
        assertEquals(OK, world.lastStatus()) { "the servicenow connector is not registered: " + world.lastResponse().body }
    }

    @Given("ServiceNow has a {string} called {string} with sys_id {string}")
    fun serviceNowHasACi(
        table: String,
        name: String,
        sysId: String,
    ) {
        lastCi = table to sysId
        servicenow.has(table, listOf(ServiceNowRows.ci(sysId = sysId, name = name, ciClass = table)))
    }

    @Given("that configuration item names the repository {string}")
    fun thatCiNamesTheRepository(url: String) {
        restate { table, sysId, name -> ServiceNowRows.ci(sysId = sysId, name = name, ciClass = table, repositoryUrl = url) }
    }

    @Given("that configuration item is {string}")
    fun thatCiHasStatus(status: String) {
        restate { table, sysId, name -> ServiceNowRows.ci(sysId = sysId, name = name, ciClass = table, status = status) }
    }

    @Given("ServiceNow relates {string} to {string} as {string}")
    fun serviceNowRelates(
        parent: String,
        child: String,
        type: String,
    ) {
        servicenow.has(
            "cmdb_rel_ci",
            listOf(ServiceNowRows.relationship(sysId = "rel-$parent-$child", parent = parent, child = child, type = type)),
        )
    }

    @Given("ServiceNow has change {string} with sys_id {string} affecting {string}")
    fun serviceNowHasChange(
        number: String,
        sysId: String,
        affects: String,
    ) {
        servicenow.has("change_request", listOf(ServiceNowRows.change(sysId = sysId, number = number, affects = affects)))
    }

    @Given("ServiceNow has incident {string} with sys_id {string} affecting {string} caused by {string}")
    fun serviceNowHasIncident(
        number: String,
        sysId: String,
        affects: String,
        causedBy: String,
    ) {
        servicenow.has(
            "incident",
            listOf(ServiceNowRows.incident(sysId = sysId, number = number, affects = affects, causedBy = causedBy)),
        )
    }

    @Given("ServiceNow has {int} {string} rows")
    fun serviceNowHasManyRows(
        count: Int,
        table: String,
    ) {
        servicenow.has(table, (1..count).map { ServiceNowRows.ci(sysId = "ci-$it", name = "app-$it", ciClass = table) })
    }

    @Given("a full sync of ServiceNow has already run")
    fun aFullSyncHasAlreadyRun() {
        sync.awaitRun("SUCCESS", sync.startSync(CONNECTOR))
        // Read now, because the incremental run will move it on: what the next run *queried from* is
        // the watermark this run left, not the one it will leave.
        world.get("/api/v1/connectors/$CONNECTOR")
        watermarkBefore =
            world
                .lastBody()
                .path("state")
                .path("watermark")
                .asText()
        // Forgotten, so the next assertion is about what the incremental run asked and not about the
        // full one that set the watermark it is asking from.
        servicenow.forgetRequests()
    }

    @When("I ask the ServiceNow connector for a full sync")
    fun iAskForAFullSync() {
        sync.startSync(CONNECTOR)
    }

    @When("I ask the ServiceNow connector for an incremental sync")
    fun iAskForAnIncrementalSync() {
        sync.startSync(CONNECTOR, "incremental")
    }

    @Then("the graph has ConfigurationItem {string}")
    fun theGraphHasCi(key: String) {
        world.get("/api/v1/nodes/ConfigurationItem/$key")
        assertEquals(OK, world.lastStatus()) { "no ConfigurationItem $key: " + world.lastResponse().body }
    }

    @Then("that ConfigurationItem has ciClass {string} and provenance sourceSystem {string}")
    fun thatCiHasClassAndProvenance(
        ciClass: String,
        sourceSystem: String,
    ) {
        assertEquals(
            ciClass,
            world
                .lastBody()
                .path("props")
                .path("ciClass")
                .asText(),
        )
        assertEquals(
            sourceSystem,
            world
                .lastBody()
                .path("provenance")
                .path("sourceSystem")
                .asText(),
        )
    }

    @Then("that ConfigurationItem has its validity closed")
    fun thatCiIsClosed() {
        assertTrue(world.lastBody().path("provenance").says("validTo")) {
            "a retired configuration item was left open: " + world.lastResponse().body
        }
    }

    @Then("{string} RELATES_TO_CI {string} with confidence {double}")
    fun relatesToCi(
        repoKey: String,
        ciKey: String,
        confidence: Double,
    ) {
        val edge = edgeFrom("Repository", repoKey, "RELATES_TO_CI", ciKey)
        assertEquals(confidence, edge.path("provenance").path("confidence").asDouble())
    }

    @Then("{string} is related to no repository")
    fun relatedToNoRepository(ciKey: String) {
        val edges = edges("ConfigurationItem", ciKey, "RELATES_TO_CI")
        assertTrue(edges.isEmpty()) { "a repository nobody has ingested was linked anyway: $edges" }
    }

    @Then("{string} DEPENDS_ON {string} with kind {string}")
    fun dependsOnWithKind(
        fromKey: String,
        toKey: String,
        kind: String,
    ) {
        val edge = edgeFrom("ConfigurationItem", fromKey, "DEPENDS_ON", toKey)
        assertEquals(kind, edge.path("props").path("kind").asText())
    }

    @Then("{string} AFFECTS {string}")
    fun affects(
        fromKey: String,
        toKey: String,
    ) {
        val type = if (fromKey.contains(":c")) "ChangeRequest" else "Incident"
        edgeFrom(type, fromKey, "AFFECTS", toKey)
    }

    @Then("{string} CAUSED_BY {string}")
    fun causedBy(
        incidentKey: String,
        changeKey: String,
    ) {
        edgeFrom("Incident", incidentKey, "CAUSED_BY", changeKey)
    }

    @Then("ServiceNow was asked for rows updated since the stored watermark")
    fun serviceNowWasAskedForRowsSinceTheWatermark() {
        val stored = checkNotNull(watermarkBefore) { "no earlier run left a watermark to ask from" }
        assertTrue(stored.isNotBlank()) { "the earlier run stored no watermark" }

        val since = ServiceNowTime.format(Instant.parse(stored))
        val queries =
            servicenow
                .requests()
                // The health check reads sys_properties with no window, which is not a sync of
                // anything and would make this assertion fail for the wrong reason.
                .filterNot { it.url.contains(HEALTH_TABLE) }
                .mapNotNull { it.queryParameter("sysparm_query").values().firstOrNull() }
        // Every table, not just one: a connector that windowed its CIs and then read every change
        // ever raised would look incremental and cost the same as a full sync.
        assertTrue(queries.isNotEmpty() && queries.all { it.contains("sys_updated_on>=$since") }) {
            "the queries were $queries, expected every one to start from $since"
        }
    }

    @Then("the graph has no Repository {string}")
    fun theGraphHasNoRepository(key: String) {
        world.get("/api/v1/nodes/Repository/$key")
        assertEquals(NOT_FOUND, world.lastStatus()) { "a repository was invented from a CMDB text field: $key" }
    }

    private fun restate(row: (table: String, sysId: String, name: String) -> String) {
        val (table, sysId) = checkNotNull(lastCi) { "no configuration item has been declared yet" }
        servicenow.reset()
        servicenow.has(table, listOf(row(table, sysId, sysId)))
    }

    private fun edgeFrom(
        fromType: String,
        fromKey: String,
        type: String,
        toKey: String,
    ): JsonNode {
        val edges = edges(fromType, fromKey, type)
        val match = edges.firstOrNull { it.path("other").path("key").asText() == toKey }
        return checkNotNull(match) {
            "$fromKey has no $type to $toKey; it has " + edges.map { it.path("other").path("key").asText() }
        }
    }

    /** Only what is still current: a closed edge is history, not a relationship. */
    private fun edges(
        fromType: String,
        fromKey: String,
        type: String,
    ): List<JsonNode> {
        world.get("/api/v1/edges?nodeId=$fromType:$fromKey&direction=out&edgeType=$type")
        return world
            .lastBody()
            .path("items")
            .filterNot { it.path("provenance").says("validTo") }
    }

    private companion object {
        const val CONNECTOR = "servicenow"
        const val OK = 200
        const val NOT_FOUND = 404
        const val HEALTH_TABLE = "sys_properties"
    }
}
