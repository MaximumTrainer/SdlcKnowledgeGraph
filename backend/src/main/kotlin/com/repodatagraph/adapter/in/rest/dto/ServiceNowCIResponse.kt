package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.GraphNode
import io.swagger.v3.oas.annotations.media.Schema

/**
 * A configuration item in the shape the `/servicenow` endpoint has always returned.
 *
 * A translation, not a model. The CMDB's shape is declared in the ontology registry now, and the
 * connector writes it there; what survives here is the four fields an existing caller of
 * `GET /api/v1/graph/repositories/{id}/servicenow` reads, so nothing built against that endpoint has
 * to change on the day the registry grew a richer `ConfigurationItem`.
 *
 * The richer view - criticality, support group, environment, and everything a later connector adds -
 * is at `GET /api/v1/nodes/ConfigurationItem/{key}`, which reports whatever the registry declares
 * without anybody adding a field here.
 *
 * The schema keeps its old name so the published OpenAPI document is unchanged to the byte. A
 * generated client would otherwise get a renamed type for a payload that is identical, which is a
 * break in everything but substance.
 */
@Schema(name = "ServiceNowCI", description = "A configuration item, in the shape this endpoint has always returned")
data class ServiceNowCIResponse(
    val id: String,
    val ciName: String,
    val serviceId: String,
    val repoId: String?,
) {
    companion object {
        fun from(
            node: GraphNode,
            repoId: String,
        ) = ServiceNowCIResponse(
            id = node.id,
            ciName = node.props["ciName"]?.toString().orEmpty(),
            // Empty rather than absent, because that is what this endpoint has always sent and a
            // caller checking for a missing field would start seeing one.
            serviceId = node.props["serviceId"]?.toString().orEmpty(),
            repoId = repoId,
        )
    }
}
