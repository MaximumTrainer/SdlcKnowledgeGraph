package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.CandidateLink
import com.repodatagraph.domain.model.CandidatePage
import com.repodatagraph.domain.model.CandidateQuery
import com.repodatagraph.domain.model.DeploymentEvidence
import com.repodatagraph.domain.model.TouchedKeys

/**
 * The link engine's reads that span more than one edge (#28): the review list, a candidate by id,
 * the deployments naming a resource, and what a sync run touched. Only current facts are read.
 */
interface LinkQueries {
    fun candidates(query: CandidateQuery): CandidatePage

    fun candidate(id: String): CandidateLink?

    fun deploymentsTargeting(resourceKey: String): List<DeploymentEvidence>

    fun touchedBy(runId: String): TouchedKeys
}
