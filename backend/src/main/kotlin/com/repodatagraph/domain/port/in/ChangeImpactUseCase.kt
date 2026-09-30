package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.ChangeImpactQuery
import com.repodatagraph.domain.model.ChangeImpactResult

/** What a change to a repository reaches, ranked for an agent's context pack (#87). */
interface ChangeImpactUseCase {
    fun changeImpact(query: ChangeImpactQuery): ChangeImpactResult
}
