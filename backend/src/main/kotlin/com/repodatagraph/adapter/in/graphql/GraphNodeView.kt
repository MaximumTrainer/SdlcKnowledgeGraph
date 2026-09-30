package com.repodatagraph.adapter.`in`.graphql

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactScoring
import com.repodatagraph.domain.model.Provenance
import graphql.schema.TypeResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * A [GraphNode] as the generated GraphQL types read it (#20, #21): a map holding `id`, `key`, every
 * property and `provenance`, plus the name of the generated type it is, `<Type>Node`.
 *
 * A map rather than a class per type, because the types are generated from the registry: a class
 * per type would be the hand-written restatement the generator exists to prevent. Instants go out as
 * ISO-8601 text, which is how the generated schema types them.
 */
object GraphNodeView {
    const val TYPE_NAME = "__graphNodeType"

    fun of(node: GraphNode): Map<String, Any?> =
        node.props.mapValues { (_, value) -> text(value) } +
            mapOf(
                "id" to node.id,
                "key" to node.key.key,
                "provenance" to provenance(node.provenance),
                TYPE_NAME to "${node.type}Node",
            )

    /** The formula impact scores come from (#87), as `ImpactScoring` reads it: shared by changeImpact and contextPack (#96). */
    fun scoring(): Map<String, Any?> =
        mapOf(
            "version" to ImpactScoring.VERSION,
            "formula" to ImpactScoring.FORMULA,
            "tierWeights" to
                ImpactScoring.TIER_WEIGHTS.entries
                    .sortedBy { it.key.wire }
                    .map { (tier, weight) -> mapOf("tier" to tier.wire, "weight" to weight) },
            "pathMatchBoost" to ImpactScoring.PATH_MATCH_BOOST,
        )

    /** Provenance as the generated `Provenance` type reads it; also what a change-impact citation cites (#87). */
    fun provenance(provenance: Provenance): Map<String, Any?> =
        mapOf(
            "sourceSystem" to provenance.sourceSystem,
            "sourceId" to provenance.sourceId,
            "ingestedAt" to provenance.ingestedAt.toString(),
            "observedAt" to provenance.observedAt?.toString(),
            "confidence" to provenance.confidence,
            "inferred" to provenance.inferred,
            "validFrom" to provenance.validFrom.toString(),
            "validTo" to provenance.validTo?.toString(),
            "syncRunId" to provenance.syncRunId,
            "writtenBy" to provenance.writtenBy,
            "principalType" to provenance.principalType,
            "onBehalfOfTeam" to provenance.onBehalfOfTeam,
        )

    private fun text(value: Any?): Any? =
        when (value) {
            is Instant -> value.toString()
            is ZonedDateTime -> value.toInstant().toString()
            is OffsetDateTime -> value.toInstant().toString()
            else -> value
        }
}

/** Resolves the `GraphNode` interface to the generated type a [GraphNodeView] names. */
@Configuration
class GraphNodeWiring {
    @Bean
    fun graphNodeTypeResolver(): RuntimeWiringConfigurer =
        RuntimeWiringConfigurer { wiring ->
            wiring.type("GraphNode") { type ->
                type.typeResolver(
                    TypeResolver { env ->
                        val name = (env.getObject<Any?>() as? Map<*, *>)?.get(GraphNodeView.TYPE_NAME)?.toString()
                        name?.let { env.schema.getObjectType(it) }
                    },
                )
            }
        }
}
