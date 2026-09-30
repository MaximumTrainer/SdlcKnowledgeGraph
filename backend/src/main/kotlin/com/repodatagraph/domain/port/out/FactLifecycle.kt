package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.lifecycle.NodeHistory
import com.repodatagraph.domain.lifecycle.RetiredReason
import com.repodatagraph.domain.model.NodeKey
import java.time.Instant

/**
 * Ending facts in bulk or by reason, as opposed to writing them one at a time through [GraphStore],
 * and reading back what a node held before.
 *
 * A port of its own rather than more methods on [GraphStore]: that one addresses a node by its key
 * and writes what it is given, and this selects nodes by what their provenance says and retires them
 * with their relationships, which are different questions with different query shapes.
 */
interface FactLifecycle {
    /**
     * Retires every node still open that [sourceSystem] asserted before [assertedBefore] and has not
     * asserted again since, ending its validity, and that of its current relationships, at
     * [closedAt] and recording [reason]. Nothing is deleted.
     *
     * This is how a complete sync says "the source stopped reporting these" (#150): every node the run
     * wrote was stamped at or after it began, so what is older than that was not seen. Selecting on the
     * time rather than on the run's own writes also spares a node another run - a webhook, say -
     * asserted while this one was going.
     *
     * @return how many nodes were closed
     */
    fun closeNodesNotReasserted(
        sourceSystem: String,
        assertedBefore: Instant,
        closedAt: Instant,
        reason: RetiredReason = RetiredReason.MISSING_FROM_SYNC,
    ): Int

    /**
     * Retires one node at [at] for [reason] (#33, FR4), with its current relationships; a node already
     * retired keeps the date and reason it has.
     *
     * @return false when there is no such node
     */
    fun retire(
        key: NodeKey,
        at: Instant,
        reason: RetiredReason,
    ): Boolean

    /** The node's current validity and values and its earlier versions, newest first; null when there is no such node. */
    fun history(key: NodeKey): NodeHistory?
}
