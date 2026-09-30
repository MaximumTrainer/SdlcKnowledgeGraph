package com.repodatagraph.domain.ontology

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.DefaultResourceLoader

class OntologyRegistryTest {
    private fun nodeType(
        name: String,
        identity: List<String> = listOf("name"),
        properties: List<PropertyDef> = listOf(PropertyDef("name", PropertyType.STRING, required = true)),
    ) = NodeTypeDef(name = name, description = null, identity = identity, properties = properties)

    private fun edgeType(
        name: String,
        from: List<String> = listOf("Repository"),
        to: List<String> = listOf("Team"),
        inverse: String = "INVERSE_OF_$name",
    ) = EdgeTypeDef(name = name, description = null, from = from, to = to, inverse = inverse, properties = emptyList())

    @Test
    fun `types are looked up by name`() {
        val registry =
            OntologyRegistry(
                version = "1.0.0",
                nodeTypes = listOf(nodeType("Repository"), nodeType("Team")),
                edgeTypes = listOf(edgeType("OWNED_BY")),
            )

        assertEquals("Repository", registry.nodeType("Repository")?.name)
        assertEquals("OWNED_BY", registry.edgeType("OWNED_BY")?.name)
        assertEquals(2, registry.allNodeTypes().size)
        assertEquals(1, registry.allEdgeTypes().size)
    }

    @Test
    fun `an unknown name is absent rather than an error`() {
        val registry = OntologyRegistry("1.0.0", listOf(nodeType("Repository")), emptyList())

        assertNull(registry.nodeType("Nonsense"))
        assertNull(registry.edgeType("NONSENSE"))
    }

    @Test
    fun `lookup is case sensitive because labels are`() {
        val registry = OntologyRegistry("1.0.0", listOf(nodeType("Repository")), emptyList())

        assertNull(registry.nodeType("repository"))
    }

    @Test
    fun `an edge without an inverse is rejected, because traversal depends on it`() {
        val error =
            assertThrows<InvalidOntologyException> {
                OntologyRegistry(
                    version = "1.0.0",
                    nodeTypes = listOf(nodeType("Repository"), nodeType("Team")),
                    edgeTypes = listOf(edgeType("OWNED_BY", inverse = " ")),
                )
            }

        assertEquals(true, error.message!!.contains("OWNED_BY"))
    }

    @Test
    fun `an edge pointing at an undeclared node type is rejected`() {
        val error =
            assertThrows<InvalidOntologyException> {
                OntologyRegistry(
                    version = "1.0.0",
                    nodeTypes = listOf(nodeType("Repository")),
                    edgeTypes = listOf(edgeType("OWNED_BY", to = listOf("Team"))),
                )
            }

        assertEquals(true, error.message!!.contains("Team"))
    }

    @Test
    fun `a duplicate node type is rejected rather than silently shadowed`() {
        assertThrows<InvalidOntologyException> {
            OntologyRegistry("1.0.0", listOf(nodeType("Repository"), nodeType("Repository")), emptyList())
        }
    }

    @Test
    fun `identity must reference properties the type actually declares`() {
        val error =
            assertThrows<InvalidOntologyException> {
                OntologyRegistry(
                    version = "1.0.0",
                    nodeTypes = listOf(nodeType("Repository", identity = listOf("missingProp"))),
                    edgeTypes = emptyList(),
                )
            }

        assertEquals(true, error.message!!.contains("missingProp"))
    }

    @Test
    fun `a node type with no identity is rejected, because nodes must be addressable`() {
        assertThrows<InvalidOntologyException> {
            OntologyRegistry("1.0.0", listOf(nodeType("Repository", identity = emptyList())), emptyList())
        }
    }

    @Test
    fun `a display property must be one the type declares, so every label can be read (#9)`() {
        val labelled =
            NodeTypeDef(
                name = "Team",
                description = null,
                identity = listOf("name"),
                properties = listOf(PropertyDef("name", PropertyType.STRING, required = true)),
                displayProperty = "title",
            )

        val error = assertThrows<InvalidOntologyException> { OntologyRegistry("1.0.0", listOf(labelled), emptyList()) }

        assertEquals("node type 'Team' displays 'title', which it does not declare", error.message)
    }

    @Test
    fun `a display property the type declares is kept, and none is allowed (#9)`() {
        val registry =
            OntologyRegistry(
                "1.0.0",
                listOf(nodeType("Team").copy(displayProperty = "name"), nodeType("Repository")),
                emptyList(),
            )

        assertEquals("name", registry.nodeType("Team")?.displayProperty)
        assertNull(registry.nodeType("Repository")?.displayProperty)
    }

    @Test
    fun `the version must be semver so it can be compared`() {
        assertThrows<InvalidOntologyException> {
            OntologyRegistry("one", listOf(nodeType("Repository")), emptyList())
        }
    }

    private val shipped by lazy { YamlOntologyLoader(DefaultResourceLoader()).load() }

    @Test
    fun `the shipped registry is a minor version on from 1_1_0, since it only adds (#85, #88, #33)`() {
        assertEquals("1.6.0", shipped.version)
    }

    @Test
    fun `a change is keyed in its repository by its sha, and says when it was committed (#85)`() {
        val change = shipped.nodeType("Change") ?: error("no Change")

        assertEquals(listOf("repositoryKey", "sha"), change.identity)
        assertRequired(change, "repositoryKey" to PropertyType.STRING, "sha" to PropertyType.STRING, "committedAt" to PropertyType.INSTANT)
        assertOptional(change, "baseSha", "title", "author", "url")
        assertEquals("sha", change.displayProperty)
        assertFalse(change.meta)
    }

