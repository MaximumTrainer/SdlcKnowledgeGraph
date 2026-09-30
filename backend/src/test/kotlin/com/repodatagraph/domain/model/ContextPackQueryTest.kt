package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

/**
 * What a context pack asks (#96, FR-1): a start node, a template and a budget, and optionally an
 * instant. Bounded here, so no caller can ask for an unbounded pack, and each bad field is refused
 * naming it before anything is walked.
 */
class ContextPackQueryTest {
    @Test
    fun `a request is read as given`() {
        val query = ContextPackQuery.of(" Repository:github.com/acme/payments ", " change-impact ", 20, "2026-09-10T00:00:00Z")

        assertEquals(NodeKey("Repository", "github.com/acme/payments"), query.startId)
        assertEquals("change-impact", query.template)
        assertEquals(20, query.budget)
        assertEquals(Instant.parse("2026-09-10T00:00:00Z"), query.asOf)
        assertEquals(null, ContextPackQuery.of("Team:platform", "t", 1, null).asOf)
    }

    @Test
    fun `the start node is required, as Type colon key`() {
        assertEquals("startId", assertThrows<InvalidQueryParameterException> { ContextPackQuery.of(null, "t", 5, null) }.field)
        assertEquals("startId", assertThrows<InvalidQueryParameterException> { ContextPackQuery.of("payments", "t", 5, null) }.field)
    }

    @Test
    fun `the template is required`() {
        assertEquals("template", assertThrows<InvalidQueryParameterException> { ContextPackQuery.of("Team:platform", null, 5, null) }.field)
        assertEquals("template", assertThrows<InvalidQueryParameterException> { ContextPackQuery.of("Team:platform", "  ", 5, null) }.field)
    }

    @Test
    fun `the budget is required: a pack an agent did not size is not one it can fit`() {
        val error = assertThrows<InvalidQueryParameterException> { ContextPackQuery.of("Team:platform", "t", null, null) }

        assertEquals("budget", error.field)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1, 501, 10_000])
    fun `a budget outside 1 to 500 is refused naming the bound`(budget: Int) {
        val error = assertThrows<InvalidQueryParameterException> { ContextPackQuery.of("Team:platform", "t", budget, null) }

        assertEquals("budget", error.field)
        assertTrue(error.message!!.contains("1 and 500"), error.message)
    }

    @Test
    fun `the budget bounds are inclusive`() {
        assertEquals(1, ContextPackQuery.of("Team:platform", "t", ContextPackQuery.MIN_BUDGET, null).budget)
        assertEquals(500, ContextPackQuery.of("Team:platform", "t", ContextPackQuery.MAX_BUDGET, null).budget)
    }

    @Test
    fun `asOf must be an instant, not a date`() {
        val error = assertThrows<InvalidQueryParameterException> { ContextPackQuery.of("Team:platform", "t", 5, "2026-09-10") }

        assertEquals("asOf", error.field)
    }
}
