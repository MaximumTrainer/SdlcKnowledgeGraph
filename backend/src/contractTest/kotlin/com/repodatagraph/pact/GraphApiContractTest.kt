package com.repodatagraph.pact

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

/**
 * Replays every interaction the frontend recorded in `contracts/pacts/` against a real, running
 * backend on a real Neo4j.
 *
 * There is no broker (ADR-0004): the pact files are committed, so a consumer expectation and the
 * provider that has to satisfy it move in the same pull request. Provider states name the graph each
 * interaction assumes; the handlers are in [ProviderStates].
 */
@Provider("sdlc-graph-backend")
@PactFolder("../contracts/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Neo4jTestcontainersConfig::class)
class GraphApiContractTest {
    @LocalServerPort
    private var port: Int = 0

    @BeforeEach
    fun setTarget(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", port)
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider::class)
    fun verifyPactInteraction(context: PactVerificationContext) {
        context.verifyInteraction()
    }
}
