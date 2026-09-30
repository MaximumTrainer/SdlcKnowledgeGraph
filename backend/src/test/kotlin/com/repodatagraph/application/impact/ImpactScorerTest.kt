package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.Citation
import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactHit
import com.repodatagraph.domain.model.ImpactScoring
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Scoring version 1 (#87, FR4 and FR5): `1 / (1 + hops)`, times the weight of the environment the hit
 * runs in, times a boost when a requested path names it, clamped to (0, 1]. The order is total -
 * score, then hops, then node id - so no tie is ever left to the database.
 */
class ImpactScorerTest {
    private val at = Instant.parse("2026-09-01T10:00:00Z")
    private val stated = Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at)

    private fun node(id: String) = GraphNode(NodeKey.parse(id), emptyMap(), stated)

    private fun hit(
        id: String,
        hops: Int,
        score: Double,
    ): ImpactHit {
        val node = node(id)
        return ImpactHit(
            node = node,
            hops = hops,
            score = score,
            confidence = 1.0,
            inferred = false,
            environment = null,
            tier = EnvironmentTier.OTHER,
            pathMatched = false,
            owners = emptyList(),
            citation = Citation(node.id, emptyList(), stated),
        )
    }

    @Test
    fun `the score is one over one plus the hops, weighted by the environment's tier`() {
        assertEquals(0.5, ImpactScorer.score(1, EnvironmentTier.PRODUCTION, pathMatched = false), 1e-12)
        assertEquals(1.0 / 3, ImpactScorer.score(2, EnvironmentTier.PRODUCTION, pathMatched = false), 1e-12)
        assertEquals(1.0 / 3 * 0.6, ImpactScorer.score(2, EnvironmentTier.PRE_PRODUCTION, pathMatched = false), 1e-12)
        assertEquals(0.25 * 0.5, ImpactScorer.score(3, EnvironmentTier.OTHER, pathMatched = false), 1e-12)
        assertEquals(0.2 * 0.3, ImpactScorer.score(4, EnvironmentTier.DEVELOPMENT, pathMatched = false), 1e-12)
    }

    @Test
    fun `the tier weights are the published ones, production above pre-production above other above development`() {
        assertEquals(
            mapOf(
                EnvironmentTier.PRODUCTION to 1.0,
                EnvironmentTier.PRE_PRODUCTION to 0.6,
                EnvironmentTier.OTHER to 0.5,
                EnvironmentTier.DEVELOPMENT to 0.3,
            ),
            ImpactScoring.TIER_WEIGHTS,
        )
        assertEquals("1", ImpactScoring.VERSION)
    }

    @Test
    fun `production two hops away outranks anything not in an environment one hop away`() {
        val production = ImpactScorer.score(2, EnvironmentTier.PRODUCTION, pathMatched = false)
        val unplaced = ImpactScorer.score(1, EnvironmentTier.OTHER, pathMatched = false)
        val staging = ImpactScorer.score(2, EnvironmentTier.PRE_PRODUCTION, pathMatched = false)

        assertTrue(production > unplaced && unplaced > staging, "$production, $unplaced, $staging")
    }

    @Test
    fun `a matched path boosts the score, and the score stays above 0 and at most 1`() {
        assertEquals(0.5, ImpactScorer.score(1, EnvironmentTier.OTHER, pathMatched = true), 1e-12)
        assertEquals(1.0, ImpactScorer.score(1, EnvironmentTier.PRODUCTION, pathMatched = true), 1e-12)
        for (hops in 0..10) {
            for (tier in EnvironmentTier.entries) {
                for (matched in listOf(true, false)) {
                    val score = ImpactScorer.score(hops, tier, matched)
                    assertTrue(score > 0.0 && score <= 1.0, "hops $hops, $tier, matched $matched: $score")
                }
            }
        }
    }

    @Test
    fun `the order is score descending, then hops ascending, then node id ascending`() {
        val ranked =
            ImpactScorer.rank(
                listOf(
                    hit("Service:b", 1, 0.25),
                    hit("Deployment:x", 2, 1.0 / 3),
                    hit("Artifact:a", 1, 0.25),
                    hit("Environment:e", 3, 0.25),
                    hit("Deployment:y", 2, 0.2),
                ),
            )

        assertEquals(listOf("Deployment:x", "Artifact:a", "Service:b", "Environment:e", "Deployment:y"), ranked.map { it.node.id })
    }

    @Test
    fun `the order does not depend on the order the hits arrived in`() {
        val random = Random(87)
        val hits =
            (0 until 300).map { index ->
                // Few distinct scores and hops, so ties are the rule and only the id can settle them.
                val hops = 1 + random.nextInt(3)
                hit("Service:s${random.nextInt(1000)}-$index", hops, listOf(0.5, 0.25, 0.2)[random.nextInt(3)])
            }
        val expected = ImpactScorer.rank(hits)

        repeat(50) { round ->
            assertEquals(expected, ImpactScorer.rank(hits.shuffled(Random(round))), "shuffle $round")
        }
        expected.zipWithNext().forEach { (a, b) ->
            assertTrue(
                a.score > b.score || (a.score == b.score && (a.hops < b.hops || (a.hops == b.hops && a.node.id < b.node.id))),
                "${a.node.id} before ${b.node.id}",
            )
        }
    }

    @Test
    fun `each node is reached by its shortest path, then its most confident, whatever order the paths came in`() {
        val root = node("Repository:r")
        val target = node("Deployment:d")
        val short = CandidatePath(target, listOf(step("BUILDS", root, target, 0.5)))
        val confidentButLong =
            CandidatePath(
                target,
                listOf(step("BUILDS", root, node("Artifact:a"), 1.0), step("DEPLOYED_TO", node("Artifact:a"), target, 1.0)),
            )
        val shortAndSure = CandidatePath(target, listOf(step("PROVIDES", root, target, 0.9)))
        val backToRoot = CandidatePath(root, listOf(step("BUILDS", root, target, 1.0), step("DEPENDS_ON", target, root, 1.0)))

        val chosen = ImpactScorer.nearest(root.key, listOf(confidentButLong, short, backToRoot, shortAndSure))
        val reversed = ImpactScorer.nearest(root.key, listOf(shortAndSure, backToRoot, short, confidentButLong))

        assertEquals(listOf(shortAndSure), chosen)
        assertEquals(chosen, reversed)
    }

    private fun step(
        edge: String,
        from: GraphNode,
        to: GraphNode,
        confidence: Double,
    ) = PathStep(edge, from.id, to.id, confidence, inferred = false)
}
