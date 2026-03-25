package com.repodatagraph.adapter.out.neo4j.node

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship

@Node("Artifact")
data class ArtifactNode(
    @Id val id: String,
    val name: String,
    val version: String,
    val commitSha: String? = null,
    val repoId: String? = null,
    val artifactType: String = "docker",
    @Relationship(type = "DEPLOYED_TO", direction = Relationship.Direction.OUTGOING)
    val deployments: List<DeploymentNode> = emptyList()
)
