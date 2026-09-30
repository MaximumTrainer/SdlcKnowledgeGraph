package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.SourceSystemDef

/**
 * Wire form of the ontology. Consumers build their own editors and schemas from this, so property
 * types are published as their wire names rather than Kotlin enum constants.
 */
data class OntologyResponse(
    val version: String,
    val nodeTypes: List<NodeTypeResponse>,
    val edgeTypes: List<EdgeTypeResponse>,
    /** The provenance envelope every node and edge carries, so a client can render it generically. */
    val provenance: ProvenanceEnvelopeResponse,
    /** The source systems a write may name (#117), so a connector author can see which to stamp. */
    val sources: List<SourceSystemResponse>,
) {
    companion object {
        fun from(registry: OntologyRegistry): OntologyResponse =
            OntologyResponse(
                version = registry.version,
                nodeTypes = registry.allNodeTypes().map(NodeTypeResponse::from),
                edgeTypes = registry.allEdgeTypes().map(EdgeTypeResponse::from),
                provenance = ProvenanceEnvelopeResponse(registry.provenance.map(PropertyResponse::from)),
                sources = registry.sources.map(SourceSystemResponse::from),
            )
    }
}

data class NodeTypeResponse(
    val name: String,
    val description: String?,
    val identity: List<String>,
    val properties: List<PropertyResponse>,
    /** True for a type that describes the graph itself, which a client browsing the software leaves out. */
    val meta: Boolean,
) {
    companion object {
        fun from(nodeType: NodeTypeDef): NodeTypeResponse =
            NodeTypeResponse(
                name = nodeType.name,
                description = nodeType.description,
                identity = nodeType.identity,
                properties = nodeType.properties.map(PropertyResponse::from),
                meta = nodeType.meta,
            )
    }
}

data class EdgeTypeResponse(
    val name: String,
    val description: String?,
    val from: List<String>,
    val to: List<String>,
    val inverse: String,
    /** `propagates` when a change travels along this edge, so a client can draw the blast radius (#21). */
    val impact: String,
    /** `forward` or `inverse`: which way a change travels along it. */
    val downstream: String,
    /** `owner`, `inherits` or `none`: what it says about ownership. */
    val ownership: String,
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
                impact = edgeType.impact.wireName,
                downstream = edgeType.downstream.wireName,
                ownership = edgeType.ownership.wireName,
                properties = edgeType.properties.map(PropertyResponse::from),
            )
    }
}

data class SourceSystemResponse(
    val name: String,
    val description: String?,
) {
    companion object {
        fun from(source: SourceSystemDef): SourceSystemResponse = SourceSystemResponse(source.name, source.description)
    }
}

data class ProvenanceEnvelopeResponse(
    val properties: List<PropertyResponse>,
)

data class PropertyResponse(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String?,
    /** Omitted when the property is unconstrained, so a form only offers a choice where there is one. */
    @field:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val enum: List<String>? = null,
) {
    companion object {
        fun from(property: PropertyDef): PropertyResponse =
            PropertyResponse(
                name = property.name,
                type = property.type.wireName,
                required = property.required,
                description = property.description,
                enum = property.enum,
            )
    }
}

data class UnknownTypeResponse(
    val error: String,
    val type: String,
)
