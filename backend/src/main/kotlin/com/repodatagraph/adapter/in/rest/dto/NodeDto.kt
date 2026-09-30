package com.repodatagraph.adapter.`in`.rest.dto

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodePage
import com.repodatagraph.domain.model.Provenance
import java.time.Instant

/**
 * What a client may send: properties, and optionally the source system it states them as.
 *
 * There is deliberately no `id` or `key` field. Identity is derived from the properties by the
 * server, so a client cannot claim one, and a client that tries has its request refused by the
 * validator rather than silently ignored.
 */
data class NodeRequest(
    val props: Map<String, Any?> = emptyMap(),
    val provenance: ProvenanceRequest? = null,
)

/**
 * The part of a fact's provenance a caller states (#117): the system of record it speaks for, which
 * must be declared in sources.yaml and, unless it is `manual`, allowed by a `graph:write:<source>`
 * scope. Left out, the write is `manual`. The rest of the envelope - who wrote it, when, how sure -
 * is the server's to record, so it is not accepted from a caller.
 */
data class ProvenanceRequest(
    val sourceSystem: String? = null,
    /**
     * When the fact stopped holding (#93, FR-5), on a `PUT` only: it closes the node at that instant,
     * or keeps it closed. A `POST` may not name one; a fact is stated as holding before it can end.
     */
    val validTo: Instant? = null,
) {
    companion object {
        fun sourceOf(provenance: ProvenanceRequest?): String = provenance?.sourceSystem ?: Provenance.MANUAL

        /** Where a validTo a request may not carry is reported, as the body names it. */
        const val VALID_TO_FIELD = "provenance.validTo"
        const val VALID_TO_ON_CREATE = "validTo can only be set on an existing fact, through PUT"
    }
}

/**
 * A fact's provenance as the API renders it: the envelope as stored, and whether the fact is stale
 * now (#93, FR-1) - current, and not stated by its source within the source's freshness window.
 * Computed on each read, never stored.
 */
data class ProvenanceResponse(
    @get:JsonUnwrapped val envelope: Provenance,
    val stale: Boolean,
) {
    companion object {
        fun of(
            provenance: Provenance,
            freshness: FactFreshness,
        ) = ProvenanceResponse(provenance, freshness.stale(provenance))
    }
}

/** A node as the API renders it: the derived identity, the properties, and who said so. */
data class NodeResponse(
    val id: String,
    val type: String,
    val key: String,
    val props: Map<String, Any?>,
    val provenance: ProvenanceResponse,
) {
    companion object {
        fun from(
            node: GraphNode,
            freshness: FactFreshness,
        ) = NodeResponse(
            id = node.id,
            type = node.type,
            key = node.key.key,
            props = node.props,
            provenance = ProvenanceResponse.of(node.provenance, freshness),
        )
    }
}

/** One page of nodes. [nextCursor] is null on the last page. */
data class NodePageResponse(
    val items: List<NodeResponse>,
    val nextCursor: String?,
) {
    companion object {
        fun from(
            page: NodePage,
            freshness: FactFreshness,
        ) = NodePageResponse(page.items.map { NodeResponse.from(it, freshness) }, page.nextCursor)
    }
}
