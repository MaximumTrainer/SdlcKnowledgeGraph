package com.repodatagraph.domain.ontology

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.DefaultResourceLoader

/**
 * Traversal templates (#96, FR-1): declared in templates.yaml and validated against the edges when
 * the registry is built, so a template that names an edge that does not exist, walks one from a type
 * it cannot start from or filters on a value its enum does not allow stops the application at startup
 * rather than answering an empty pack.
 */
class OntologyRegistryTemplatesTest {
    private val shipped = YamlOntologyLoader(DefaultResourceLoader()).load()

    private fun nodeType(
        name: String,
        vararg extra: PropertyDef,
    ) = NodeTypeDef(
        name = name,
        description = null,
        identity = listOf("name"),
        properties = listOf(PropertyDef("name", PropertyType.STRING, required = true)) + extra,
    )

    private val nodes =
        listOf(
            nodeType("Repository"),
            nodeType("Team"),
            nodeType("Artifact"),
            nodeType("Deployment"),
            nodeType("Environment"),
        )

    private val edges =
        listOf(
            EdgeTypeDef(
                name = "DEPENDS_ON",
                description = null,
                from = listOf("Repository"),
                to = listOf("Repository"),
                inverse = "DEPENDED_ON_BY",
                properties =
                    listOf(
                        PropertyDef("kind", PropertyType.STRING, required = true, enum = listOf("library", "api", "data")),
                        PropertyDef("manifest", PropertyType.STRING),
                    ),
            ),
            EdgeTypeDef("OWNED_BY", null, listOf("Repository"), listOf("Team"), "OWNS", ownership = EdgeOwnership.OWNER),
            EdgeTypeDef("BUILT_FROM", null, listOf("Artifact"), listOf("Repository"), "BUILDS"),
            EdgeTypeDef("DEPLOYED_TO", null, listOf("Artifact"), listOf("Deployment"), "DEPLOYMENT_OF"),
            EdgeTypeDef("TO_ENVIRONMENT", null, listOf("Deployment"), listOf("Environment"), "HOSTS"),
        )

    private fun registry(vararg templates: TemplateDef) = OntologyRegistry("1.0.0", nodes, edges, templates = templates.toList())

    private fun template(
        vararg steps: TemplateStepDef,
        name: String = "change-impact",
        start: List<String> = listOf("Repository"),
        description: String? = "What a change reaches",
    ) = TemplateDef(name = name, description = description, start = start, owners = true, steps = steps.toList())

    private fun refusal(vararg templates: TemplateDef): String = assertThrows<InvalidOntologyException> { registry(*templates) }.message!!

    @Test
    fun `a template walks edges by their own name or by their inverse, and is looked up by name`() {
        val changeImpact =
            template(
                TemplateStepDef(
                    edge = "DEPENDED_ON_BY",
                    min = 0,
                    max = 4,
                    then =
                        listOf(
                            TemplateStepDef(
                                edge = "BUILDS",
                                then =
                                    listOf(
                                        TemplateStepDef(
                                            edge = "DEPLOYED_TO",
                                            current = true,
                                            then = listOf(TemplateStepDef("TO_ENVIRONMENT")),
                                        ),
                                    ),
                            ),
                        ),
                ),
            )

        val registry = registry(changeImpact)

        assertEquals(changeImpact, registry.template("change-impact"))
        assertNull(registry.template("everything"))
        assertEquals(listOf("change-impact"), registry.templates.map { it.name })
        val inverse = registry.walkable("DEPENDED_ON_BY")!!
        assertEquals("DEPENDS_ON", inverse.type.name)
        assertFalse(inverse.alongStoredDirection)
        assertEquals(listOf("Repository"), inverse.sources)
        val forward = registry.walkable("DEPLOYED_TO")!!
        assertTrue(forward.alongStoredDirection)
        assertEquals(listOf("Deployment"), forward.targets)
        assertNull(registry.walkable("NOT_AN_EDGE"))
    }

    @Test
    fun `an edge that is neither an edge type nor an inverse stops startup, naming it`() {
        val message = refusal(template(TemplateStepDef("DEPENDS_ON_EVERYTHING")))

        assertTrue(message.contains("change-impact") && message.contains("DEPENDS_ON_EVERYTHING"), message)
    }

    @Test
    fun `an edge that cannot be walked from where the template stands stops startup`() {
        // TO_ENVIRONMENT leaves a Deployment; a Repository has none to walk.
        val message = refusal(template(TemplateStepDef("TO_ENVIRONMENT")))

        assertTrue(message.contains("TO_ENVIRONMENT") && message.contains("Repository"), message)
    }

    @Test
    fun `what a step can walk from is what the steps before it reached`() {
        val chained =
            template(
                TemplateStepDef(
                    "BUILDS",
                    then = listOf(TemplateStepDef("DEPLOYED_TO", then = listOf(TemplateStepDef("TO_ENVIRONMENT")))),
                ),
            )
        registry(chained)

        val broken = template(TemplateStepDef("BUILDS", then = listOf(TemplateStepDef("TO_ENVIRONMENT"))))
        val message = refusal(broken)
        assertTrue(message.contains("TO_ENVIRONMENT") && message.contains("Artifact"), message)
    }

