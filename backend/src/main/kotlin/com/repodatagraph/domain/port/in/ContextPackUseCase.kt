package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.model.ContextPack
import com.repodatagraph.domain.model.ContextPackQuery

/** The bounded subgraph a task needs, walked by a registry template from one node (#96). */
interface ContextPackUseCase {
    fun contextPack(query: ContextPackQuery): ContextPack
}
