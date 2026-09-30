package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * How a path is scored and which path explains a node (#21, FR3): the product of the edges'
 * provenance confidence, the best path wins, and a node is inferred only if that path is.
 */
class PathConfidenceTest {
    private val at = Instant.parse("2026-09-01T10:00:00Z")

    private fun node(id: String) =
        GraphNode(NodeKey.parse(id), emptyMap(), Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at))

    private fun step(
        from: String,
        to: String,
        confidence: Double = 1.0,
        inferred: Boolean = false,
        edge: String = "DEPENDED_ON_BY",
    ) = PathStep(edge, from, to, confidence, inferred)

    private val root = "Repository:root"
    private val a = "Repository:a"
    private val b = "Repository:b"
    private val db = "CloudResource:aws:db"

    @Test
    fun `a path's confidence is the product of its edges' confidence`() {
        assertEquals(0.95 * 0.4, PathConfidence.of(listOf(step(root, a, 0.95), step(a, db, 0.4))), 1e-12)
    }

    @Test
    fun `an empty path is certain`() {
        assertEquals(1.0, PathConfidence.of(emptyList()))
    }

    @Test
    fun `when several paths reach a node the most confident one wins, whatever its length`() {
        val short = listOf(step(root, b, 0.5))
        val long = listOf(step(root, a, 0.9), step(a, b, 0.9))

        val ranking =
            PathConfidence.rank(
                NodeKey.parse(root),
                listOf(CandidatePath(node(b), short), CandidatePath(node(b), long)),
                0.0,
                1000,
            )

        val affected = ranking.affected.single { it.node.id == b }
        assertEquals(0.81, affected.confidence, 1e-12)
        assertEquals(2, affected.distance)
        assertEquals(long, affected.path)
    }

    @Test
    fun `between equally confident paths the shorter one wins`() {
        val short = listOf(step(root, b))
        val long = listOf(step(root, a), step(a, b))

        val ranking =
            PathConfidence.rank(
                NodeKey.parse(root),
                listOf(CandidatePath(node(b), long), CandidatePath(node(b), short)),
                0.0,
                1000,
            )

        assertEquals(1, ranking.affected.single { it.node.id == b }.distance)
    }

    @Test
    fun `a node is inferred only when the winning path holds an inferred edge`() {
        val inferredButStrong = listOf(step(root, db, 0.9, inferred = true, edge = "OWNS_RESOURCE"))
        val statedButWeak = listOf(step(root, a, 0.8), step(a, db, 1.0, edge = "OWNS_RESOURCE"))
        val stated = listOf(step(root, a, 1.0))

        val ranking =
            PathConfidence.rank(
                NodeKey.parse(root),
                listOf(CandidatePath(node(db), inferredButStrong), CandidatePath(node(db), statedButWeak), CandidatePath(node(a), stated)),
                0.0,
                1000,
            )

        assertTrue(ranking.affected.single { it.node.id == db }.inferred)
        assertFalse(ranking.affected.single { it.node.id == a }.inferred)
    }

    @Test
    fun `nodes below minConfidence are left out but counted as excluded`() {
        val ranking =
            PathConfidence.rank(
                NodeKey.parse(root),
                listOf(
                    CandidatePath(node(a), listOf(step(root, a, 1.0))),
                    CandidatePath(node(db), listOf(step(root, a, 1.0), step(a, db, 0.4, inferred = true))),
                ),
                0.5,
                1000,
            )

        assertEquals(listOf(a), ranking.affected.map { it.node.id })
        assertEquals(mapOf("Repository" to 1, "excluded" to 1), ranking.byType)
    }

    @Test
    fun `a node exactly at minConfidence is kept`() {
        val ranking = PathConfidence.rank(NodeKey.parse(root), listOf(CandidatePath(node(a), listOf(step(root, a, 0.5)))), 0.5, 1000)

        assertEquals(1, ranking.affected.size)
    }

    @Test
    fun `a path back to the root does not make the root affected`() {
        val ranking =
            PathConfidence.rank(
                NodeKey.parse(root),
                listOf(CandidatePath(node(root), listOf(step(root, a), step(a, root)))),
                0.0,
                1000,
            )

        assertTrue(ranking.affected.isEmpty())
        assertEquals(mapOf("excluded" to 0), ranking.byType)
    }

    @Test
    fun `affected nodes are ordered nearest first, then most confident, then by id`() {
        val ranking =
            PathConfidence.rank(
                NodeKey.parse(root),
                listOf(
                    CandidatePath(node(db), listOf(step(root, a), step(a, db, 0.9))),
                    CandidatePath(node(b), listOf(step(root, b, 0.7))),
                    CandidatePath(node(a), listOf(step(root, a, 1.0))),
                ),
                0.0,
                1000,
            )

        assertEquals(listOf(a, b, db), ranking.affected.map { it.node.id })
    }

    @Test
    fun `more than the cap is truncated, and the counts still describe everything found`() {
        val many = (1..5).map { CandidatePath(node("Repository:r$it"), listOf(step(root, "Repository:r$it"))) }

        val ranking = PathConfidence.rank(NodeKey.parse(root), many, 0.0, 3)

        assertEquals(3, ranking.affected.size)
        assertTrue(ranking.truncated)
        assertEquals(5, ranking.byType["Repository"])
    }

    @Test
    fun `exactly the cap is not truncated`() {
        val many = (1..3).map { CandidatePath(node("Repository:r$it"), listOf(step(root, "Repository:r$it"))) }

        assertFalse(PathConfidence.rank(NodeKey.parse(root), many, 0.0, 3).truncated)
    }
}
