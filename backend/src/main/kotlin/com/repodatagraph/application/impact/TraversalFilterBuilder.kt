package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.ImpactDirection
import com.repodatagraph.domain.model.Traversal
import com.repodatagraph.domain.ontology.EdgeImpact
import com.repodatagraph.domain.ontology.EdgeOwnership
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.ImpactAlong
import com.repodatagraph.domain.ontology.OntologyRegistry
import org.springframework.stereotype.Component

/**
 * Turns the registry's `impact` and `ownership` flags into the edges a walk may take (#21, FR2).
 *
 * This is the only place the choice is made, and it is made from the registry: no traversal names an
 * edge, so an edge flagged in edges.yaml joins the blast radius without a code change.
 */
@Component
class TraversalFilterBuilder(
    private val registry: OntologyRegistry,
) {
    /** The propagating edges, each read the way a change travels ([ImpactDirection.DOWNSTREAM]) or against it. */
    fun impact(direction: ImpactDirection): Traversal {
        val propagating = registry.allEdgeTypes().filter { it.impact == EdgeImpact.PROPAGATES }
        return traversal(propagating, reversed = direction == ImpactDirection.UPSTREAM)
    }

    /** The edges ownership is inherited along, read against the way a change travels: back to what owns the change. */
    fun ownershipInheritance(): Traversal =
        traversal(registry.allEdgeTypes().filter { it.ownership == EdgeOwnership.INHERITS }, reversed = true)

    /** The edges that name an owner, followed as stored. */
    fun ownerEdges(): Set<String> =
        registry
            .allEdgeTypes()
            .filter { it.ownership == EdgeOwnership.OWNER }
            .mapTo(linkedSetOf()) { it.name }

    /**
     * The edges that place a node in an environment (#87): the propagating ones that end at an
     * Environment, followed as stored. Only a propagating edge, because an edge a change does not
     * travel along - a sync run's PRODUCED - says nothing about where something runs.
     */
    fun placement(): Set<String> =
        registry
            .allEdgeTypes()
            .filter { it.impact == EdgeImpact.PROPAGATES && ENVIRONMENT in it.to }
            .mapTo(linkedSetOf()) { it.name }

    private fun traversal(
        edges: List<EdgeTypeDef>,
        reversed: Boolean,
    ): Traversal {
        val (forward, inverse) = edges.partition { (it.downstream == ImpactAlong.FORWARD) != reversed }
        return Traversal(
            forward = forward.mapTo(linkedSetOf()) { it.name },
            inverse = inverse.associate { it.name to it.inverse },
        )
    }
}

private const val ENVIRONMENT = "Environment"
