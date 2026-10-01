package com.repodatagraph.domain.lifecycle

import com.repodatagraph.domain.model.NodeKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * When one deployment replaces another (#90, FR-4): a successful deployment of an artifact family to
 * an environment ends the one before it there, at the instant it was deployed. The rule is pure, so
 * the order deployments arrive in - a poll, a webhook, one redelivered late - cannot change what it
 * decides.
 */
class DeploymentSupersessionTest {
    private val payments = ArtifactFamily("ghcr.io", "acme/payments")
    private val ledger = ArtifactFamily("ghcr.io", "acme/ledger")
    private val t1 = Instant.parse("2026-09-30T10:00:00Z")
    private val t2 = Instant.parse("2026-09-30T11:00:00Z")
    private val t3 = Instant.parse("2026-09-30T12:00:00Z")

    private fun deployment(
        digest: String,
        at: Instant,
        family: ArtifactFamily = payments,
        environment: String = "production",
        succeeded: Boolean = true,
    ) = DeploymentRecord(
        key = NodeKey("Deployment", "${family.registry}/${family.name}@sha256:$digest#$environment#${at.epochSecond}"),
        family = family,
        environmentKey = environment,
        deployedAt = at,
        succeeded = succeeded,
    )

    @Test
    fun `a newer deployment closes the current one at the newer deployedAt`() {
        val earlier = deployment("a", t1)
        val newer = deployment("b", t2)

        assertThat(DeploymentSupersession.closures(written = listOf(newer), current = listOf(earlier)))
            .containsExactly(Supersession(earlier.key, t2))
    }

    @Test
    fun `an older deployment arriving late is closed at once by the newer one already current`() {
        val newer = deployment("b", t2)
        val late = deployment("a", t1)

        assertThat(DeploymentSupersession.closures(written = listOf(late), current = listOf(newer)))
            .containsExactly(Supersession(late.key, t2))
    }

    @Test
    fun `several deployments in one read close one another in order`() {
        val first = deployment("a", t1)
        val second = deployment("b", t2)
        val third = deployment("c", t3)

        assertThat(DeploymentSupersession.closures(written = listOf(third, first, second), current = emptyList()))
            .containsExactlyInAnyOrder(Supersession(first.key, t2), Supersession(second.key, t3))
    }

    @Test
    fun `a deployment between two current ones closes the first and is closed by the second`() {
        val first = deployment("a", t1)
        val between = deployment("b", t2)
        val last = deployment("c", t3)

        assertThat(DeploymentSupersession.closures(written = listOf(between), current = listOf(first, last)))
            .containsExactlyInAnyOrder(Supersession(first.key, t2), Supersession(between.key, t3))
    }

    @Test
    fun `another family or another environment is not replaced`() {
        val other = deployment("l", t1, family = ledger)
        val staging = deployment("s", t1, environment = "staging")
        val newer = deployment("b", t2)

        assertThat(DeploymentSupersession.closures(written = listOf(newer), current = listOf(other, staging))).isEmpty()
    }

    @Test
    fun `a failed deployment replaces nothing and is replaced by nothing`() {
        val running = deployment("a", t1)
        val failed = deployment("b", t2, succeeded = false)

        assertThat(DeploymentSupersession.closures(written = listOf(failed), current = listOf(running))).isEmpty()
    }

    @Test
    fun `the same deployment read again replaces nothing`() {
        val only = deployment("a", t1)

        assertThat(DeploymentSupersession.closures(written = listOf(only), current = listOf(only))).isEmpty()
    }

    @Test
    fun `two current deployments this read did not touch are left for whoever wrote them`() {
        val first = deployment("a", t1)
        val second = deployment("b", t2)
        val unrelated = deployment("l", t3, family = ledger)

        assertThat(DeploymentSupersession.closures(written = listOf(unrelated), current = listOf(first, second))).isEmpty()
    }

    @Test
    fun `deployments at the same instant do not close each other`() {
        val one = deployment("a", t1)
        val other = deployment("b", t1)

        assertThat(DeploymentSupersession.closures(written = listOf(one, other), current = emptyList())).isEmpty()
    }

    @Test
    fun `a family without a registry is its own family`() {
        val bare = deployment("a", t1, family = ArtifactFamily(null, "acme/payments"))
        val newer = deployment("b", t2)

        assertThat(DeploymentSupersession.closures(written = listOf(newer), current = listOf(bare))).isEmpty()
    }
}
