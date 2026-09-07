package com.repodatagraph.pact

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the seam between the two halves of the contract.
 *
 * A consumer can invent a provider state name in one line of TypeScript, and the only thing that
 * notices is a verification failure hours later with a message about an unimplemented state. This
 * reads the committed pacts and asserts that every state they name has a handler here, and that no
 * handler has been left behind by a consumer that stopped using it.
 *
 * It needs neither Docker nor a Spring context, but it lives in the contract suite because that is
 * where the class it describes lives.
 */
class ProviderStatesTest {
    private val pactDirectory = File("../contracts/pacts")

    @Test
    fun `every provider state named by a pact has a handler`() {
        assertThat(statesNamedByPacts()).isNotEmpty()
        assertThat(ProviderStates.ALL).containsAll(statesNamedByPacts())
    }

    @Test
    fun `no handler exists for a state no pact names`() {
        assertThat(statesNamedByPacts()).containsAll(ProviderStates.ALL)
    }

    private fun statesNamedByPacts(): Set<String> {
        val pacts =
            pactDirectory
                .listFiles { file -> file.extension == "json" }
                .orEmpty()
                .toList()
        assertThat(pacts).describedAs("pact files in %s", pactDirectory.canonicalPath).isNotEmpty()

        return pacts
            .flatMap { ObjectMapper().readTree(it).path("interactions").toList() }
            .flatMap { interaction -> interaction.path("providerStates").toList() }
            .map { state: JsonNode -> state.path("name").asText() }
            .toSet()
    }
}
