package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.CandidateLink
import com.repodatagraph.domain.model.CandidatePage
import com.repodatagraph.domain.model.CandidateQuery
import com.repodatagraph.domain.model.LinkScope
import com.repodatagraph.domain.model.OwnerLink
import com.repodatagraph.domain.model.ResolutionStarted

/** Cloud-to-repository link resolution and its review (#28). */
interface LinkUseCase {
    /** Starts a resolution over [scope] as a sync run, answering at once with its id. */
    fun resolve(scope: LinkScope): ResolutionStarted

    fun candidates(query: CandidateQuery): CandidatePage

    /** Makes the candidate a manual owner, closing rival owners and superseding other candidates. */
    fun accept(id: String): OwnerLink

    /** Marks the candidate rejected: a tombstone while its evidence holds. */
    fun reject(id: String): CandidateLink

    /** States that [repoKey] owns [resourceKey], as the principal's own word. */
    fun link(
        resourceKey: String,
        repoKey: String,
    ): OwnerLink

    /** Closes a manual link: sets its validTo, keeping the fact that it held. */
    fun unlink(
        resourceKey: String,
        repoKey: String,
    )
}
