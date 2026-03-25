package com.repodatagraph.adapter.out.neo4j.node

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import java.time.Instant

@Node("Deployment")
data class DeploymentNode(
    @Id val id: String,
    val artifactId: String,
    val environmentId: String,
    val deployedAt: Instant = Instant.now(),
    val deployedBy: String? = null,
    val status: String = "SUCCESS",
    @Relationship(type = "TO_ENVIRONMENT", direction = Relationship.Direction.OUTGOING)
    val environment: EnvironmentNode? = null
)
