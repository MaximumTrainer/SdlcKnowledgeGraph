package com.repodatagraph.pact

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import com.repodatagraph.support.TestPrincipalConfig
import org.apache.hc.core5.http.HttpRequest
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
 *
 * Every interaction is replayed as a signed-in user, [TestPrincipalConfig]'s, as the web interface
 * sends it behind the login (#118). The pacts do not record the token: it is a property of the
 * deployment, not of the contract.
 */
@Provider("sdlc-graph-backend")
@PactFolder("../contracts/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Neo4jTestcontainersConfig::class, TestPrincipalConfig::class)
class GraphApiContractTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    @Autowired
    private lateinit var registry: OntologyRegistry

    private val states: ProviderStates by lazy { ProviderStates(graphStore, neo4jClient, registry.version) }

    @BeforeEach
    fun setTarget(context: PactVerificationContext) {
        context.target = HttpTestTarget("localhost", port)
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider::class)
    fun verifyPactInteraction(
        context: PactVerificationContext,
        request: HttpRequest,
    ) {
        request.setHeader("Authorization", TestPrincipalConfig.AUTHORIZATION)
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

    @State(ProviderStates.TWO_REPOSITORIES_EXIST)
    fun twoRepositoriesExist() = states.twoRepositoriesExist()

    @State(ProviderStates.PAYMENTS_DEPENDS_ON_SHARED_LIB)
    fun paymentsDependsOnSharedLib() = states.paymentsDependsOnSharedLib()

    @State(ProviderStates.R1_RELATES_TO_A_CI)
    fun repositoryR1RelatesToACi() = states.repositoryR1RelatesToACi()

    @State(ProviderStates.FAILED_SYNC_RUN_EXISTS)
    fun failedSyncRunExists() = states.failedSyncRunExists()

    @State(ProviderStates.PAYMENTS_HAS_A_NEIGHBOURHOOD)
    fun paymentsHasANeighbourhood() = states.paymentsHasANeighbourhood()

    @State(ProviderStates.PENDING_NAMING_CANDIDATE)
    fun pendingNamingCandidate() = states.pendingNamingCandidate()

    @State(ProviderStates.HUB_DEPENDS_ON_MANY)
    fun hubRepositoryDependsOnMany() = states.hubRepositoryDependsOnMany()

    @State(ProviderStates.WORK_ITEM_IS_LIVE)
    fun workItemIsLiveInProduction() = states.workItemIsLiveInProduction()

    @State(ProviderStates.DEPLOYMENT_WITHOUT_LINEAGE)
    fun deploymentWithoutLineage() = states.deploymentWithoutLineage()

    @State(ProviderStates.NO_WORK_ITEMS_EXIST)
    fun noWorkItemsExist() = states.noWorkItemsExist()

    @State(ProviderStates.PAYMENTS_HAS_GITHUB_ID)
    fun paymentsHasGitHubId() = states.paymentsHasGitHubId()

    @State(ProviderStates.PAYMENTS_WAS_RENAMED)
    fun paymentsWasRenamed() = states.paymentsWasRenamed()

    @State(ProviderStates.GITHUB_SYNCED_THIRTY_HOURS_AGO)
    fun githubSyncedThirtyHoursAgo() = states.githubSyncedThirtyHoursAgo()

    @State(ProviderStates.LIFECYCLE_AT_ITS_DEFAULTS)
    fun lifecycleAtItsDefaults() = states.lifecycleAtItsDefaults()

    @State(ProviderStates.POLICY_AT_ITS_DEFAULTS)
    fun policyAtItsDefaults() = states.policyAtItsDefaults()

    @State(ProviderStates.GRAPH_ON_PREVIOUS_ONTOLOGY)
    fun graphOnThePreviousOntologyVersion() = states.graphOnThePreviousOntologyVersion()

    @State(ProviderStates.FACT_RETIRED_LONG_AGO)
    fun factRetiredLongAgo() = states.factRetiredLongAgo()

    @State(ProviderStates.PAYMENTS_HAS_ONE_EARLIER_VERSION)
    fun paymentsHasOneEarlierVersion() = states.paymentsHasOneEarlierVersion()
}
