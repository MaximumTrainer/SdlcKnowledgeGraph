package com.repodatagraph.adapter.out.ontology

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.Deprecation
import com.repodatagraph.domain.ontology.EdgeImpact
import com.repodatagraph.domain.ontology.EdgeOwnership
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.EnvironmentDef
import com.repodatagraph.domain.ontology.ImpactAlong
import com.repodatagraph.domain.ontology.InvalidOntologyException
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyFormat
import com.repodatagraph.domain.ontology.PropertyType
import com.repodatagraph.domain.ontology.SourceSystemDef
import com.repodatagraph.domain.ontology.TemplateDef
import com.repodatagraph.domain.ontology.TemplateStepDef
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

/**
 * Reads the ontology from YAML on the classpath. The path is configurable so tests can load a
 * deliberately broken registry and assert that startup fails.
 */
@Component
@Suppress("TooManyFunctions") // One reader per registry file and per shape within one.
class YamlOntologyLoader(
    private val resourceLoader: ResourceLoader,
) {
    private val yaml = YAMLMapper()

    fun load(basePath: String = DEFAULT_BASE_PATH): OntologyRegistry {
        val version =
            read("$basePath/version.yaml").path("version").asTextOrNull()
                ?: throw InvalidOntologyException("$basePath/version.yaml declares no version")

        return OntologyRegistry(
            version = version,
            nodeTypes = readNodeTypes("$basePath/nodes.yaml"),
            edgeTypes = readEdgeTypes("$basePath/edges.yaml"),
            provenance = readProvenance("$basePath/provenance.yaml"),
            sources = readSources("$basePath/sources.yaml"),
            environments = readEnvironments("$basePath/environments.yaml"),
            templates = readTemplates("$basePath/templates.yaml"),
        )
    }

    /**
     * The traversal templates of context packs (#96, FR-1). Optional like the environment table: a
     * registry without the file has no templates, and one with it is validated against the edges as
     * the registry is built.
     */
    private fun readTemplates(path: String): List<TemplateDef> {
        if (!resourceLoader.getResource("classpath:$path").exists()) return emptyList()
        val templates = read(path).path("templates")
        if (!templates.isObject) throw InvalidOntologyException("$path declares no 'templates' mapping")
        return templates.properties().map { (name, definition) ->
            definition.requireOnly(TEMPLATE_KEYS, "$path template '$name'")
            TemplateDef(
                name = name,
                description = definition.path("description").asTextOrNull(),
                start = definition.path("start").map { it.asText() },
                owners = definition.path("owners").asBoolean(false),
                steps = readSteps(definition.path("steps"), "$path template '$name'"),
            )
        }
    }

    private fun readSteps(
        steps: JsonNode,
        owner: String,
    ): List<TemplateStepDef> =
        steps.map { step ->
            step.requireOnly(STEP_KEYS, "$owner step")
            val edge = step.path("edge").asTextOrNull() ?: throw InvalidOntologyException("$owner declares a step with no edge")
            TemplateStepDef(
                edge = edge,
                where = step.path("where").properties().associate { (key, value) -> key to value.asText() },
                min = step.path("min").asInt(1),
                max = step.path("max").asInt(1),
                current = step.path("current").asBoolean(false),
                then = readSteps(step.path("then"), "$owner step '$edge'"),
            )
        }

    /**
     * The environment alias table (#98, FR-3). Optional like the source list: a registry without the
     * file folds no environment names, and one with it is validated by the registry as it is built.
     */
    private fun readEnvironments(path: String): List<EnvironmentDef> {
        if (!resourceLoader.getResource("classpath:$path").exists()) return emptyList()
        val environments = read(path).path("environments")
        if (!environments.isArray) throw InvalidOntologyException("$path declares no 'environments' list")
        return environments.map { environment ->
            val name =
                environment.path("name").asTextOrNull() ?: throw InvalidOntologyException("$path declares an environment with no name")
            environment.requireOnly(ENVIRONMENT_KEYS, "$path environment '$name'")
            EnvironmentDef(
                name = name,
                description = environment.path("description").asTextOrNull(),
                aliases = environment.path("aliases").map { it.asText() },
            )
        }
    }

    /**
     * The source systems a write may name (#117). Optional like the provenance envelope: a registry
     * without the file knows `manual` alone, which the registry supplies.
     */
    private fun readSources(path: String): List<SourceSystemDef> {
        if (!resourceLoader.getResource("classpath:$path").exists()) return listOf(SourceSystemDef(Provenance.MANUAL))
        val sources = read(path).path("sources")
        if (!sources.isArray) throw InvalidOntologyException("$path declares no 'sources' list")
        return sources.map { source ->
            SourceSystemDef(
                name = source.path("name").asTextOrNull() ?: throw InvalidOntologyException("$path declares a source with no name"),
                description = source.path("description").asTextOrNull(),
            )
        }
    }

    /**
     * The provenance envelope (#114). Optional, so a registry written before it was declared - such
     * as the deliberately broken ones the tests load - still loads, with an empty envelope.
     */
    private fun readProvenance(path: String): List<PropertyDef> {
        if (!resourceLoader.getResource("classpath:$path").exists()) return emptyList()
        return readProperties(read(path).path("provenance"), "the provenance envelope")
    }

    private fun readNodeTypes(path: String): List<NodeTypeDef> {
        val nodes = read(path).path("nodes")
        if (nodes.isMissingNode || !nodes.isObject) {
            throw InvalidOntologyException("$path declares no 'nodes' mapping")
        }
        return nodes.properties().map { (name, definition) ->
            definition.requireOnly(NODE_KEYS, "node type '$name'")
            NodeTypeDef(
                name = name,
                description = definition.path("description").asTextOrNull(),
                identity = definition.path("identity").map { it.asText() },
                properties = readProperties(definition, "node type '$name'"),
                meta = definition.path("meta").asBoolean(false),
                displayProperty = definition.path("displayProperty").asTextOrNull(),
                alias = definition.path("alias").map { it.asText() },
                examples = readExamples(definition),
                questions = definition.path("questions").map { it.asText() },
                mergeScope = definition.path("mergeScope").map { it.asText() },
            )
        }
    }

    private fun readEdgeTypes(path: String): List<EdgeTypeDef> {
        val edges = read(path).path("edges")
        if (edges.isMissingNode || !edges.isObject) {
            throw InvalidOntologyException("$path declares no 'edges' mapping")
        }
        return edges.properties().map { (name, definition) ->
            definition.requireOnly(EDGE_KEYS, "edge type '$name'")
            EdgeTypeDef(
                name = name,
                description = definition.path("description").asTextOrNull(),
                from = definition.path("from").map { it.asText() },
                to = definition.path("to").map { it.asText() },
                inverse = definition.path("inverse").asTextOrNull().orEmpty(),
                properties = readProperties(definition, "edge type '$name'"),
                impact = definition.path("impact").asTextOrNull()?.let(EdgeImpact::fromWireName) ?: EdgeImpact.NONE,
                downstream = definition.path("downstream").asTextOrNull()?.let(ImpactAlong::fromWireName) ?: ImpactAlong.FORWARD,
                ownership = definition.path("ownership").asTextOrNull()?.let(EdgeOwnership::fromWireName) ?: EdgeOwnership.NONE,
                examples = readExamples(definition),
                questions = definition.path("questions").map { it.asText() },
            )
        }
    }

    private fun readProperties(
        definition: JsonNode,
        owner: String,
    ): List<PropertyDef> =
        definition.path("properties").map { property ->
            val name =
                property.path("name").asTextOrNull()
                    ?: throw InvalidOntologyException("$owner declares a property with no name")
            property.requireOnly(PROPERTY_KEYS, "$owner property '$name'")
            PropertyDef(
                name = name,
                type = PropertyType.fromWireName(property.path("type").asTextOrNull() ?: "string"),
                required = property.path("required").asBoolean(false),
                description = property.path("description").asTextOrNull(),
                enum = property.path("enum").takeIf { it.isArray }?.map { it.asText() },
                format = property.path("format").asTextOrNull()?.let(PropertyFormat::fromWireName),
                formatWhen =
                    property
                        .path("formatWhen")
                        .properties()
                        .associate { (key, value) -> key to value.asText() },
                examples = property.path("examples").map { it.plain() },
                deprecated = readDeprecation(property.path("deprecated"), "$owner property '$name'"),
            )
        }

    private fun readDeprecation(
        node: JsonNode,
        owner: String,
    ): Deprecation? {
        if (node.isMissingNode || node.isNull) return null
        node.requireOnly(DEPRECATED_KEYS, "$owner deprecation")
        return Deprecation(
            since = node.path("since").asTextOrNull() ?: throw InvalidOntologyException("$owner is deprecated since no version"),
            replacedBy = node.path("replacedBy").asTextOrNull(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readExamples(definition: JsonNode): List<Map<String, Any?>> =
        definition.path("examples").map { example ->
            example.plain() as? Map<String, Any?> ?: throw InvalidOntologyException("an example node must be a mapping: $example")
        }

    private fun read(path: String): JsonNode {
        val resource = resourceLoader.getResource("classpath:$path")
        if (!resource.exists()) throw InvalidOntologyException("ontology file '$path' not found on the classpath")
        return resource.inputStream.use { yaml.readTree(it) }
    }

    private fun JsonNode.asTextOrNull(): String? = if (isMissingNode || isNull) null else asText().takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_BASE_PATH = "ontology/v1"

        private val NODE_KEYS =
            setOf("description", "identity", "alias", "mergeScope", "displayProperty", "meta", "properties", "examples", "questions")
        private val EDGE_KEYS =
            setOf("description", "from", "to", "inverse", "impact", "downstream", "ownership", "properties", "examples", "questions")
        private val PROPERTY_KEYS =
            setOf("name", "type", "required", "description", "enum", "format", "formatWhen", "examples", "deprecated")
        private val DEPRECATED_KEYS = setOf("since", "replacedBy")
        private val ENVIRONMENT_KEYS = setOf("name", "description", "aliases")
        private val TEMPLATE_KEYS = setOf("description", "start", "owners", "steps")
        private val STEP_KEYS = setOf("edge", "where", "min", "max", "current", "then")
    }
}
