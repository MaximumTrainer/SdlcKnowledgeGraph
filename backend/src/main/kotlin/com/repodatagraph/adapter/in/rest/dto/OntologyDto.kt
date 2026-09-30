package com.repodatagraph.adapter.`in`.rest.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.ontology.Deprecation
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
    /**
     * How long each source's facts stay fresh (#93, FR-2), so an agent can read the policy a `stale`
     * flag was judged by. Null only where no policy was given, which the snapshot is rendered without.
     */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val freshness: FreshnessPolicyResponse? = null,
) {
    companion object {
        fun from(
            registry: OntologyRegistry,
            freshness: FreshnessPolicy? = null,
        ): OntologyResponse =
            OntologyResponse(
                version = registry.version,
                nodeTypes = registry.allNodeTypes().map(NodeTypeResponse::from),
                edgeTypes = registry.allEdgeTypes().map(EdgeTypeResponse::from),
                provenance = ProvenanceEnvelopeResponse(registry.provenance.map(PropertyResponse::from)),
                sources = registry.sources.map(SourceSystemResponse::from),
                freshness = freshness?.let(FreshnessPolicyResponse::from),
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
    /** The property a node of this type is labelled with where it is drawn (#9); null for its key. */
    val displayProperty: String?,
    /** Properties that together find a node beside its key, unique where all are present (#88); empty for none. */
    val alias: List<String>,
    /** Questions a reader answers with this type (#81), so an agent knows when to reach for it. */
    val questions: List<String>,
    /** Whole example nodes, each one the API accepts (#81). */
    val examples: List<Map<String, Any?>>,
) {
    companion object {
        fun from(nodeType: NodeTypeDef): NodeTypeResponse =
            NodeTypeResponse(
                name = nodeType.name,
                description = nodeType.description,
                identity = nodeType.identity,
                properties = nodeType.properties.map(PropertyResponse::from),
                meta = nodeType.meta,
                displayProperty = nodeType.displayProperty,
                alias = nodeType.alias,
                questions = nodeType.questions,
                examples = nodeType.examples,
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
    val questions: List<String>,
    /** Example property sets for an edge of this type (#81). */
    val examples: List<Map<String, Any?>>,
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
                questions = edgeType.questions,
                examples = edgeType.examples,
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
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val enum: List<String>? = null,
    /** The shape a value takes (#81), such as `url` or `email`; omitted where none is declared. */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val format: String? = null,
    /** The sibling values under which the format applies, such as provider aws; omitted when it always does. */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val formatWhen: Map<String, String>? = null,
    /** Values the property accepts, the first one shown wherever one is (#81). */
    val examples: List<Any?> = emptyList(),
    /** Since when the property is deprecated and what replaces it; omitted for a current property. */
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val deprecated: DeprecationResponse? = null,
) {
    companion object {
        fun from(property: PropertyDef): PropertyResponse =
            PropertyResponse(
                name = property.name,
                type = property.type.wireName,
                required = property.required,
                description = property.description,
                enum = property.enum,
                format = property.format?.wireName,
                formatWhen = property.formatWhen.takeIf { it.isNotEmpty() },
                examples = property.examples,
                deprecated = property.deprecated?.let(DeprecationResponse::from),
            )
    }
}

data class DeprecationResponse(
    val since: String,
    /** The property of the same type, or the edge type, to use instead. */
    val replacedBy: String?,
) {
    companion object {
        fun from(deprecation: Deprecation): DeprecationResponse = DeprecationResponse(deprecation.since, deprecation.replacedBy)
    }
}

data class UnknownTypeResponse(
    val error: String,
    val type: String,
)

/** A `format` the ontology cannot be rendered in, with the ones it can (#81). */
data class UnknownFormatResponse(
    val error: String,
    val format: String,
    val supported: List<String>,
)
