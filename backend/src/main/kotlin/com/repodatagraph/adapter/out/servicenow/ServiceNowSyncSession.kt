package com.repodatagraph.adapter.out.servicenow

import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.plus
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One run of the ServiceNow connector, and the state a run needs while it is going.
 *
 * The order of the four reads is the design. Configuration items come first because everything else
 * points at one: a relationship, a change and an incident are each about a CI, and an edge written
 * before its far end exists is an edge the writer refuses. So each pass records the sys_ids it saw,
 * and the next pass uses that to decide which rows it can honestly connect.
 *
 * That is also why rows referring to things outside the synced tables are skipped rather than
 * followed. A CMDB relates services to hardware, contracts, locations and people; following those
 * would pull an entire enterprise's asset register into a graph about software.
 */
class ServiceNowSyncSession(
    private val client: ServiceNowClient,
    private val mapper: ServiceNowMapper,
    private val properties: ServiceNowProperties,
    private val graphStore: GraphStore,
    private val clock: Clock,
    private val since: Instant?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val seenCis = mutableSetOf<String>()
    private val seenChanges = mutableSetOf<String>()

    /** Everything this run has to say, in the order the later passes depend on. */
    fun deltas(): Sequence<GraphDelta> =
        sequence {
            yieldAll(configurationItems())
            yieldAll(relationships())
            yieldAll(changes())
            yieldAll(incidents())
        }

    fun configurationItems(): Sequence<GraphDelta> =
        properties.ciTables.asSequence().flatMap { table ->
            client
                .rows(table, since)
                .mapNotNull { row ->
                    row.sysId?.let { seenCis += it }
                    mapper.configurationItem(row, table)?.let { withKnownRepositories(it) }
                }.inPages()
        }

    /**
     * The relationships whose ends this run actually ingested.
     *
     * Read after the CIs for that reason: the set of ingested sys_ids is what makes "is this
     * relationship about something we hold" answerable at all.
     */
    fun relationships(known: Set<String> = seenCis): Sequence<GraphDelta> =
        client
            .rows(RELATIONSHIP_TABLE, since)
            .mapNotNull { mapper.relationship(it, known) }
            .inPages()

    fun changes(known: Set<String> = seenCis): Sequence<GraphDelta> =
        client
            .rows(CHANGE_TABLE, since ?: lookback(properties.changeLookbackDays))
            .mapNotNull { row ->
                row.sysId?.let { seenChanges += it }
                mapper.changeRequest(row, known)
            }.inPages()

    fun incidents(
        known: Set<String> = seenCis,
        knownChanges: Set<String> = seenChanges,
    ): Sequence<GraphDelta> =
        client
            .rows(INCIDENT_TABLE, since ?: lookback(properties.incidentLookbackDays))
            .mapNotNull { mapper.incident(it, known, knownChanges) }
            .inPages()

    /**
     * Drops a repository link whose repository the graph has never seen.
     *
     * A CMDB field claims a repository exists somewhere; it is not evidence of one here. Writing the
     * edge anyway would fail the whole page, and creating the Repository from the field would fill
     * the graph with repositories nobody can open.
     */
    private fun withKnownRepositories(delta: GraphDelta): GraphDelta {
        val (known, unknown) = delta.edges.partition { graphStore.findNode(it.from) != null }
        unknown.forEach { log.info("no Repository {} for the CI it is named on; no link written", it.from.key) }
        return delta.copy(edges = known)
    }

    /**
     * A page of rows as one delta, rather than a delta per row.
     *
     * A run against an enterprise CMDB is tens of thousands of rows, and a delta each would be tens
     * of thousands of round trips through the writer. A page is also the unit the API answered in, so
     * a page failing loses exactly what one request read.
     */
    private fun Sequence<GraphDelta>.inPages(): Sequence<GraphDelta> =
        chunked(properties.pageSize).map { page -> page.reduce { merged, next -> merged + next } }

    private fun lookback(days: Long): Instant = Instant.now(clock).minus(days, ChronoUnit.DAYS)

    private companion object {
        const val RELATIONSHIP_TABLE = "cmdb_rel_ci"
        const val CHANGE_TABLE = "change_request"
        const val INCIDENT_TABLE = "incident"
    }
}
