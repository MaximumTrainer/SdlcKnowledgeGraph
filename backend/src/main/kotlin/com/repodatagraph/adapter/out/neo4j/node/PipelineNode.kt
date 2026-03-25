package com.repodatagraph.adapter.out.neo4j.node

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node

@Node("Pipeline")
data class PipelineNode(
    @Id val id: String,
    val name: String,
    val provider: String,
    val repoId: String,
    val lastRunStatus: String? = null
)
