package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals

/** Who a write through the development bypass is recorded as (#114, FR-5). */
class AuthBypassSteps(
    private val world: ApiWorld,
) {
    @Then("the created node's provenance has writtenBy {string} and principalType {string}")
    fun theCreatedNodesProvenance(
        writtenBy: String,
        principalType: String,
    ) {
        val provenance = world.lastBody().path("provenance")
        assertEquals(writtenBy, provenance.path("writtenBy").asText(null), "writtenBy: $provenance")
        assertEquals(principalType, provenance.path("principalType").asText(null), "principalType: $provenance")
    }
}
