package com.repodatagraph.application

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * An artifact first seen without a digest, keyed `<name>:<version>`, is folded into the node keyed by
 * its digest once one is written (#98, FR-2): deterministically, idempotently, and never when the two
 * differ in anything but the digest or when more than one digest could be the one it meant.
 */
class ArtifactFoldingTest {
    private val graphStore: GraphStore = mock()
    private val merged = mutableListOf<Triple<NodeKey, NodeKey, Provenance>>()
    private val folding = ArtifactFolding(graphStore) { from, into, by -> merged += Triple(from, into, by) }

    private val at = Instant.parse("2026-01-01T00:00:00Z")
    private val provenance = Provenance(sourceSystem = "github-actions", ingestedAt = at, validFrom = at, syncRunId = "run-1")
    private val versionOnlyKey = NodeKey("Artifact", "payments:1.4.2")
    private val digestKey = NodeKey("Artifact", "ghcr.io/acme/payments@sha256:abc")

    private fun versionOnly(
        extra: Map<String, Any?> = emptyMap(),
        validTo: Instant? = null,
    ) = GraphNode(
        versionOnlyKey,
        mapOf("name" to "payments", "version" to "1.4.2", "artifactType" to "container-image", "identityQuality" to "version-only") + extra,
        Provenance.manual(at).copy(validTo = validTo),
    )

    private fun digest(
        key: NodeKey = digestKey,
        digest: String = "sha256:abc",
        extra: Map<String, Any?> = emptyMap(),
    ) = GraphNode(
        key,
        mapOf(
            "registry" to "ghcr.io/acme",
            "name" to "payments",
            "version" to "1.4.2",
            "digest" to digest,
            "artifactType" to "container-image",
            "identityQuality" to "digest",
        ) + extra,
        provenance,
    )

    private fun given(
        versionOnly: GraphNode?,
        vararg digests: GraphNode,
    ) {
        whenever(graphStore.findNode(versionOnlyKey)).thenReturn(versionOnly)
        whenever(graphStore.findNodes(eq("Artifact"), eq(mapOf("name" to "payments", "version" to "1.4.2")), anyOrNull(), anyOrNull()))
            .thenReturn(listOfNotNull(versionOnly) + digests)
    }

    @Test
    fun `writing the digest folds the version-only node into it, as the write that saw it`() {
        val written = digest()
        given(versionOnly(), written)

        folding.afterWrite(written)

        assertThat(merged).containsExactly(Triple(versionOnlyKey, digestKey, provenance))
    }

    @Test
    fun `a version-only node that also holds values the digest node lacks is still the same artifact`() {
        val written = digest()
        given(versionOnly(mapOf("commitSha" to "c1")), written)

        folding.afterWrite(written)

        assertThat(merged).hasSize(1)
    }

    @Test
    fun `two artifacts that differ in more than the digest are never folded`() {
        val written = digest(extra = mapOf("commitSha" to "c2"))
        given(versionOnly(mapOf("commitSha" to "c1")), written)

        folding.afterWrite(written)

        assertThat(merged).isEmpty()
    }

    @Test
    fun `two digests for one name and version could each be the one it meant, so neither takes it`() {
        val written = digest()
        val other = digest(NodeKey("Artifact", "ghcr.io/acme/payments@sha256:def"), "sha256:def")
        given(versionOnly(), written, other)

        folding.afterWrite(written)

        assertThat(merged).isEmpty()
    }

    @Test
    fun `a version-only node already folded, or otherwise retired, is left alone`() {
        val written = digest()
        given(versionOnly(validTo = at.plusSeconds(60)), written)

        folding.afterWrite(written)

        assertThat(merged).isEmpty()
    }

    @Test
    fun `nothing is folded when there is no version-only node`() {
        val written = digest()
        given(null, written)

        folding.afterWrite(written)

        assertThat(merged).isEmpty()
    }

    @Test
    fun `writing a version-only artifact, or anything but an artifact, folds nothing`() {
        folding.afterWrite(versionOnly())
        folding.afterWrite(GraphNode(NodeKey("Team", "platform"), mapOf("name" to "platform"), provenance))

        assertThat(merged).isEmpty()
    }

    @Test
    fun `an artifact written with a digest and no version has no version-only twin`() {
        folding.afterWrite(digest(extra = mapOf("version" to null)))

        assertThat(merged).isEmpty()
    }
}
