package com.repodatagraph.application.ingest

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.application.PropertyValidator
import com.repodatagraph.domain.exception.InvalidGitRemoteException
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolutionException
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.springframework.stereotype.Component

/** A seed batch read: the facts it asserts, or everything wrong with it. */
sealed interface ParsedSeed {
    data class Valid(
        val delta: GraphDelta,
    ) : ParsedSeed

    data class Invalid(
        val errors: Map<String, String>,
    ) : ParsedSeed
}

/**
 * Reads a seed batch (#47, FR10): `{"nodes": [{type, props, sourceId?}], "edges": [{type, from, to, props?}]}`,
 * where an edge names its ends by their position in `nodes`.
 *
 * The seed writes to an instance nobody else may write to, so what it may say is narrow: only the
 * types [SEEDABLE_NODES] and [SEEDABLE_EDGES] name, every property checked against the ontology exactly
 * as the node and edge APIs check it, and each edge only between the types the ontology lets it join.
 * Every problem is collected, so a seed script is fixed in one round.
 */
@Component
class SeedBatchParser(
    private val objectMapper: ObjectMapper,
    private val registry: OntologyRegistry,
    private val validator: PropertyValidator,
    private val identityResolver: IdentityResolver,
    private val derivedProperties: DerivedProperties,
) {
    fun parse(body: ByteArray): ParsedSeed =
        readObject(body)?.let { Batch(it).read() } ?: ParsedSeed.Invalid(mapOf("body" to "body must be a JSON object"))

    private fun readObject(body: ByteArray): JsonNode? =
        try {
            objectMapper.readTree(body)?.takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        }

    /** One batch being read, with what is wrong with it gathered in [errors]. */
    private inner class Batch(
        private val root: JsonNode,
    ) {
        val errors = linkedMapOf<String, String>()

        fun read(): ParsedSeed {
            val nodes = list("nodes", MAX_NODES, allowEmpty = false).mapIndexed(::readNode)
            val edges = list("edges", MAX_EDGES, allowEmpty = true).mapIndexedNotNull { index, edge -> readEdge(index, edge, nodes) }
            return if (errors.isEmpty()) {
                ParsedSeed.Valid(GraphDelta(nodes = nodes.map { checkNotNull(it).upsert }, edges = edges))
            } else {
                ParsedSeed.Invalid(errors)
            }
        }

        private fun list(
            field: String,
            max: Int,
            allowEmpty: Boolean,
        ): List<JsonNode> {
            val node = root.path(field)
            return when {
                node.isMissingNode && allowEmpty -> emptyList()
                !node.isArray || (node.isEmpty && !allowEmpty) -> fail(field, "$field must not be empty") ?: emptyList()
                node.size() > max -> fail(field, "$field must not number more than $max") ?: emptyList()
                else -> node.toList()
            }
        }

        private fun readNode(
            index: Int,
            node: JsonNode,
        ): ReadNode? {
            val path = "nodes[$index]"
            val type = node.path("type").asText("")
            val nodeType = registry.nodeType(type)?.takeIf { type in SEEDABLE_NODES }
            val props = propsOf(node)
            val problems = nodeType?.let { validator.validate(it, props) }.orEmpty()
            problems.forEach { errors["$path.props.${it.field}"] = it.message }
            return when {
                nodeType == null -> fail("$path.type", "${type.ifBlank { "a node with no type" }} cannot be seeded")
                problems.isNotEmpty() -> null
                else -> keyed(path, type, props, node.path("sourceId").takeIf { it.isTextual }?.asText())
            }
        }

        private fun keyed(
            path: String,
            type: String,
            props: Map<String, Any?>,
            sourceId: String?,
        ): ReadNode? =
            try {
                val key = identityResolver.keyFor(type, derivedProperties.expand(type, props))
                ReadNode(key, NodeUpsert(type = type, props = props, sourceId = sourceId))
            } catch (invalid: InvalidGitRemoteException) {
                fail("$path.props.url", "url is not a git remote: ${invalid.message}")
            } catch (unresolvable: IdentityResolutionException) {
                fail(path, unresolvable.message ?: "the node's identity cannot be resolved")
            }

        private fun readEdge(
            index: Int,
            edge: JsonNode,
            nodes: List<ReadNode?>,
        ): EdgeUpsert? {
            val path = "edges[$index]"
            val type = edge.path("type").asText("")
            // Both ends are read whatever else is wrong, so each problem is reported once, now.
            val from = end(edge, "from", nodes, path)
            val to = end(edge, "to", nodes, path)
            val edgeType = registry.edgeType(type)?.takeIf { type in SEEDABLE_EDGES }
            val props = propsOf(edge)
            edgeType?.let { validator.validate(it.properties, props) }?.forEach { errors["$path.props.${it.field}"] = it.message }
            return when {
                edgeType == null -> fail("$path.type", "${type.ifBlank { "an edge with no type" }} cannot be seeded")
                // An end that failed to read has already been reported; there is nothing more to say about it.
                from == null || to == null -> null
                !edgeType.connects(from.key.type, to.key.type) -> fail(path, "$type cannot go from ${from.key.type} to ${to.key.type}")
                else -> EdgeUpsert(type = type, from = from.key, to = to.key, props = props)
            }
        }

        private fun end(
            edge: JsonNode,
            field: String,
            nodes: List<ReadNode?>,
            path: String,
        ): ReadNode? {
            val value = edge.path(field)
            return if (value.isInt && value.asInt() in nodes.indices) {
                nodes[value.asInt()]
            } else {
                fail("$path.$field", "$path.$field must be the index of a node in this batch")
            }
        }

        private fun <T> fail(
            path: String,
            message: String,
        ): T? {
            errors[path] = message
            return null
        }
    }

    private fun propsOf(node: JsonNode): Map<String, Any?> {
        val props = node.path("props")
        return if (props.isObject) objectMapper.convertValue(props, PROPS) else emptyMap()
    }

    private class ReadNode(
        val key: NodeKey,
        val upsert: NodeUpsert,
    )

    companion object {
        /** What the seed exists to write: the repository, who owns it, what builds it, and what it uses. */
        val SEEDABLE_NODES = setOf("Repository", "Team", "Pipeline")
        val SEEDABLE_EDGES = setOf("OWNED_BY", "HAS_PIPELINE", "DEPENDS_ON")

        /** A seed describes one repository; a batch bigger than this is something else. */
        const val MAX_NODES = 500
        const val MAX_EDGES = 2000

        private val PROPS = object : TypeReference<Map<String, Any?>>() {}
    }
}
