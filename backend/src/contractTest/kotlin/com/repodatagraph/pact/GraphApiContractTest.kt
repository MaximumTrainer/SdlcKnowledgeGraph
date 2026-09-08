package com.repodatagraph.pact

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.core.Neo4jClient

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

    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    private val states: ProviderStates by lazy { ProviderStates(graphStore, neo4jClient) }

    @BeforeEach
    fun setTarget(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", port)
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider::class)
    fun verifyPactInteraction(context: PactVerificationContext) {
        context.verifyInteraction()
    }

    // Pact looks up state handlers on the test class, so these delegate to ProviderStates, which is
    // where the seeding lives and what ProviderStatesTest checks against the pacts.
    @State(ProviderStates.REPOSITORY_R1_EXISTS)
    fun repositoryR1Exists() = states.repositoryR1Exists()

    @State(ProviderStates.NO_REPOSITORIES_EXIST)
    fun noRepositoriesExist() = states.noRepositoriesExist()

    @State(ProviderStates.NO_TEAMS_EXIST)
    fun noTeamsExist() = states.noTeamsExist()

    @State(ProviderStates.TEAM_PLATFORM_EXISTS)
    fun teamPlatformExists() = states.teamPlatformExists()
}
