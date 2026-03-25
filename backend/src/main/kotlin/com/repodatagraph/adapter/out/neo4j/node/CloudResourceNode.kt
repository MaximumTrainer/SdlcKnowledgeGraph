package com.repodatagraph.adapter.out.neo4j.node

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node

@Node("CloudResource")
data class CloudResourceNode(
    @Id val id: String,
    val provider: String,
    val resourceType: String,
    val name: String,
    val region: String? = null,
    val repoId: String? = null
)
