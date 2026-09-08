package com.repodatagraph.adapter.out.ontology

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.InvalidOntologyException
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component

/**
 * Reads the ontology from YAML on the classpath. The path is configurable so tests can load a
 * deliberately broken registry and assert that startup fails.
 */
@Component
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
        )
    }

    private fun readNodeTypes(path: String): List<NodeTypeDef> {
        val nodes = read(path).path("nodes")
        if (nodes.isMissingNode || !nodes.isObject) {
            throw InvalidOntologyException("$path declares no 'nodes' mapping")
        }
        return nodes.properties().map { (name, definition) ->
            NodeTypeDef(
                name = name,
                description = definition.path("description").asTextOrNull(),
                identity = definition.path("identity").map { it.asText() },
                properties = readProperties(definition, "node type '$name'"),
            )
        }
    }

    private fun readEdgeTypes(path: String): List<EdgeTypeDef> {
        val edges = read(path).path("edges")
        if (edges.isMissingNode || !edges.isObject) {
            throw InvalidOntologyException("$path declares no 'edges' mapping")
        }
        return edges.properties().map { (name, definition) ->
            EdgeTypeDef(
                name = name,
                description = definition.path("description").asTextOrNull(),
                from = definition.path("from").map { it.asText() },
                to = definition.path("to").map { it.asText() },
                inverse = definition.path("inverse").asTextOrNull().orEmpty(),
                properties = readProperties(definition, "edge type '$name'"),
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
            PropertyDef(
                name = name,
                type = PropertyType.fromWireName(property.path("type").asTextOrNull() ?: "string"),
                required = property.path("required").asBoolean(false),
                description = property.path("description").asTextOrNull(),
                enum = property.path("enum").takeIf { it.isArray }?.map { it.asText() },
            )
        }

    private fun read(path: String): JsonNode {
        val resource = resourceLoader.getResource("classpath:$path")
        if (!resource.exists()) throw InvalidOntologyException("ontology file '$path' not found on the classpath")
        return resource.inputStream.use { yaml.readTree(it) }
    }

    private fun JsonNode.asTextOrNull(): String? = if (isMissingNode || isNull) null else asText().takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_BASE_PATH = "ontology/v1"
    }
}