    @Test
    fun `a pull request is keyed in its repository by its number, with a state from a fixed set (#85)`() {
        val pullRequest = shipped.nodeType("PullRequest") ?: error("no PullRequest")

        assertEquals(listOf("repositoryKey", "number"), pullRequest.identity)
        assertRequired(pullRequest, "repositoryKey" to PropertyType.STRING, "number" to PropertyType.INT, "url" to PropertyType.STRING)
        assertOptional(pullRequest, "title", "state", "mergedAt", "branch")
        assertEquals(listOf("open", "merged", "closed"), pullRequest.property("state")?.enum)
        assertEquals(PropertyType.INSTANT, pullRequest.property("mergedAt")?.type)
        assertEquals("number", pullRequest.displayProperty)
    }

    @Test
    fun `an external work item is keyed by its uri and names the system that owns it (#85)`() {
        val workItem = shipped.nodeType("ExternalWorkItem") ?: error("no ExternalWorkItem")

        assertEquals(listOf("uri"), workItem.identity)
        assertRequired(workItem, "uri" to PropertyType.STRING, "system" to PropertyType.STRING)
        assertOptional(workItem, "externalKey", "title")
        assertEquals(listOf("chorus", "jira", "linear", "github", "other"), workItem.property("system")?.enum)
        assertEquals("externalKey", workItem.displayProperty)
    }

    @Test
    fun `the lineage edges connect what the issue names, each with its inverse (#85)`() {
        val expected =
            mapOf(
                "INTRODUCED_IN" to Triple("Change", "Repository", "HAS_CHANGE"),
                "MERGES" to Triple("PullRequest", "Change", "MERGED_BY"),
                "CONTAINS" to Triple("Artifact", "Change", "CONTAINED_IN"),
                "IMPLEMENTS" to Triple("Change", "ExternalWorkItem", "IMPLEMENTED_BY"),
                "TRACKED_IN" to Triple("ExternalWorkItem", "Team", "TRACKS"),
            )

        expected.forEach { (name, ends) ->
            val edge = shipped.edgeType(name) ?: error("no $name")
            assertEquals(listOf(ends.first), edge.from, "$name from")
            assertEquals(listOf(ends.second), edge.to, "$name to")
            assertEquals(ends.third, edge.inverse, "$name inverse")
        }
    }

    @Test
    fun `a change travels to the artifacts that contain it, and no other lineage edge carries it (#85)`() {
        val contains = shipped.edgeType("CONTAINS") ?: error("no CONTAINS")

        assertEquals(EdgeImpact.PROPAGATES, contains.impact)
        assertEquals(ImpactAlong.INVERSE, contains.downstream)
        assertEquals(EdgeOwnership.NONE, contains.ownership)
        listOf("INTRODUCED_IN", "MERGES", "IMPLEMENTS", "TRACKED_IN").forEach {
            assertEquals(EdgeImpact.NONE, shipped.edgeType(it)?.impact, it)
        }
    }

    @Test
    fun `a sync run can record writing a change, a pull request or a work item (#85)`() {
        val produced = shipped.edgeType("PRODUCED") ?: error("no PRODUCED")

        assertEquals(true, produced.to.containsAll(listOf("Change", "PullRequest", "ExternalWorkItem")))
    }

    private fun assertRequired(
        type: NodeTypeDef,
        vararg properties: Pair<String, PropertyType>,
    ) = properties.forEach { (name, propertyType) ->
        val property = type.property(name) ?: error("${type.name} declares no $name")
        assertEquals(true, property.required, "${type.name}.$name required")
        assertEquals(propertyType, property.type, "${type.name}.$name type")
    }

    private fun assertOptional(
        type: NodeTypeDef,
        vararg names: String,
    ) = names.forEach { name ->
        assertEquals(false, type.property(name)?.required ?: error("${type.name} declares no $name"), "${type.name}.$name optional")
    }

    /**
     * An alias is a second way to find a node, unique where present (#88): the Repository's provider
     * id. It has to be declared, it cannot be part of the key it stands beside, and it cannot be
     * required, since a node written before its alias was known has to stay writable.
     */
    @Test
    fun `an alias must name optional properties the type declares, outside its identity (#88)`() {
        val properties =
            listOf(
                PropertyDef("name", PropertyType.STRING, required = true),
                PropertyDef("providerId", PropertyType.STRING, required = true),
            )
        val aliased = nodeType("Repository", properties = properties).copy(alias = listOf("name", "providerId", "provider"))

        val error = assertThrows<InvalidOntologyException> { OntologyRegistry("1.0.0", listOf(aliased), emptyList()) }

        assertEquals(
            "node type 'Repository' alias property 'name' is part of its identity; " +
                "node type 'Repository' alias property 'providerId' is required; " +
                "node type 'Repository' alias references 'provider', which it does not declare",
            error.message,
        )
    }

    @Test
    fun `the shipped registry aliases a repository by its provider and provider id (#88)`() {
        val registry = YamlOntologyLoader(DefaultResourceLoader()).load()

        assertEquals(listOf("provider", "providerId"), registry.nodeType("Repository")?.alias)
        assertEquals(listOf("github", "gitlab", "other"), registry.nodeType("Repository")?.property("provider")?.enum)
        assertEquals(emptyList<String>(), registry.nodeType("Team")?.alias)
        assertEquals(PropertyType.STRING_ARRAY, registry.provenance.firstOrNull { it.name == "previousKeys" }?.type)
    }
}
