package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.application.impact.ImpactAnalysisService
import com.repodatagraph.domain.model.ImpactSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.core.Neo4jClient
import java.util.UUID

/**
 * The blast radius on a graph of ten thousand nodes (#21, FR8).
 *
 * The issue asks for a p95 under 500 ms. A shared CI runner is several times slower than a developer
 * machine and a Testcontainers Neo4j has a cold page cache, so the bound asserted here is a generous
 * ceiling that catches a traversal gone quadratic without failing for a busy runner. The measured
 * figures are printed, so a run shows how far under it they are.
 *
 * The graph is 2,000 repositories, each with a pipeline, an artifact, a deployment and a cloud
 * resource, over three environments: 10,003 nodes. Each repository depends on one or two earlier
 * ones, so the dependents of the first fan out as a tree with diamonds in it.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class ImpactPerformanceIT {
    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    @Autowired
    private lateinit var impact: ImpactAnalysisService

    private val run = "perf-" + UUID.randomUUID().toString().take(8)

    /** Ten thousand nodes are not left behind for the suites that count what is in the graph. */
    @AfterEach
    fun removeSeed() {
        neo4jClient.query("MATCH (n) WHERE n.key CONTAINS ${'$'}run DETACH DELETE n").bindAll(mapOf("run" to run)).run()
    }

    @Test
    fun `the blast radius of a well-depended-on repository on a ten thousand node graph stays fast`() {
        seed()
        val root = NodeKey("Repository", "github.com/$run/r0")

        repeat(WARM_UP) { impact.impact(ImpactSpec(root, depth = 5, minConfidence = 0.0)) }

        val results = mutableListOf<Int>()
        val timings =
            (1..SAMPLES).map {
                val started = System.nanoTime()
                val result = impact.impact(ImpactSpec(root, depth = 5, minConfidence = 0.0))
                results += result.affected.size
                (System.nanoTime() - started) / NANOS_PER_MILLI
            }
        val sorted = timings.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]

        println(
            "ImpactPerformanceIT: depth 5 over $NODES nodes, ${results.last()} affected: p50 ${p50}ms p95 ${p95}ms max ${sorted.last()}ms",
        )
        assertThat(results.last()).isGreaterThan(MIN_AFFECTED)
        assertThat(p95).isLessThan(P95_CEILING_MILLIS)
    }

    /** Written in bulk rather than through GraphStore: the subject here is the read, not ten thousand writes. */
    private fun seed() {
        val repositories = (0 until REPOSITORIES).map { i -> mapOf("i" to i, "key" to "github.com/$run/r$i") }
        neo4jClient
            .query(
                """
                UNWIND ${'$'}environments AS name
                MERGE (e:Environment { key: name })
                SET e.id = 'Environment:' + name, e.name = name, e.type = name,
                    e.prov_sourceSystem = 'manual', e.prov_confidence = 1.0, e.prov_inferred = false
                WITH count(*) AS environments
                UNWIND ${'$'}repositories AS row
                CREATE (r:Repository { key: row.key, id: 'Repository:' + row.key, url: 'https://' + row.key, defaultBranch: 'main' })
                CREATE (p:Pipeline { key: row.key + ':ci', id: 'Pipeline:' + row.key + ':ci', name: 'ci', provider: 'github-actions' })
                CREATE (a:Artifact { key: row.key + '@sha256:' + row.i, id: 'Artifact:' + row.key + '@sha256:' + row.i, name: row.key })
                CREATE (d:Deployment { key: row.key + '#' + row.i, id: 'Deployment:' + row.key + '#' + row.i, status: 'SUCCESS' })
                CREATE (c:CloudResource { key: 'aws:' + row.key, id: 'CloudResource:aws:' + row.key, name: row.key })
                CREATE (r)-[:HAS_PIPELINE { prov_confidence: 1.0, prov_inferred: false }]->(p)
                CREATE (a)-[:BUILT_FROM { prov_confidence: 1.0, prov_inferred: false }]->(r)
                CREATE (a)-[:DEPLOYED_TO { prov_confidence: 1.0, prov_inferred: false }]->(d)
                CREATE (r)-[:OWNS_RESOURCE { prov_confidence: 0.8, prov_inferred: true }]->(c)
                WITH d, row
                MATCH (e:Environment { key: ${'$'}environments[row.i % 3] })
                CREATE (d)-[:TO_ENVIRONMENT { prov_confidence: 1.0, prov_inferred: false }]->(e)
                """.trimIndent(),
            ).bindAll(mapOf("repositories" to repositories, "environments" to ENVIRONMENTS.map { "$run-$it" }))
            .run()

        val dependencies =
            (1 until REPOSITORIES).flatMap { i ->
                val first = (i - 1) / FAN_OUT
                listOfNotNull(first, (first + 1).takeIf { it < i && i % 2 == 0 }).map { target ->
                    mapOf("from" to "github.com/$run/r$i", "to" to "github.com/$run/r$target")
                }
            }
        neo4jClient
            .query(
                """
                UNWIND ${'$'}dependencies AS dep
                MATCH (a:Repository { key: dep.from }), (b:Repository { key: dep.to })
                CREATE (a)-[:DEPENDS_ON { kind: 'library', prov_confidence: 0.95, prov_inferred: false }]->(b)
                """.trimIndent(),
            ).bindAll(mapOf("dependencies" to dependencies))
            .run()
    }

    private companion object {
        const val REPOSITORIES = 2000
        const val NODES = REPOSITORIES * 5 + 3
        const val FAN_OUT = 3
        const val WARM_UP = 3
        const val SAMPLES = 20
        const val MIN_AFFECTED = 500
        const val NANOS_PER_MILLI = 1_000_000L
        val ENVIRONMENTS = listOf("development", "staging", "production")

        /** Four times the issue's 500 ms target; see the class comment for why it is not the target itself. */
        const val P95_CEILING_MILLIS = 2000L
    }
}
