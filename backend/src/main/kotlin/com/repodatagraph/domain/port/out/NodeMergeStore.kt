package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.identity.MergePlan
import com.repodatagraph.domain.identity.MergeResult

/**
 * Carries out a merge the application has already decided (#98, FR-4), all or nothing.
 *
 * A port of its own rather than a [GraphStore] method: a merge touches every relationship of two
 * nodes, the history of one and the lifecycle of the other, and has to happen in one transaction the
 * adapter holds open across several statements, which no single-statement write of [GraphStore] does.
 */
interface NodeMergeStore {
    /**
     * Moves every relationship of [MergePlan.source] onto [MergePlan.target], writes what the target
     * gained and the keys it absorbed, and retires the source as merged, pointing at the target and
     * recorded as [MergePlan.by]. With [dryRun], the same work is done and then rolled back, so what
     * it answers is exactly what the merge would do.
     */
    fun merge(
        plan: MergePlan,
        dryRun: Boolean,
    ): MergeResult
}
