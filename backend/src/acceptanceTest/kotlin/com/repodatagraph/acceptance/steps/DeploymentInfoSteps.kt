package com.repodatagraph.acceptance.steps

import com.fasterxml.jackson.databind.JsonNode
import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** Steps over the `deployment` block of `/actuator/info` (docs/DEPLOYMENT.md, D2). */
class DeploymentInfoSteps(
    private val world: ApiWorld,
) {
    @Then("deployment.commit is a 40-character hex string")
    fun theCommitIsAFullSha() {
        val commit = deployment().path("commit").asText()
        assertTrue(FULL_SHA.matches(commit)) { "expected a 40-character hex commit, got '$commit'" }
    }

    @Then("deployment.ontologyVersion equals the version the ontology endpoint serves")
    fun theOntologyVersionMatchesTheServedOntology() {
        val reported = deployment().path("ontologyVersion").asText(null)
        world.get("/api/v1/ontology")
        val served = world.lastBody().path("version").asText(null)
        assertEquals(served, reported)
    }

    @Then("deployment.version is not blank")
    fun theVersionIsNotBlank() {
        val version = deployment().path("version").asText("")
        assertTrue(version.isNotBlank() && version != "null") { "expected a version, got '$version'" }
    }

    @Then("deployment.profile is {string}")
    fun theProfileIs(expected: String) {
        assertEquals(expected, deployment().path("profile").asText(null))
    }

    @Then("deployment.readOnly is {word}")
    fun theReadOnlyFlagIs(expected: String) {
        val flag = deployment().path("readOnly")
        assertTrue(flag.isBoolean) { "expected readOnly to be a boolean, got $flag" }
        assertEquals(expected.toBooleanStrict(), flag.booleanValue())
    }

    @Then("deployment.authentication is {string}")
    fun theAuthenticationIs(expected: String) {
        assertEquals(expected, deployment().path("authentication").asText(null))
    }

    // Kept from the first step that reads it, because the ontology step makes a request of its own
    // and every step after it would otherwise be reading the ontology. Cucumber builds this class
    // afresh for each scenario, so nothing carries over between them.
    private val info: JsonNode by lazy { world.lastBody().path("deployment") }

    private fun deployment(): JsonNode = info

    private companion object {
        val FULL_SHA = Regex("^[0-9a-f]{40}$")
    }
}
