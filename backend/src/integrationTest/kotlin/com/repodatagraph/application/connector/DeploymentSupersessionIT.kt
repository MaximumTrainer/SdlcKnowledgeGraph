package com.repodatagraph.application.connector

import com.repodatagraph.domain.lifecycle.ArtifactFamily
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.lifecycle.Supersession
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.FactLifecycle
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.DeploymentHistory
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.support.Neo4jTestcontainersConfig
import com.repodatagraph.support.connector.FakeConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * Which deployments are current where (#90, FR-4), and closing one when a newer one replaces it,
 * against a real Neo4j: the selection is a traversal through the artifact to the environment, and the
 * validity a retirement leaves is decided in Cypher, so neither can be proved with a store in memory.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class DeploymentSupersessionIT {
    @Autowired
    private lateinit var writer: GraphDeltaWriter

    @Autowired
    private lateinit var recorder: SyncRunRecorder

    @Autowired
    private lateinit var history: DeploymentHistory

    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var lifecycle: FactLifecycle

    private val actions =
        ConnectorDescriptor(
            "github-actions",
            "github-actions",
            setOf("Artifact", "Deployment", "Environment"),
            setOf("DEPLOYED_TO", "TO_ENVIRONMENT"),
            setOf(Capability.WEBHOOK),
        )

    /** Unique per test, so another test's deployments of the same family are never in scope. */
    private val name = "acme/supersession-" + UUID.randomUUID().toString().take(8)
    private val family = ArtifactFamily("ghcr.io", name)
    private val t1 = Instant.parse("2026-09-30T10:00:00Z")
    private val t2 = Instant.parse("2026-09-30T11:00:00Z")

    private fun deploy(
        digest: String,
        at: Instant,
        environment: String = "production",
        registry: String? = "ghcr.io",
        status: String = "SUCCESS",
    ): NodeKey {
        val artifactProps =
            mapOf("registry" to registry, "name" to name, "digest" to digest, "version" to "1.0.0", "artifactType" to "container-image")
                .filterValues { it != null }
        val artifact = NodeKey("Artifact", (registry?.let { "$it/" } ?: "") + "$name@$digest")
        val deployment = NodeKey("Deployment", "${artifact.key}#$environment#${at.epochSecond}")
        val environmentKey = NodeKey("Environment", environment)
        writer.apply(
            GraphDelta(
                nodes =
                    listOf(
                        NodeUpsert("Artifact", artifactProps),
                        NodeUpsert("Environment", mapOf("name" to environment, "type" to "other")),
                        NodeUpsert(
                            "Deployment",
                            mapOf(
                                "artifactKey" to artifact.key,
                                "environmentKey" to environment,
                                "deployedAt" to at,
                                "artifactId" to artifact.key,
                                "environmentId" to environment,
                                "status" to status,
                            ),
                            validFrom = at,
                        ),
                    ),
                edges =
                    listOf(
                        EdgeUpsert("DEPLOYED_TO", artifact, deployment, validFrom = at),
                        EdgeUpsert("TO_ENVIRONMENT", deployment, environmentKey, validFrom = at),
                    ),
            ),
            actions,
            run(),
        )
        return deployment
    }

    @Test
    fun `the current deployments of a family to an environment are found through its artifacts`() {
        val current = deploy("sha256:a", t1)
        deploy("sha256:s", t1, environment = "staging")
        val failed = deploy("sha256:f", t2, status = "FAILED")

        val found = history.currentDeployments(family, "production")

        assertThat(found.map { it.key }).containsExactlyInAnyOrder(current, failed)
        assertThat(found.single { it.key == current }.deployedAt).isEqualTo(t1)
        assertThat(found.single { it.key == current }.succeeded).isTrue()
        assertThat(found.single { it.key == failed }.succeeded).isFalse()
    }

    @Test
    fun `an artifact with no registry is a family of its own`() {
        val bare = deploy("sha256:b", t1, registry = null)

        assertThat(history.currentDeployments(family, "production")).isEmpty()
        assertThat(history.currentDeployments(ArtifactFamily(null, name), "production").map { it.key }).containsExactly(bare)
    }

    @Test
    fun `a superseded deployment ends at the instant the newer one began, and is no longer current`() {
        val earlier = deploy("sha256:a", t1)
        deploy("sha256:b", t2)

        writer.apply(GraphDelta(supersessions = listOf(Supersession(earlier, t2))), actions, run())

        assertThat(graphStore.findNode(earlier)!!.provenance.validTo).isEqualTo(t2)
        assertThat(lifecycle.history(earlier)!!.current.retiredReason).isEqualTo(RetiredReason.SUPERSEDED)
        assertThat(history.currentDeployments(family, "production").map { it.key }).doesNotContain(earlier)
    }

    /** A recorded run, since what a run writes is linked to it. */
    private fun run(): String =
        UUID.randomUUID().toString().also { id ->
            recorder.recordRun(
                id,
                RegisteredConnector(FakeConnector(name = "supersession-it"), enabled = true),
                SyncMode.WEBHOOK,
                RunStatus.RUNNING,
                DeltaResult(),
                null,
                null,
            )
        }
}
