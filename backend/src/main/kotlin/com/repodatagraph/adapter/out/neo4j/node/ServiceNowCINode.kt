package com.repodatagraph.adapter.out.neo4j.node

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node

@Node("ServiceNowCI")
data class ServiceNowCINode(
    @Id val id: String,
    val ciName: String,
    val serviceId: String,
    val repoId: String? = null
)
