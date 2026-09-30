package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * What a neighbourhood question asks (#9, FR1 and FR2), read from query parameters and bounded here,
 * so no caller can ask the graph view's endpoint for an unbounded walk.
 */
class NeighbourhoodSpecTest {
    private val payments = NodeKey("Repository", "github.com/acme/payments")

    @Test
    fun `absent parameters take the documented defaults`() {
        val spec = NeighbourhoodSpec.of(payments.id, null, null, null, null)

        assertEquals(payments, spec.nodeId)
        assertEquals(1, spec.depth)
        assertEquals(Direction.BOTH, spec.direction)
        assertEquals(emptySet<String>(), spec.nodeTypes)
        assertEquals(emptySet<String>(), spec.edgeTypes)
        assertEquals(500, spec.limit)
    }

    @Test
    fun `filters are comma separated, trimmed, and blanks ignored`() {
        val spec = NeighbourhoodSpec.of(payments.id, "2", " Repository, Team ,,", "DEPENDS_ON", "out")

        assertEquals(2, spec.depth)
        assertEquals(setOf("Repository", "Team"), spec.nodeTypes)
        assertEquals(setOf("DEPENDS_ON"), spec.edgeTypes)
        assertEquals(Direction.OUTGOING, spec.direction)
    }

    @ParameterizedTest
    @CsvSource("in, INCOMING", "out, OUTGOING", "both, BOTH", "IN, INCOMING", " Both , BOTH")
    fun `direction is in, out or both, in any case`(
        value: String,
        expected: Direction,
    ) {
        assertEquals(expected, NeighbourhoodSpec.of(payments.id, null, null, null, value).direction)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "4", "-1", "two", "1.5"])
    fun `a depth outside 1 to 3 is refused, naming depth`(depth: String) {
        val refused = assertThrows<InvalidQueryParameterException> { NeighbourhoodSpec.of(payments.id, depth, null, null, null) }

        assertEquals("depth", refused.field)
    }

    @Test
    fun `a depth outside 1 to 3 is refused however the spec is built`() {
        assertEquals("depth", assertThrows<InvalidQueryParameterException> { NeighbourhoodSpec(payments, depth = 4) }.field)
    }

    @Test
    fun `an unknown direction is refused, naming direction`() {
        val refused = assertThrows<InvalidQueryParameterException> { NeighbourhoodSpec.of(payments.id, null, null, null, "sideways") }

        assertEquals("direction", refused.field)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "no-colon", "Repository:"])
    fun `a missing or malformed node id is refused, naming nodeId`(nodeId: String) {
        val refused = assertThrows<InvalidQueryParameterException> { NeighbourhoodSpec.of(nodeId, null, null, null, null) }

        assertEquals("nodeId", refused.field)
    }

    @Test
    fun `the cap is between 1 and 500`() {
        assertThrows<IllegalArgumentException> { NeighbourhoodSpec(payments, limit = 0) }
        assertThrows<IllegalArgumentException> { NeighbourhoodSpec(payments, limit = 501) }
    }
}
