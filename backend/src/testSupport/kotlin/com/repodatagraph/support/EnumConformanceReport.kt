package com.repodatagraph.support

import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import org.springframework.data.neo4j.core.Neo4jClient

/** One value stored under a property whose enum does not allow it, and how many facts hold it. */
data class NonConformingValue(
    /** The node or edge type, e.g. `Deployment` or `OWNS_RESOURCE`. */
    val owner: String,
    val property: String,
    val value: String,
    val count: Long,
) {
    val path: String get() = "$owner.$property"
}

/**
 * Which values already in the graph fall outside the enums the registry declares (#81).
 *
 * An enum is enforced on write only: data written before it was declared, or by a writer that does
 * not go through the validator, is left as it is and still reads. This lists it, so a migration (#33)
 * can be written from facts rather than guesses. It reports; it never rewrites.
 *
 * Labels, relationship types and property names come from the registry, never from input, which is
 * why interpolating them into the Cypher is safe; the allowed values travel as a parameter.
 */
class EnumConformanceReport(
    private val neo4jClient: Neo4jClient,
    private val registry: OntologyRegistry,
) {
    fun run(): List<NonConformingValue> {
        val nodes =
            registry.allNodeTypes().flatMap { type ->
                type.properties.constrained().flatMap { property ->
                    count("MATCH (f:`${type.name}`)", type.name, property)
                }
            }
        val edges =
            registry.allEdgeTypes().flatMap { type ->
                type.properties.constrained().flatMap { property ->
                    count("MATCH ()-[f:`${type.name}`]->()", type.name, property)
                }
            }
        return nodes + edges
    }

    /** One line per value, grouped by the property it is stored under. */
    fun render(rows: List<NonConformingValue>): String =
        if (rows.isEmpty()) {
            "Every stored value conforms to its enum."
        } else {
            rows.joinToString(separator = "\n", prefix = "Values outside their enum (${rows.size}):\n") {
                "  ${it.path}: \"${it.value}\" x ${it.count}"
            }
        }

    private fun List<PropertyDef>.constrained(): List<PropertyDef> = filter { !it.enum.isNullOrEmpty() }

    private fun count(
        match: String,
        owner: String,
        property: PropertyDef,
    ): List<NonConformingValue> =
        neo4jClient
            .query(
                "$match WHERE f.`${property.name}` IS NOT NULL AND NOT toString(f.`${property.name}`) IN \$allowed " +
                    "RETURN toString(f.`${property.name}`) AS value, count(*) AS count ORDER BY value",
            ).bind(property.enum.orEmpty())
            .to("allowed")
            .fetch()
            .all()
            .map { row -> NonConformingValue(owner, property.name, row["value"].toString(), (row["count"] as Number).toLong()) }
}
