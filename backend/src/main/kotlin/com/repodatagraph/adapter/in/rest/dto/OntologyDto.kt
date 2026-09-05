package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef

/**
 * Wire form of the ontology. Consumers build their own editors and schemas from this, so property
 * types are published as their wire names rather than Kotlin enum constants.
 */
data class OntologyResponse(
    val version: String,
    val nodeTypes: List<NodeTypeResponse>,
    val edgeTypes: List<EdgeTypeResponse>,
) {
    companion object {
        fun from(registry: OntologyRegistry): OntologyResponse =
            OntologyResponse(
                version = registry.version,
                nodeTypes = registry.allNodeTypes().map(NodeTypeResponse::from),
                edgeTypes = registry.allEdgeTypes().map(EdgeTypeResponse::from),
            )
    }
}

data class NodeTypeResponse(
    val name: String,
    val description: String?,
    val identity: List<String>,
    val properties: List<PropertyResponse>,
) {
    companion object {
        fun from(nodeType: NodeTypeDef): NodeTypeResponse =
            NodeTypeResponse(
                name = nodeType.name,
                description = nodeType.description,
                identity = nodeType.identity,
                properties = nodeType.properties.map(PropertyResponse::from),
            )
    }
}

data class EdgeTypeResponse(
    val name: String,
    val description: String?,
    val from: List<String>,
    val to: List<String>,
    val inverse: String,
    val properties: List<PropertyResponse>,
) {
    companion object {
        fun from(edgeType: EdgeTypeDef): EdgeTypeResponse =
            EdgeTypeResponse(
                name = edgeType.name,
                description = edgeType.description,
                from = edgeType.from,
                to = edgeType.to,
                inverse = edgeType.inverse,
                properties = edgeType.properties.map(PropertyResponse::from),
            )
    }
}

data class PropertyResponse(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String?,
) {
    companion object {
        fun from(property: PropertyDef): PropertyResponse =
            PropertyResponse(
                name = property.name,
                type = property.type.wireName,
                required = property.required,
                description = property.description,
            )
    }
}

data class UnknownTypeResponse(
    val error: String,
    val type: String,
)
