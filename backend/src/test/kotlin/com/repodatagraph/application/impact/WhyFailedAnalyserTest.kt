package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.DeploymentFacts
import com.repodatagraph.domain.model.DeploymentRecord
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * "Why did the deployment fail" (#21, FR4 and FR5): the window runs from the last successful
 * deployment of the same repository to the same environment up to this one, and a dependency that
 * was deployed to that environment inside the window is a change worth looking at.
 */
class WhyFailedAnalyserTest {
    private val analyser = WhyFailedAnalyser()

    private fun at(hour: Int): Instant = Instant.parse("2026-09-02T00:00:00Z").plusSeconds(hour * 3600L)

    private fun node(id: String) =
        GraphNode(NodeKey.parse(id), emptyMap(), Provenance(sourceSystem = "manual", ingestedAt = at(0), validFrom = at(0)))

    private val payments = NodeKey("Repository", "github.com/acme/payments")
    private val sharedLib = NodeKey("Repository", "github.com/acme/shared-lib")
    private val ledger = NodeKey("Repository", "github.com/acme/ledger")

    private fun record(
        id: String,
        repository: NodeKey,
        commitSha: String,
        hour: Int,
        status: String = "SUCCESS",
    ) = DeploymentRecord("Deployment:$id", repository, commitSha, at(hour), status)

    private fun facts(
        status: String = "FAILED",
        hour: Int = 10,
        history: List<DeploymentRecord> = emptyList(),
        dependencies: List<DeploymentRecord> = emptyList(),
    ) = DeploymentFacts(
        deployment = node("Deployment:this"),
        status = status,
        deployedAt = at(hour),
        artifact = node("Artifact:ghcr.io/acme/payments@sha256:ccc"),
        commitSha = "c2",
        repository = node(payments.id),
        pipeline = null,
        environment = node("Environment:staging"),
        history = history + record("this", payments, "c2", hour, status),
        dependencyDeployments = dependencies,
    )

    @Test
    fun `the preceding success is the latest successful deployment before this one`() {
        val result =
            analyser.analyse(
                facts(
                    history =
                        listOf(
                            record("old", payments, "c0", 1),
                            record("recent", payments, "c1", 5),
                            record("broken", payments, "c1b", 7, "FAILED"),
                            record("later", payments, "c3", 12),
                        ),
                ),
            )

        assertEquals("Deployment:recent", result.precedingSuccessfulDeployment?.id)
        assertEquals("c1", result.precedingSuccessfulDeployment?.commitSha)
    }

    @Test
    fun `a dependency deployed inside the window is a changed dependency, one outside it is not`() {
        val result =
            analyser.analyse(
                facts(
                    history = listOf(record("recent", payments, "c1", 5)),
                    dependencies =
                        listOf(
                            record("before", sharedLib, "s8", 4),
                            record("inside", sharedLib, "s9", 9),
                            record("after", ledger, "l2", 11),
                        ),
                ),
            )

        assertEquals(listOf("Deployment:inside"), result.changedDependencies.map { it.id })
        assertEquals(sharedLib, result.changedDependencies.single().repository)
    }

    @Test
    fun `a dependency deployed at the same instant as the failure is inside the window`() {
        val result =
            analyser.analyse(
                facts(history = listOf(record("recent", payments, "c1", 5)), dependencies = listOf(record("same", ledger, "l1", 10))),
            )

        assertEquals(listOf("Deployment:same"), result.changedDependencies.map { it.id })
    }

    @Test
    fun `with no earlier success every earlier dependency deployment counts, and that is a reason`() {
        val result = analyser.analyse(facts(dependencies = listOf(record("early", sharedLib, "s1", 1))))

        assertNull(result.precedingSuccessfulDeployment)
        assertEquals(listOf("Deployment:early"), result.changedDependencies.map { it.id })
        assertTrue(result.reasons.any { it.kind == "NO_PRECEDING_SUCCESS" }) { "reasons: ${result.reasons}" }
    }

    @Test
    fun `the reasons name the commit change and each changed dependency`() {
        val result =
            analyser.analyse(
                facts(
                    history = listOf(record("recent", payments, "c1", 5)),
                    dependencies = listOf(record("inside", sharedLib, "s9", 9)),
                ),
            )

        assertEquals(listOf("COMMIT_CHANGED", "DEPENDENCY_CHANGED"), result.reasons.map { it.kind })
        assertTrue(result.reasons[0].detail.contains("c1") && result.reasons[0].detail.contains("c2"))
        assertTrue(result.reasons[1].detail.contains("github.com/acme/shared-lib") && result.reasons[1].detail.contains("s9"))
    }

    @Test
    fun `changed dependencies are listed most recent first`() {
        val result =
            analyser.analyse(
                facts(
                    history = listOf(record("recent", payments, "c1", 5)),
                    dependencies = listOf(record("first", sharedLib, "s9", 6), record("second", ledger, "l2", 8)),
                ),
            )

        assertEquals(listOf("Deployment:second", "Deployment:first"), result.changedDependencies.map { it.id })
    }

    @Test
    fun `a deployment that succeeded has no reasons and no changed dependencies`() {
        val result =
            analyser.analyse(
                facts(
                    status = "SUCCESS",
                    history = listOf(record("recent", payments, "c1", 5)),
                    dependencies = listOf(record("inside", sharedLib, "s9", 9)),
                ),
            )

        assertEquals("SUCCESS", result.status)
        assertTrue(result.reasons.isEmpty())
        assertTrue(result.changedDependencies.isEmpty())
    }

    @Test
    fun `the lineage is carried through`() {
        val result = analyser.analyse(facts())

        assertEquals("Deployment:this", result.deployment.id)
        assertEquals("c2", result.commitSha)
        assertEquals(payments.id, result.repository?.id)
        assertEquals("Environment:staging", result.environment?.id)
        assertEquals(at(10), result.deployedAt)
    }
}
