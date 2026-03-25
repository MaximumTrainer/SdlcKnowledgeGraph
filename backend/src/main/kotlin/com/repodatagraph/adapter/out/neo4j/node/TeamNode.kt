package com.repodatagraph.adapter.out.neo4j.node

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node

@Node("Team")
data class TeamNode(
    @Id val id: String,
    val name: String,
    val email: String? = null
)
