package com.repodatagraph.domain.ontology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The impact and ownership flags on an edge (#21) are only meaningful together: ownership is
 * inherited against the direction a change propagates, so an edge that inherits ownership but does
 * not propagate change has no direction to inherit along, and a `downstream` on an edge that does
 * not propagate says nothing. Both are rejected at startup rather than traversed wrongly.
 */
class OntologyRegistryImpactTest {
    private val nodes =
        listOf("Repository", "CloudResource").map {
            NodeTypeDef(it, null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, true)))
        }

    private fun registryWith(edge: EdgeTypeDef) = OntologyRegistry("1.0.0", nodes, listOf(edge))

    private fun edge(
        impact: EdgeImpact,
        downstream: ImpactAlong = ImpactAlong.FORWARD,
        ownership: EdgeOwnership = EdgeOwnership.NONE,
    ) = EdgeTypeDef(
        "OWNS_RESOURCE",
        null,
        listOf("Repository"),
        listOf("CloudResource"),
        "OWNED_BY_REPO",
        emptyList(),
        impact,
        downstream,
        ownership,
    )

    @Test
    fun `an edge declares no impact unless it says so`() {
        val plain = EdgeTypeDef("OWNS_RESOURCE", null, listOf("Repository"), listOf("CloudResource"), "OWNED_BY_REPO")

        assertEquals(EdgeImpact.NONE, plain.impact)
        assertEquals(ImpactAlong.FORWARD, plain.downstream)
        assertEquals(EdgeOwnership.NONE, plain.ownership)
    }

    @Test
    fun `a propagating edge that inherits ownership is accepted`() {
        registryWith(edge(EdgeImpact.PROPAGATES, ImpactAlong.INVERSE, EdgeOwnership.INHERITS))
    }

    @Test
    fun `inheriting ownership along an edge that does not propagate is rejected`() {
        val refused = assertThrows<InvalidOntologyException> { registryWith(edge(EdgeImpact.NONE, ownership = EdgeOwnership.INHERITS)) }

        assertTrue(refused.message!!.contains("OWNS_RESOURCE")) { refused.message }
    }

    @Test
    fun `a downstream direction on an edge that does not propagate is rejected`() {
        assertThrows<InvalidOntologyException> { registryWith(edge(EdgeImpact.NONE, ImpactAlong.INVERSE)) }
    }

    @Test
    fun `the flags have wire names, and an unknown one is refused`() {
        assertEquals(EdgeImpact.PROPAGATES, EdgeImpact.fromWireName("propagates"))
        assertEquals(ImpactAlong.INVERSE, ImpactAlong.fromWireName("inverse"))
        assertEquals(EdgeOwnership.INHERITS, EdgeOwnership.fromWireName("inherits"))
        assertThrows<InvalidOntologyException> { EdgeImpact.fromWireName("sometimes") }
        assertThrows<InvalidOntologyException> { ImpactAlong.fromWireName("sideways") }
        assertThrows<InvalidOntologyException> { EdgeOwnership.fromWireName("maybe") }
    }
}
