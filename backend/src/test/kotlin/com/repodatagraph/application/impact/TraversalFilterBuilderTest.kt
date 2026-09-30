package com.repodatagraph.application.impact

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.model.ImpactDirection
import com.repodatagraph.domain.model.LineageTraversal
import com.repodatagraph.domain.ontology.EdgeImpact
import com.repodatagraph.domain.ontology.EdgeOwnership
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.ImpactAlong
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

/**
 * Which edges a change travels along, read from the registry and never written in a query (#21,
 * FR2). An edge flagged `impact: propagates` is walked the way its `downstream` says; upstream walks
 * every one of them the other way, and a step taken against the stored direction is named by the
 * edge's declared inverse.
 */
class TraversalFilterBuilderTest {
    private fun node(name: String) = NodeTypeDef(name, null, listOf("name"), listOf(PropertyDef("name", PropertyType.STRING, true)))

    private fun edge(
        name: String,
        from: String,
        to: String,
        inverse: String,
        impact: EdgeImpact = EdgeImpact.NONE,
        downstream: ImpactAlong = ImpactAlong.FORWARD,
        ownership: EdgeOwnership = EdgeOwnership.NONE,
    ) = EdgeTypeDef(name, null, listOf(from), listOf(to), inverse, emptyList(), impact, downstream, ownership)

    private val registry =
        OntologyRegistry(
            version = "1.0.0",
            nodeTypes = listOf(node("Repository"), node("Team"), node("CloudResource"), node("Library")),
            edgeTypes =
                listOf(
                    edge("OWNED_BY", "Repository", "Team", "OWNS", ownership = EdgeOwnership.OWNER),
                    edge("DEPENDS_ON", "Repository", "Repository", "DEPENDED_ON_BY", EdgeImpact.PROPAGATES, ImpactAlong.INVERSE),
                    edge(
                        "OWNS_RESOURCE",
                        "Repository",
                        "CloudResource",
                        "OWNED_BY_REPO",
                        EdgeImpact.PROPAGATES,
                        ownership = EdgeOwnership.INHERITS,
                    ),
                    edge("USES", "Repository", "Library", "USED_BY"),
                ),
        )

    private val builder = TraversalFilterBuilder(registry)

    @Test
    fun `downstream follows each propagating edge the way the registry says, and nothing else`() {
        val traversal = builder.impact(ImpactDirection.DOWNSTREAM)

        assertEquals(setOf("OWNS_RESOURCE"), traversal.forward)
        assertEquals(mapOf("DEPENDS_ON" to "DEPENDED_ON_BY"), traversal.inverse)
        assertEquals(setOf("OWNS_RESOURCE", "DEPENDS_ON"), traversal.edgeTypes)
    }

    @Test
    fun `upstream walks the same edges the other way`() {
        val traversal = builder.impact(ImpactDirection.UPSTREAM)

        assertEquals(setOf("DEPENDS_ON"), traversal.forward)
        assertEquals(mapOf("OWNS_RESOURCE" to "OWNED_BY_REPO"), traversal.inverse)
    }

    @Test
    fun `a step is named by the edge along its direction and by its inverse against it`() {
        val traversal = builder.impact(ImpactDirection.DOWNSTREAM)

        assertEquals("OWNS_RESOURCE", traversal.nameOf("OWNS_RESOURCE", alongStoredDirection = true))
        assertEquals("DEPENDED_ON_BY", traversal.nameOf("DEPENDS_ON", alongStoredDirection = false))
    }

    @Test
    fun `ownership is inherited upstream along the edges flagged inherits, and owner edges are named`() {
        val inheritance = builder.ownershipInheritance()

        assertEquals(emptySet<String>(), inheritance.forward)
        assertEquals(mapOf("OWNS_RESOURCE" to "OWNED_BY_REPO"), inheritance.inverse)
        assertEquals(setOf("OWNED_BY"), builder.ownerEdges())
    }

