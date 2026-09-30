package com.repodatagraph.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Which deployments are current (#96, FR-2): what is running now in each environment is the latest
 * deployment there that succeeded. A failed or unfinished one replaced nothing, and one after the
 * instant asked about had not happened yet.
 */
class CurrentDeploymentsTest {
    private val at = Instant.parse("2026-09-01T00:00:00Z")
    private val stated = Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at)

    private fun deployment(
        name: String,
        environment: String?,
        deployedAt: String,
        status: String = "SUCCESS",
        legacyEnvironmentOnly: Boolean = false,
    ): GraphNode {
        val props =
            buildMap<String, Any?> {
                put("deployedAt", Instant.parse(deployedAt))
                put("status", status)
                if (environment != null) {
                    put("environmentId", environment)
                    if (!legacyEnvironmentOnly) put("environmentKey", environment)
                }
            }
        return GraphNode(NodeKey("Deployment", name), props, stated)
    }

    @Test
    fun `the latest successful deployment in each environment is current`() {
        val old = deployment("old", "production", "2026-09-01T10:00:00Z")
        val new = deployment("new", "production", "2026-09-02T10:00:00Z")
        val staging = deployment("staging", "staging", "2026-08-01T10:00:00Z")

        assertEquals(setOf(new.id, staging.id), CurrentDeployments.select(listOf(old, new, staging), asOf = null))
    }

    @Test
    fun `a failed or unfinished deployment replaced nothing`() {
        val running = deployment("running", "production", "2026-09-01T10:00:00Z")
        val failed = deployment("failed", "production", "2026-09-02T10:00:00Z", status = "FAILED")
        val pending = deployment("pending", "production", "2026-09-03T10:00:00Z", status = "IN_PROGRESS")

        assertEquals(setOf(running.id), CurrentDeployments.select(listOf(failed, running, pending), asOf = null))
    }

    @Test
    fun `an environment where nothing succeeded has nothing current`() {
        val failed = deployment("failed", "production", "2026-09-02T10:00:00Z", status = "FAILED")

        assertEquals(emptySet<String>(), CurrentDeployments.select(listOf(failed), asOf = null))
    }

    @Test
    fun `as of an instant, a later deployment had not happened yet`() {
        val old = deployment("old", "production", "2026-09-01T10:00:00Z")
        val new = deployment("new", "production", "2026-09-02T10:00:00Z")

        assertEquals(setOf(old.id), CurrentDeployments.select(listOf(old, new), asOf = Instant.parse("2026-09-01T12:00:00Z")))
    }

    @Test
    fun `the environment is read from environmentKey, or the environmentId it replaced`() {
        val keyed = deployment("keyed", "production", "2026-09-01T10:00:00Z")
        val legacy = deployment("legacy", "production", "2026-09-02T10:00:00Z", legacyEnvironmentOnly = true)

        assertEquals(setOf(legacy.id), CurrentDeployments.select(listOf(keyed, legacy), asOf = null))
    }

    @Test
    fun `a tie on deployedAt is broken by id, so the answer does not depend on the order given`() {
        val a = deployment("a", "production", "2026-09-01T10:00:00Z")
        val b = deployment("b", "production", "2026-09-01T10:00:00Z")

        assertEquals(CurrentDeployments.select(listOf(a, b), null), CurrentDeployments.select(listOf(b, a), null))
        assertEquals(1, CurrentDeployments.select(listOf(a, b), null).size)
    }

    @Test
    fun `a deployment with no deployedAt is never current`() {
        val undated = GraphNode(NodeKey("Deployment", "undated"), mapOf("status" to "SUCCESS", "environmentKey" to "production"), stated)

        assertEquals(emptySet<String>(), CurrentDeployments.select(listOf(undated), asOf = null))
    }
}
