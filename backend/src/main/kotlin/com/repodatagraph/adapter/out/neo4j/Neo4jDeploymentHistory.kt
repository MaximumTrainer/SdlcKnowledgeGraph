package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.lifecycle.ArtifactFamily
import com.repodatagraph.domain.lifecycle.DeploymentRecord
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.connector.DeploymentHistory
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository

/**
 * The deployments current in an environment for an artifact family (#90, FR-4), in one statement.
 *
 * Found by walking from the family's artifacts, because a Deployment holds only its artifact's key and
 * a key is no place to read a registry and a name back out of. Every step must still hold: a retired
 * deployment, or one whose relationships a retirement closed, is history and replaces nothing. An
 * artifact with no registry matches only a family with none.
 */
@Repository
class Neo4jDeploymentHistory(
    private val neo4jClient: Neo4jClient,
    private val cypher: CypherBuilder,
) : DeploymentHistory {
    override fun currentDeployments(
        family: ArtifactFamily,
        environmentKey: String,
    ): List<DeploymentRecord> =
        neo4jClient
            .query(
                """
                MATCH (a:${cypher.nodeLabel(ARTIFACT)} { name: ${'$'}name })-[x:${cypher.edgeType(DEPLOYED_TO)}]->
                      (d:${cypher.nodeLabel(DEPLOYMENT)})-[t:${cypher.edgeType(TO_ENVIRONMENT)}]->
                      (:${cypher.nodeLabel(ENVIRONMENT)} { key: ${'$'}environment })
                WHERE coalesce(a.registry, '') = ${'$'}registry
                  AND d.prov_validTo IS NULL AND x.prov_validTo IS NULL AND t.prov_validTo IS NULL
                RETURN DISTINCT d.key AS key, d.deployedAt AS deployedAt, d.status AS status
                ORDER BY key
                """.trimIndent(),
            ).bindAll(mapOf("name" to family.name, "registry" to family.registry.orEmpty(), "environment" to environmentKey))
            .fetch()
            .all()
            .mapNotNull { row ->
                ProvenanceMapper.instant(row["deployedAt"])?.let { deployedAt ->
                    DeploymentRecord(
                        key = NodeKey(DEPLOYMENT, row["key"].toString()),
                        family = family,
                        environmentKey = environmentKey,
                        deployedAt = deployedAt,
                        succeeded = row["status"] == SUCCESS,
                    )
                }
            }

    private companion object {
        const val ARTIFACT = "Artifact"
        const val DEPLOYMENT = "Deployment"
        const val ENVIRONMENT = "Environment"
        const val DEPLOYED_TO = "DEPLOYED_TO"
        const val TO_ENVIRONMENT = "TO_ENVIRONMENT"
        const val SUCCESS = "SUCCESS"
    }
}