    @Test
    fun `the shipped registry propagates along the edges the issue names`() {
        val shipped = TraversalFilterBuilder(YamlOntologyLoader(DefaultResourceLoader()).load())

        val downstream = shipped.impact(ImpactDirection.DOWNSTREAM)

        assertEquals(setOf("PROVIDES", "HAS_PIPELINE", "DEPLOYED_TO", "TO_ENVIRONMENT", "OWNS_RESOURCE"), downstream.forward)
        // A change reaches the artifacts that contain it (#85): CONTAINS read against its direction.
        assertEquals(
            mapOf("DEPENDS_ON" to "DEPENDED_ON_BY", "BUILT_FROM" to "BUILDS", "CONTAINS" to "CONTAINED_IN"),
            downstream.inverse,
        )
    }

    @Test
    fun `the lineage of a deployment is found by the types each edge connects, not by its name (#85)`() {
        val lineage =
            OntologyRegistry(
                version = "1.0.0",
                nodeTypes = listOf("Artifact", "Deployment", "Change", "ExternalWorkItem", "Environment").map(::node),
                edgeTypes =
                    listOf(
                        edge("SHIPPED_AS", "Artifact", "Deployment", "SHIPMENT_OF", EdgeImpact.PROPAGATES),
                        edge("BUNDLES", "Artifact", "Change", "BUNDLED_IN"),
                        edge("DELIVERS", "Change", "ExternalWorkItem", "DELIVERED_BY"),
                        // The other way round: a work item that mentions a change does not say the change implements it.
                        edge("MENTIONS", "ExternalWorkItem", "Change", "MENTIONED_BY"),
                        edge("RUNS_IN", "Deployment", "Environment", "RUNS", EdgeImpact.PROPAGATES),
                    ),
            )

        assertEquals(
            LineageTraversal(
                deployedAs = setOf("SHIPPED_AS"),
                contains = setOf("BUNDLES"),
                implements = setOf("DELIVERS"),
                placement = setOf("RUNS_IN"),
            ),
            TraversalFilterBuilder(lineage).lineage(),
        )
    }

    @Test
    fun `the shipped registry's lineage is DEPLOYED_TO, CONTAINS and IMPLEMENTS, placed by TO_ENVIRONMENT (#85)`() {
        val shipped = TraversalFilterBuilder(YamlOntologyLoader(DefaultResourceLoader()).load())

        assertEquals(
            LineageTraversal(setOf("DEPLOYED_TO"), setOf("CONTAINS"), setOf("IMPLEMENTS"), setOf("TO_ENVIRONMENT")),
            shipped.lineage(),
        )
    }

    @Test
    fun `a registry without the lineage types has an empty lineage rather than an error (#85)`() {
        assertEquals(LineageTraversal(emptySet(), emptySet(), emptySet(), emptySet()), builder.lineage())
    }

    @Test
    fun `a node is placed in an environment by the propagating edges that end at one (#87)`() {
        val shipped = TraversalFilterBuilder(YamlOntologyLoader(DefaultResourceLoader()).load())

        // Not PRODUCED, which also ends at an Environment: a sync run records writing it, it does not run there.
        assertEquals(setOf("TO_ENVIRONMENT"), shipped.placement())
        assertEquals(emptySet<String>(), builder.placement())
    }

    @Test
    fun `the shipped registry inherits ownership back to a repository through resources, services, pipelines and builds`() {
        val shipped = TraversalFilterBuilder(YamlOntologyLoader(DefaultResourceLoader()).load())

        val inheritance = shipped.ownershipInheritance()

        assertEquals(setOf("BUILT_FROM"), inheritance.forward)
        assertEquals(
            mapOf(
                "OWNS_RESOURCE" to "OWNED_BY_REPO",
                "HAS_PIPELINE" to "PIPELINE_OF",
                "DEPLOYED_TO" to "DEPLOYMENT_OF",
                "PROVIDES" to "PROVIDED_BY",
            ),
            inheritance.inverse,
        )
        assertEquals(setOf("OWNED_BY"), shipped.ownerEdges())
    }
}
