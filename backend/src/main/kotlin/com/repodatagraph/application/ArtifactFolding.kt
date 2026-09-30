package com.repodatagraph.application

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.stereotype.Component

/**
 * Folds an artifact first seen without a digest, keyed `<name>:<version>`, into the node keyed by its
 * digest once a writer reports one (#98, FR-2).
 *
 * Deterministic and cautious by construction. It acts only when the digest node just written is the
 * one current node with that name and version and a digest, so two images tagged alike never fight
 * over one version-only node; only when the two differ in nothing but the digest, so a node built
 * from another commit is never taken for this one; and only while the version-only node is current,
 * so folding it again, or folding one a person retired, does nothing.
 */
@Component
class ArtifactFolding(
    private val graphStore: GraphStore,
    private val merge: AutomaticMerge,
) : IdentityFolding {
    override fun afterWrite(node: GraphNode) {
        twinOf(node)
            ?.takeIf { soleDigestFor(node) && sameArtifact(it, node) }
            ?.let { merge.fold(it.key, node.key, node.provenance) }
    }

    /** The current version-only node a digest-keyed artifact may be the digest of, or null. */
    private fun twinOf(node: GraphNode): GraphNode? {
        val name = value(node, NAME)
        val version = value(node, VERSION)
        val keyed = node.type == ARTIFACT && value(node, DIGEST) != null && name != null && version != null
        return if (keyed) {
            graphStore
                .findNode(NodeKey(ARTIFACT, "$name:$version"))
                ?.takeIf { it.provenance.current && value(it, DIGEST) == null }
        } else {
            null
        }
    }

    /** The node just written is the one current artifact with its name and version and a digest. */
    private fun soleDigestFor(node: GraphNode): Boolean =
        graphStore
            .findNodes(ARTIFACT, mapOf(NAME to node.props[NAME], VERSION to node.props[VERSION]))
            .filter { value(it, DIGEST) != null && it.provenance.current }
            .singleOrNull()
            ?.key == node.key

    /** No value both hold differs, but for what a digest changes. */
    private fun sameArtifact(
        versionOnly: GraphNode,
        digest: GraphNode,
    ): Boolean =
        versionOnly.props.keys
            .filter { it !in DIGEST_BOUND }
            .none { versionOnly.props[it] != null && digest.props[it] != null && versionOnly.props[it] != digest.props[it] }

    private fun value(
        node: GraphNode,
        property: String,
    ): String? = node.props[property]?.toString()?.takeIf { it.isNotBlank() }

    private companion object {
        const val ARTIFACT = "Artifact"
        const val NAME = "name"
        const val VERSION = "version"
        const val DIGEST = "digest"
        val DIGEST_BOUND = setOf(DIGEST, "identityQuality")
    }
}
