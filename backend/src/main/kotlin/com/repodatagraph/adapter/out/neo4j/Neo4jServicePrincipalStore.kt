package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.PrincipalType
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.model.ServicePrincipalKind
import com.repodatagraph.domain.port.out.ServicePrincipalStore
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository

/**
 * Service principal registrations in Neo4j (#115), as nodes of the ontology's ServicePrincipal meta
 * type keyed by name, so they are in the graph they vouch for and read back through the node API and
 * GraphQL like everything else.
 *
 * What a registration says about itself - who registered it, from when, until when - is exactly
 * what provenance says about any fact, so it is kept there: `writtenBy` is the registrar and
 * `validFrom`/`validTo` bound the registration. The properties are the name, the owning team's key
 * and the description.
 *
 * The label is a constant and every value is a parameter. A save replaces every property, so
 * registering a name again after deregistering it clears the old `validTo` and description.
 */
@Repository
class Neo4jServicePrincipalStore(
    private val neo4jClient: Neo4jClient,
) : ServicePrincipalStore {
    override fun find(name: String): ServicePrincipal? =
        neo4jClient
            .query("MATCH (n:$LABEL { key: ${'$'}key }) RETURN n { .* } AS n")
            .bindAll(mapOf("key" to name))
            .fetch()
            .one()
            .map { toPrincipal(GraphRowMapper.toNode(LABEL, it["n"])) }
            .orElse(null)

    /** Unpaged: a registration is a decision a person made, so there are tens of them, not millions. */
    override fun findAll(): List<ServicePrincipal> =
        neo4jClient
            .query("MATCH (n:$LABEL) RETURN n { .* } AS n ORDER BY n.key")
            .fetch()
            .all()
            .map { toPrincipal(GraphRowMapper.toNode(LABEL, it["n"])) }

    override fun save(principal: ServicePrincipal): ServicePrincipal {
        val key = NodeKey(LABEL, principal.name)
        val properties =
            mapOf(
                "name" to principal.name,
                "ownedBy" to principal.ownedBy,
                "description" to principal.description,
                "kind" to principal.kind.wireName,
            ) + ProvenanceMapper.toProperties(provenanceOf(principal))
        // SET n += with a null value removes the property, which is what clears an old validTo.
        neo4jClient
            .query(
                """
                MERGE (n:$LABEL { key: ${'$'}key })
                SET n += ${'$'}props, n.id = ${'$'}id
                """.trimIndent(),
            ).bindAll(mapOf("key" to key.key, "id" to key.id, "props" to properties))
            .run()
        return principal
    }

    private fun provenanceOf(principal: ServicePrincipal): Provenance =
        Provenance(
            sourceSystem = Provenance.MANUAL,
            ingestedAt = principal.validFrom,
            validFrom = principal.validFrom,
            validTo = principal.validTo,
            writtenBy = principal.registeredBy,
            principalType = principal.registeredBy?.let { PrincipalType.USER.wireName },
        )

    private fun toPrincipal(node: GraphNode): ServicePrincipal =
        ServicePrincipal(
            name = node.props["name"]?.toString() ?: node.key.key,
            ownedBy = node.props["ownedBy"]?.toString().orEmpty(),
            description = node.props["description"]?.toString(),
            registeredBy = node.provenance.writtenBy,
            validFrom = node.provenance.validFrom,
            validTo = node.provenance.validTo,
            // A registration stored before kinds existed (#30) is a service.
            kind = node.props["kind"]?.toString()?.let(ServicePrincipalKind::fromWireName) ?: ServicePrincipalKind.SERVICE,
        )

    private companion object {
        const val LABEL = ServicePrincipal.NODE_TYPE
    }
}
