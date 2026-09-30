package com.repodatagraph.application.connector

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.application.IdentityFolding
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.FactLifecycle
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.core.io.DefaultResourceLoader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * An artifact a connector or a deploy report writes is offered to the folding, so a digest-less twin
 * written earlier is folded into it whichever writer saw the digest (#98, FR-2). What folding
 * decides is ArtifactFolding's; this is only that every writer asks.
 */
class GraphDeltaWriterFoldingTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val offered = mutableListOf<GraphNode>()
    private val writer =
        GraphDeltaWriter(
            mock<GraphStore>(),
            mock<FactLifecycle>(),
            IdentityResolver(),
            DerivedProperties(GitRemoteParser()),
            Clock.fixed(now, ZoneOffset.UTC),
            YamlOntologyLoader(DefaultResourceLoader()).load(),
            IdentityFolding { offered += it },
        )
    private val reporter = ConnectorDescriptor("github-actions", "github-actions", setOf("Artifact"), emptySet(), setOf(Capability.FULL))

    @Test
    fun `a written artifact is offered to the folding with the provenance of the run that wrote it`() {
        val delta =
            GraphDelta(
                nodes =
                    listOf(
                        NodeUpsert(
                            "Artifact",
                            mapOf(
                                "registry" to "ghcr.io/acme",
                                "name" to "payments",
                                "version" to "1.4.2",
                                "digest" to "sha256:abc",
                                "artifactType" to "container-image",
                            ),
                        ),
                    ),
            )

        writer.apply(delta, reporter, "run-1")

        assertThat(offered.map { it.key }).containsExactly(NodeKey("Artifact", "ghcr.io/acme/payments@sha256:abc"))
        assertThat(offered.single().props).containsEntry("identityQuality", "digest")
        assertThat(offered.single().provenance.syncRunId).isEqualTo("run-1")
    }
}
