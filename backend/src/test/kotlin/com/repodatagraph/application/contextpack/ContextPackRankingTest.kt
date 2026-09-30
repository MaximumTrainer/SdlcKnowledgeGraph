package com.repodatagraph.application.contextpack

import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.PackNode
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ProvenanceSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/**
 * How a pack is ordered and cut to its budget (#96, FR-3): nearest first, then most confident, then
 * by #87's impact score - so at one distance a production deployment comes before a staging one - and
 * then by id, a total order that leaves nothing to the store. What the budget leaves out is counted.
 */
class ContextPackRankingTest {
    private val at = Instant.parse("2026-09-01T00:00:00Z")
    private val stated = Provenance(sourceSystem = "manual", ingestedAt = at, validFrom = at)
    private val summary = ProvenanceSummary("manual", null, 1.0, inferred = false, stale = false)

    private fun packNode(
        id: String,
        distance: Int,
        confidence: Double = 1.0,
        score: Double = 0.5 / (1 + distance),
    ) = PackNode(
        node = GraphNode(NodeKey.parse(id), emptyMap(), stated),
        label = id,
        distance = distance,
        confidence = confidence,
        inferred = false,
        score = score,
        tier = EnvironmentTier.OTHER,
        via = emptyList(),
        provenance = summary,
    )

    @Test
    fun `nearer comes first, whatever it scores`() {
        val near = packNode("Repository:a/b/near", distance = 1, score = 0.1)
        val far = packNode("Deployment:far", distance = 3, score = 0.9)

        assertEquals(listOf(near, far), ContextPackRanking.rank(listOf(far, near)))
    }

    @Test
    fun `at one distance the more confident comes first`() {
        val sure = packNode("Repository:a/b/z", distance = 2, confidence = 1.0)
        val guessed = packNode("Repository:a/b/a", distance = 2, confidence = 0.6)

        assertEquals(listOf(sure, guessed), ContextPackRanking.rank(listOf(guessed, sure)))
    }

    @Test
    fun `at one distance and confidence the impact score orders them, production before staging`() {
        val production = packNode("Deployment:z-production", distance = 3, score = 0.25)
        val staging = packNode("Deployment:a-staging", distance = 3, score = 0.15)

        assertEquals(listOf(production, staging), ContextPackRanking.rank(listOf(staging, production)))
    }

    @Test
    fun `a full tie is broken by id`() {
        val b = packNode("Repository:a/b/b", distance = 1)
        val a = packNode("Repository:a/b/a", distance = 1)

        assertEquals(listOf(a, b), ContextPackRanking.rank(listOf(b, a)))
    }

    @Test
    fun `the order is total, so shuffled input gives one answer`() {
        val nodes =
            (0 until 300).map { index ->
                packNode("Repository:a/b/n%03d".format(index), distance = 1 + index % 3, confidence = if (index % 2 == 0) 1.0 else 0.8)
            }
        val expected = ContextPackRanking.rank(nodes)
        val random = Random(96)

        repeat(50) { assertEquals(expected, ContextPackRanking.rank(nodes.shuffled(random))) }
    }

    @Test
    fun `the budget keeps the first nodes and counts the rest as cut`() {
        val nodes = (1..35).map { packNode("Repository:a/b/n%02d".format(it), distance = 1) }

        val cut = ContextPackRanking.cut(nodes.shuffled(Random(1)), budget = 10)

        assertEquals(nodes.take(10), cut.kept)
        assertEquals(25, cut.cut)
    }

    @Test
    fun `a budget larger than the pack cuts nothing`() {
        val nodes = (1..3).map { packNode("Repository:a/b/n$it", distance = it) }

        val cut = ContextPackRanking.cut(nodes, budget = 20)

        assertEquals(nodes, cut.kept)
        assertEquals(0, cut.cut)
    }
}
