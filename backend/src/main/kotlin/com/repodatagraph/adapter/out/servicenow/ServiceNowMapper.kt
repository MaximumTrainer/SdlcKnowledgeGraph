package com.repodatagraph.adapter.out.servicenow

import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Turns ServiceNow rows into what the ontology declares.
 *
 * Three things here are decisions rather than translations.
 *
 * A retired CI is tombstoned rather than dropped: "this used to be a service" is the answer to half
 * the questions asked of a CMDB six months later.
 *
 * A repository named in a custom field becomes an edge only if the graph already has that
 * repository. A CMDB field is a claim that a repository exists somewhere, not evidence of one here,
 * and creating a Repository node from it would fill the graph with repositories nobody can open.
 *
 * A relationship pointing at a CI outside the synced tables is skipped. A CMDB relates services to
 * hardware, contracts and locations; an edge to a node that was never ingested is worse than no edge,
 * because a traversal finds it and then finds nothing on the other side.
 */
@Component
class ServiceNowMapper(
    private val identityResolver: IdentityResolver,
    private val gitRemoteParser: GitRemoteParser,
    private val properties: ServiceNowProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun configurationItem(
        row: ServiceNowRow,
        table: String,
    ): GraphDelta? {
        val sysId = row.sysId ?: return null
        val props = ciProps(row, table, sysId)
        val key = identityResolver.keyFor(CONFIGURATION_ITEM, props)

        return GraphDelta(
            nodes = listOf(NodeUpsert(type = CONFIGURATION_ITEM, props = props, observedAt = row.updatedOn, sourceId = sysId)),
            edges = listOfNotNull(repositoryLink(row, key)),
            // A CMDB does not delete; it retires. That is exactly what a tombstone records.
            tombstones = if (row.display("operational_status")?.equals(RETIRED, ignoreCase = true) == true) listOf(key) else emptyList(),
        )
    }

    /** Null when either end is outside the tables this connector reads. */
    fun relationship(
        row: ServiceNowRow,
        known: Set<String>,
    ): GraphDelta? {
        val type = row.display("type")?.takeIf { declared -> properties.dependencyRelTypes.any { it.equals(declared, true) } }
        val parent = row.value("parent")?.takeIf { it in known }
        val child = row.value("child")?.takeIf { it in known }
        // All three or nothing: a relationship of a kind nobody asked for, or to a CI outside the
        // synced tables, is not half an edge - it is no edge.
        if (type == null || parent == null || child == null) return null

        return GraphDelta(
            edges =
                listOf(
                    EdgeUpsert(
                        type = DEPENDS_ON,
                        from = ciKey(parent),
                        to = ciKey(child),
                        props = mapOf("kind" to "cmdb", "relType" to type),
                        observedAt = row.updatedOn,
                    ),
                ),
        )
    }

    fun changeRequest(
        row: ServiceNowRow,
        known: Set<String>,
    ): GraphDelta? {
        // A row with no id or no number is not a change request anyone can refer to afterwards.
        val sysId = row.sysId
        val number = row.value("number")
        if (sysId == null || number == null) return null
        val props =
            baseProps(sysId) +
                buildMap<String, Any?> {
                    put("number", number)
                    row.value("short_description")?.let { put("shortDescription", it) }
                    row.display("state")?.let { put("state", it) }
                    row
                        .value("type")
                        ?.lowercase()
                        ?.takeIf { it in CHANGE_TYPES }
                        ?.let { put("changeType", it) }
                    row.display("risk")?.let { put("risk", it) }
                    ServiceNowTime.parse(row.value("start_date"))?.let { put("startDate", it) }
                    ServiceNowTime.parse(row.value("end_date"))?.let { put("endDate", it) }
                    row.display("close_code")?.let { put("closeCode", it) }
                    row.display("requested_by")?.let { put("requestedBy", it) }
                    row.display("assignment_group")?.let { put("assignmentGroup", it) }
                }

        return GraphDelta(
            nodes = listOf(NodeUpsert(type = CHANGE_REQUEST, props = props, observedAt = row.updatedOn, sourceId = sysId)),
            edges = affects(identityResolver.keyFor(CHANGE_REQUEST, props), row, known),
        )
    }

    fun incident(
        row: ServiceNowRow,
        known: Set<String>,
        knownChanges: Set<String>,
    ): GraphDelta? {
        val sysId = row.sysId
        val number = row.value("number")
        if (sysId == null || number == null) return null
        val props =
            baseProps(sysId) +
                buildMap<String, Any?> {
                    put("number", number)
                    row.value("short_description")?.let { put("shortDescription", it) }
                    row.display("state")?.let { put("state", it) }
                    row.display("priority")?.let { put("priority", it) }
                    row.display("severity")?.let { put("severity", it) }
                    ServiceNowTime.parse(row.value("opened_at"))?.let { put("openedAt", it) }
                    ServiceNowTime.parse(row.value("resolved_at"))?.let { put("resolvedAt", it) }
                    row.display("close_code")?.let { put("closeCode", it) }
                    row.display("assignment_group")?.let { put("assignmentGroup", it) }
                }
        val key = identityResolver.keyFor(INCIDENT, props)

        val causedBy =
            row.value("caused_by")?.takeIf { it in knownChanges }?.let { change ->
                // Reported, not inferred: somebody filled this in during a post-incident review, which
                // is a statement rather than a correlation.
                EdgeUpsert(type = CAUSED_BY, from = key, to = changeKey(change), observedAt = row.updatedOn)
            }

        return GraphDelta(
            nodes = listOf(NodeUpsert(type = INCIDENT, props = props, observedAt = row.updatedOn, sourceId = sysId)),
            edges = affects(key, row, known) + listOfNotNull(causedBy),
        )
    }

    private fun affects(
        from: NodeKey,
        row: ServiceNowRow,
        known: Set<String>,
    ): List<EdgeUpsert> =
        listOfNotNull(row.value("cmdb_ci"), row.value("business_service"))
            .filter { it in known }
            .distinct()
            .map { EdgeUpsert(type = AFFECTS, from = from, to = ciKey(it), observedAt = row.updatedOn) }

    /**
     * The repository a CI names, if the graph already has it.
     *
     * Not full confidence: this rests on somebody having filled in a custom field correctly, which is
     * a better guess than a name match and still a guess. An unparseable value is logged and dropped
     * rather than guessed at.
     */
    private fun repositoryLink(
        row: ServiceNowRow,
        ci: NodeKey,
    ): EdgeUpsert? =
        row
            .value(properties.repoUrlField)
            ?.let { url ->
                runCatching { gitRemoteParser.parse(url) }
                    .onFailure { log.info("{} is not a git remote, from {} on {}", url, properties.repoUrlField, ci.key) }
                    .getOrNull()
            }?.let { remote ->
                EdgeUpsert(
                    type = RELATES_TO_CI,
                    from = NodeKey(REPOSITORY, remote.key),
                    to = ci,
                    observedAt = row.updatedOn,
                    confidence = FIELD_CONFIDENCE,
                )
            }

    private fun ciProps(
        row: ServiceNowRow,
        table: String,
        sysId: String,
    ): Map<String, Any?> =
        baseProps(sysId) +
            buildMap {
                put("ciName", row.value("name") ?: sysId)
                // The row's own class, not the table it was read from: a `cmdb_ci_service` query
                // returns subclasses too, and calling them all services loses what they are.
                put("ciClass", row.value("sys_class_name") ?: table)
                row.display("operational_status")?.let { put("operationalStatus", it) }
                row.display("environment")?.let { put("environment", it) }
                row.display("business_criticality")?.let { put("businessCriticality", it) }
                row.display("owned_by")?.let { put("ownerGroup", it) }
                row.display("support_group")?.let { put("supportGroup", it) }
                row.value("number")?.let { put("number", it) }
            }

    private fun baseProps(sysId: String) = mapOf("sourceSystem" to SOURCE_SYSTEM, "instance" to properties.instance, "sysId" to sysId)

    private fun ciKey(sysId: String) = NodeKey(CONFIGURATION_ITEM, "$SOURCE_SYSTEM:${properties.instance}:$sysId")

    private fun changeKey(sysId: String) = NodeKey(CHANGE_REQUEST, "$SOURCE_SYSTEM:${properties.instance}:$sysId")

    private companion object {
        const val SOURCE_SYSTEM = "servicenow"
        const val CONFIGURATION_ITEM = "ConfigurationItem"
        const val CHANGE_REQUEST = "ChangeRequest"
        const val INCIDENT = "Incident"
        const val REPOSITORY = "Repository"
        const val DEPENDS_ON = "DEPENDS_ON"
        const val AFFECTS = "AFFECTS"
        const val CAUSED_BY = "CAUSED_BY"
        const val RELATES_TO_CI = "RELATES_TO_CI"
        const val RETIRED = "Retired"
        val CHANGE_TYPES = setOf("standard", "normal", "emergency")

        /** A filled-in field is better evidence than a name match, and still not a fact. */
        const val FIELD_CONFIDENCE = 0.95
    }
}
