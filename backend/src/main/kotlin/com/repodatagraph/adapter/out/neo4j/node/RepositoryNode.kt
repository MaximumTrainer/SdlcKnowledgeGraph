package com.repodatagraph.adapter.out.neo4j.node

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship

@Node("Repository")
data class RepositoryNode(
    @Id val id: String,
    val orgRepo: String,
    val defaultBranch: String = "main",
    val topics: List<String> = emptyList(),
    val codeowners: List<String> = emptyList(),
    val serviceId: String? = null,
    val language: String? = null,
    val description: String? = null,
    @Relationship(type = "OWNED_BY", direction = Relationship.Direction.OUTGOING)
    val team: TeamNode? = null,
    @Relationship(type = "OWNS_RESOURCE", direction = Relationship.Direction.OUTGOING)
    val cloudResources: List<CloudResourceNode> = emptyList(),
    @Relationship(type = "DEPENDS_ON", direction = Relationship.Direction.OUTGOING)
    val dependencies: List<RepositoryNode> = emptyList(),
    @Relationship(type = "HAS_PIPELINE", direction = Relationship.Direction.OUTGOING)
    val pipelines: List<PipelineNode> = emptyList(),
    @Relationship(type = "RELATES_TO_CI", direction = Relationship.Direction.OUTGOING)
    val serviceNowCI: ServiceNowCINode? = null
)
