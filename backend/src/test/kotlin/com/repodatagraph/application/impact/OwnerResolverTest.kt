package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.OwnerPath
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Who owns a node (#21, FR6): its own OWNED_BY when it has one, otherwise the owners of the nearest
 * things it inherits ownership from, each team once with its most confident path.
 */
class OwnerResolverTest {
    private val resolver = OwnerResolver()
    private val at = Instant.parse("2026-09-01T10:00:00Z")

    private fun node(id: String) =
        GraphNode(NodeKey.parse(id), emptyMap(), Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at))

    private val resource = node("CloudResource:aws:db")
    private val payments = "Repository:github.com/acme/payments"
    private val ledger = "Repository:github.com/acme/ledger"
    private val paymentsTeam = node("Team:payments-team")
    private val platform = node("Team:platform")

    private fun step(
        edge: String,
        from: String,
        to: String,
        confidence: Double = 1.0,
    ) = PathStep(edge, from, to, confidence, confidence < 1.0)

    @Test
    fun `a node's own owner is the answer, and inherited owners are not added to it`() {
        val owners =
            resolver.resolve(
                resource,
                listOf(
                    OwnerPath(platform, listOf(step("OWNED_BY", resource.id, platform.id))),
                    OwnerPath(
                        paymentsTeam,
                        listOf(step("OWNED_BY_REPO", resource.id, payments), step("OWNED_BY", payments, paymentsTeam.id)),
                    ),
                ),
            )

        assertEquals(listOf("Team:platform"), owners.owners.map { it.team.id })
        assertEquals(1.0, owners.owners.single().confidence)
    }

    @Test
    fun `without its own owner, the nearest inherited owners answer, with the product of the path`() {
        val owners =
            resolver.resolve(
                resource,
                listOf(
                    OwnerPath(
                        paymentsTeam,
                        listOf(step("OWNED_BY_REPO", resource.id, payments, 0.4), step("OWNED_BY", payments, paymentsTeam.id)),
                    ),
                ),
            )

        val owner = owners.owners.single()
        assertEquals(paymentsTeam.id, owner.team.id)
        assertEquals(listOf("OWNED_BY_REPO", "OWNED_BY"), owner.via.map { it.edge })
        assertEquals(0.4, owner.confidence, 1e-12)
    }

    @Test
    fun `a team reached twice is listed once, by its most confident path`() {
        val owners =
            resolver.resolve(
                resource,
                listOf(
                    OwnerPath(
                        paymentsTeam,
                        listOf(step("OWNED_BY_REPO", resource.id, payments, 0.4), step("OWNED_BY", payments, paymentsTeam.id)),
                    ),
                    OwnerPath(
                        paymentsTeam,
                        listOf(step("OWNED_BY_REPO", resource.id, ledger, 0.9), step("OWNED_BY", ledger, paymentsTeam.id)),
                    ),
                ),
            )

        assertEquals(1, owners.owners.size)
        assertEquals(0.9, owners.owners.single().confidence, 1e-12)
    }

    @Test
    fun `farther owners are not listed when nearer ones exist`() {
        val deployment = node("Deployment:d1")
        val artifact = "Artifact:a1"
        val owners =
            resolver.resolve(
                deployment,
                listOf(
                    OwnerPath(platform, listOf(step("DEPLOYMENT_OF", deployment.id, artifact), step("OWNED_BY", artifact, platform.id))),
                    OwnerPath(
                        paymentsTeam,
                        listOf(
                            step("DEPLOYMENT_OF", deployment.id, artifact),
                            step("BUILT_FROM", artifact, payments),
                            step("OWNED_BY", payments, paymentsTeam.id),
                        ),
                    ),
                ),
            )

        assertEquals(listOf(platform.id), owners.owners.map { it.team.id })
    }

    @Test
    fun `several owners at the same distance are all listed, most confident first`() {
        val owners =
            resolver.resolve(
                resource,
                listOf(
                    OwnerPath(platform, listOf(step("OWNED_BY", resource.id, platform.id, 0.6))),
                    OwnerPath(paymentsTeam, listOf(step("OWNED_BY", resource.id, paymentsTeam.id, 0.9))),
                ),
            )

        assertEquals(listOf(paymentsTeam.id, platform.id), owners.owners.map { it.team.id })
    }

    @Test
    fun `no path means no owners, not an error`() {
        val owners = resolver.resolve(resource, emptyList())

        assertEquals(resource, owners.node)
        assertTrue(owners.owners.isEmpty())
    }
}