    @Test
    fun `a filter on a property the edge does not declare stops startup`() {
        val message = refusal(template(TemplateStepDef("DEPENDED_ON_BY", where = mapOf("flavour" to "api"))))

        assertTrue(message.contains("flavour") && message.contains("DEPENDS_ON"), message)
    }

    @Test
    fun `a filter on a value the property's enum does not allow stops startup`() {
        val message = refusal(template(TemplateStepDef("DEPENDED_ON_BY", where = mapOf("kind" to "events"))))

        assertTrue(message.contains("events") && message.contains("library"), message)
        registry(template(TemplateStepDef("DEPENDED_ON_BY", where = mapOf("kind" to "data"))))
    }

    @Test
    fun `current may only be asked of a step that reaches deployments alone`() {
        val message = refusal(template(TemplateStepDef("DEPENDED_ON_BY", current = true)))

        assertTrue(message.contains("current") && message.contains("Deployment"), message)
    }

    @Test
    fun `a repeat is bounded and ordered`() {
        listOf(0 to 0, 2 to 1, 0 to 6, -1 to 2).forEach { (min, max) ->
            val message = refusal(template(TemplateStepDef("DEPENDED_ON_BY", min = min, max = max)))
            assertTrue(message.contains("DEPENDED_ON_BY") && message.contains("repeat"), message)
        }
        registry(template(TemplateStepDef("DEPENDED_ON_BY", min = 0, max = TemplateStepDef.MAX_REPEAT)))
    }

    @Test
    fun `a template starts from declared types, says what it is for and is named in kebab case`() {
        assertTrue(refusal(template(TemplateStepDef("DEPENDED_ON_BY"), start = listOf("Galaxy"))).contains("Galaxy"))
        assertTrue(refusal(template(TemplateStepDef("DEPENDED_ON_BY"), start = emptyList())).contains("start"))
        assertTrue(refusal(template(TemplateStepDef("DEPENDED_ON_BY"), description = null)).contains("description"))
        assertTrue(refusal(template(TemplateStepDef("DEPENDED_ON_BY"), name = "Change_Impact")).contains("Change_Impact"))
        assertTrue(refusal(template(name = "empty")).contains("steps"))
    }

    @Test
    fun `two templates of one name stop startup`() {
        val one = template(TemplateStepDef("DEPENDED_ON_BY"))

        assertTrue(refusal(one, one).contains("change-impact"))
    }

    @Test
    fun `every problem is reported at once`() {
        val message = refusal(template(TemplateStepDef("NOPE"), TemplateStepDef("DEPENDED_ON_BY", where = mapOf("kind" to "events"))))

        assertTrue(message.contains("NOPE") && message.contains("events"), message)
    }

    @Test
    fun `the shipped registry declares the three templates of #96, in order`() {
        assertEquals(listOf("change-impact", "incident-triage", "data-consumers"), shipped.templates.map { it.name })
        assertEquals(listOf("Repository", "Service"), shipped.template("change-impact")!!.start)
        assertEquals(listOf("CloudResource"), shipped.template("incident-triage")!!.start)
        assertEquals(listOf("CloudResource"), shipped.template("data-consumers")!!.start)
        assertTrue(shipped.template("change-impact")!!.owners)
        assertTrue(shipped.template("data-consumers")!!.owners)
    }

    @Test
    fun `the shipped change-impact walks dependants, then what they build to where it currently runs`() {
        val dependants = shipped.template("change-impact")!!.steps.single()
        assertEquals("DEPENDED_ON_BY", dependants.edge)
        assertEquals(0, dependants.min)
        val builds = dependants.then.single()
        assertEquals("BUILDS", builds.edge)
        val deployed = builds.then.single()
        assertEquals("DEPLOYED_TO", deployed.edge)
        assertTrue(deployed.current)
        assertEquals("TO_ENVIRONMENT", deployed.then.single().edge)
    }

    @Test
    fun `the shipped data-consumers reads the data dependants of a cloud resource`() {
        val step = shipped.template("data-consumers")!!.steps.single()

        assertEquals("DEPENDED_ON_BY", step.edge)
        assertEquals(mapOf("kind" to "data"), step.where)
        assertTrue("CloudResource" in shipped.edgeType("DEPENDS_ON")!!.to)
    }

    @Test
    fun `the shipped incident-triage follows only api dependencies upstream`() {
        val owner = shipped.template("incident-triage")!!.steps.single()
        assertEquals("OWNED_BY_REPO", owner.edge)
        val upstream = owner.then.single { it.edge == "DEPENDS_ON" }
        assertEquals(mapOf("kind" to "api"), upstream.where)
        assertEquals(
            setOf("HAS_PIPELINE", "RELATES_TO_CI", "BUILDS", "DEPENDS_ON"),
            owner.then.map { it.edge }.toSet(),
        )
    }
}
