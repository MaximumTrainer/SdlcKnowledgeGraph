package com.repodatagraph.domain.port.out

import java.time.Instant

/**
 * Ending facts in bulk, as opposed to writing them one at a time through [GraphStore].
 *
 * A port of its own rather than more methods on [GraphStore]: that one addresses a node by its key,
 * and this selects nodes by what their provenance says, which is a different question with a
 * different query shape.
 */
interface FactLifecycle {
    /**
     * Closes every node still open that [sourceSystem] asserted before [assertedBefore] and has not
     * asserted again since, setting its validity to end at [closedAt]. Nothing is deleted.
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
    ): Int
}
