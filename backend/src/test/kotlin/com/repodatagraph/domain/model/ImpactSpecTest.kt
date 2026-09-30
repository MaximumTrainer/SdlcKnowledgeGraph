package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** What an impact question may ask (#21, FR1 and FR7): bounded depth, a confidence in 0..1, a real node id. */
class ImpactSpecTest {
    private val root = "Repository:github.com/acme/shared-lib"

    @Test
    fun `defaults are depth 3, minConfidence 0_5 and downstream`() {
        val spec = ImpactSpec.of(nodeId = root, depth = null, minConfidence = null, direction = null)

        assertEquals(NodeKey("Repository", "github.com/acme/shared-lib"), spec.nodeId)
        assertEquals(3, spec.depth)
        assertEquals(0.5, spec.minConfidence)
        assertEquals(ImpactDirection.DOWNSTREAM, spec.direction)
    }

    @Test
    fun `every depth from 1 to 5 is accepted`() {
        (1..5).forEach { assertEquals(it, ImpactSpec.of(root, it.toString(), null, null).depth) }
    }

    @Test
    fun `a depth outside 1 to 5, or not a number, is refused naming depth`() {
        listOf("0", "6", "-1", "9", "three", "2.5").forEach { depth ->
            val refused = assertThrows<InvalidQueryParameterException> { ImpactSpec.of(root, depth, null, null) }
            assertEquals("depth", refused.field, "for depth '$depth'")
        }
    }

    @Test
    fun `a minConfidence of 0 and of 1 are both accepted`() {
        assertEquals(0.0, ImpactSpec.of(root, null, "0", null).minConfidence)
        assertEquals(1.0, ImpactSpec.of(root, null, "1.0", null).minConfidence)
    }

    @Test
    fun `a minConfidence outside 0 to 1, or not a number, is refused naming minConfidence`() {
        listOf("-0.01", "1.01", "NaN", "high").forEach { confidence ->
            val refused = assertThrows<InvalidQueryParameterException> { ImpactSpec.of(root, null, confidence, null) }
            assertEquals("minConfidence", refused.field, "for minConfidence '$confidence'")
        }
    }

    @Test
    fun `a missing, blank or untyped nodeId is refused naming nodeId`() {
        listOf(null, "", "  ", "no-type", ":key", "Repository:").forEach { nodeId ->
            val refused = assertThrows<InvalidQueryParameterException> { ImpactSpec.of(nodeId, null, null, null) }
            assertEquals("nodeId", refused.field, "for nodeId '$nodeId'")
        }
    }

    @Test
    fun `a node key keeps every separator after the first`() {
        val spec = ImpactSpec.of("CloudResource:aws:arn:aws:rds:eu-west-1:1:db:payments", null, null, null)

        assertEquals(NodeKey("CloudResource", "aws:arn:aws:rds:eu-west-1:1:db:payments"), spec.nodeId)
    }

    @Test
    fun `direction is read without regard to case, and anything else is refused naming direction`() {
        assertEquals(ImpactDirection.UPSTREAM, ImpactSpec.of(root, null, null, "UpStream").direction)
        assertEquals(ImpactDirection.DOWNSTREAM, ImpactSpec.of(root, null, null, "downstream").direction)

        val refused = assertThrows<InvalidQueryParameterException> { ImpactSpec.of(root, null, null, "sideways") }
        assertEquals("direction", refused.field)
    }

    @Test
    fun `the constructor holds the same bounds as the parser`() {
        assertThrows<InvalidQueryParameterException> { ImpactSpec(NodeKey("Repository", "r"), depth = 6) }
        assertThrows<InvalidQueryParameterException> { ImpactSpec(NodeKey("Repository", "r"), minConfidence = 1.5) }
    }
}
