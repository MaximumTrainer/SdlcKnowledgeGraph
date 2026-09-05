package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.exception.InvalidEdgeException
import com.repodatagraph.domain.exception.UnknownEdgeTypeException
import com.repodatagraph.domain.exception.UnknownNodeTypeException
import com.repodatagraph.domain.ontology.OntologyRegistry
import org.springframework.stereotype.Component

/**
 * Builds Cypher fragments, and is the only place a label or relationship type becomes part of a
 * query string.
 *
 * Values are always parameters, but Cypher has no parameter form for a label or a relationship
 * type, so those have to be interpolated. That is an injection surface. Every name is therefore
 * checked against the ontology registry first and rejected if it is not a declared type, which
 * means the set of strings that can ever reach a query is fixed at build time by the YAML. The
 * belt-and-braces character check exists so that a future registry loaded from somewhere less
 * trustworthy still cannot smuggle syntax through.
 */
@Component
class CypherBuilder(
    private val registry: OntologyRegistry,
) {
    /** Returns [type] if the registry declares it as a node type, otherwise refuses. */
    fun nodeLabel(type: String): String {
        val nodeType = registry.nodeType(type) ?: throw UnknownNodeTypeException(type)
        return safe(nodeType.name)
    }

    /** Returns [type] if the registry declares it as an edge type, otherwise refuses. */
    fun edgeType(type: String): String {
        val edgeType = registry.edgeType(type) ?: throw UnknownEdgeTypeException(type)
        return safe(edgeType.name)
    }

    /**
     * Checks the ontology allows this relationship between these two node types. Without this an
     * edge could connect anything to anything and the declared model would be decoration.
     */
    fun requireEdgeAllowed(
        edgeTypeName: String,
        fromType: String,
        toType: String,
    ) {
        val edgeType = registry.edgeType(edgeTypeName) ?: throw UnknownEdgeTypeException(edgeTypeName)
        if (!edgeType.connects(fromType, toType)) {
            throw InvalidEdgeException(
                "$edgeTypeName is not allowed from $fromType to $toType; " +
                    "it connects ${edgeType.from} to ${edgeType.to}",
            )
        }
    }

    /**
     * Drops any property the ontology does not declare for this type, so a caller cannot write
     * arbitrary keys onto a node. Provenance properties are added by the store itself and are
     * therefore not accepted here.
     */
    fun declaredProperties(
        type: String,
        props: Map<String, Any?>,
    ): Map<String, Any?> {
        val nodeType = registry.nodeType(type) ?: throw UnknownNodeTypeException(type)
        val declared = nodeType.properties.map { it.name }.toSet()
        return props.filterKeys { it in declared && !ProvenanceMapper.isProvenanceProperty(it) }
    }

    fun declaredEdgeProperties(
        type: String,
        props: Map<String, Any?>,
    ): Map<String, Any?> {
        val edgeType = registry.edgeType(type) ?: throw UnknownEdgeTypeException(type)
        val declared = edgeType.properties.map { it.name }.toSet()
        return props.filterKeys { it in declared && !ProvenanceMapper.isProvenanceProperty(it) }
    }

    private fun safe(name: String): String {
        require(SAFE_IDENTIFIER.matches(name)) { "ontology declares an unsafe identifier '$name'" }
        return name
    }

    private companion object {
        val SAFE_IDENTIFIER = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }
}
