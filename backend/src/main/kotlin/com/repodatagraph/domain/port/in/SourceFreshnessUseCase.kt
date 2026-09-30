package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.SourceLag

/** How far behind each source is (#93, FR-3), for the health component and the home page alike. */
interface SourceFreshnessUseCase {
    /** Every source with something to lag - a successful run, or an enabled connector - in declaration order. */
    fun lag(): List<SourceLag>
}
