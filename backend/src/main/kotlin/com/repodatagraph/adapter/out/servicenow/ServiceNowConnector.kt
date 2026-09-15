package com.repodatagraph.adapter.out.servicenow

import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.DiscoveryResult
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.HealthStatus
import com.repodatagraph.domain.port.out.connector.ItsmConnector
import com.repodatagraph.domain.port.out.connector.SyncRequest
import com.repodatagraph.domain.port.out.connector.plus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * ServiceNow, and the first proof that [ItsmConnector] is a shape rather than a wish.
 *
 * The order of the four reads is the design. Configuration items come first because everything else
 * points at one: a relationship, a change and an incident are each about a CI, and an edge written
 * before its far end exists is an edge the writer refuses. So each pass records what it saw, and the
 * next pass uses that to decide which rows it can honestly connect.
 *
 * That is also why rows referring to things outside the synced tables are skipped rather than
 * ingested. A CMDB relates services to hardware, contracts, locations and people; following those
 * would pull an entire enterprise's asset register into a graph about software.
 */
@Component
class ServiceNowConnector(
    private val properties: ServiceNowProperties,
    private val client: ServiceNowClient,
    private val mapper: ServiceNowMapper,
    private val graphStore: GraphStore,
    private val clock: Clock,
) : ItsmConnector {
    override fun descriptor() =
        ConnectorDescriptor(
            name = NAME,
            sourceSystem = NAME,
            nodeTypes = setOf("ConfigurationItem", "ChangeRequest", "Incident"),
            edgeTypes = setOf("DEPENDS_ON", "AFFECTS", "CAUSED_BY", "RELATES_TO_CI"),
            // No webhooks: ServiceNow pushes through Business Rules, which is a change to the
            // instance rather than to this application, and nobody has asked for it yet.
            capabilities = setOf(Capability.FULL, Capability.INCREMENTAL, Capability.DISCOVERY),
        )

    override fun healthCheck(): HealthStatus =
        when {
            !properties.isConfigured() -> HealthStatus.down(UNCONFIGURED)
            properties.auth.mode == ServiceNowAuthMode.OAUTH ->
                HealthStatus.down("OAuth client credentials is configured but not implemented; use basic auth")
            client.isReachable() -> HealthStatus.up("${properties.instanceUrl} as ${properties.auth.username}")
            else -> HealthStatus.down("${properties.instanceUrl} did not answer")
        }

    override fun discover(): DiscoveryResult =
        DiscoveryResult(
            scopes = properties.ciTables,
            detail =
                mapOf(
                    "instance" to properties.instance,
                    "repoUrlField" to properties.repoUrlField,
                    "configured" to properties.isConfigured(),
                ),
        )

    override fun sync(request: SyncRequest): Sequence<GraphDelta> {
        require(properties.isConfigured()) { UNCONFIGURED }
        val watermark = Instant.now(clock)
        return session(request.since).deltas().map { it.copy(watermark = watermark) }
    }

    override fun configurationItems(since: Instant?): Sequence<GraphDelta> = session(since).configurationItems()

    override fun changeRequests(since: Instant?): Sequence<GraphDelta> = session(since).changes(idsOf("ConfigurationItem"))

    override fun incidents(since: Instant?): Sequence<GraphDelta> =
        session(since).incidents(idsOf("ConfigurationItem"), idsOf("ChangeRequest"))

    private fun session(since: Instant?) =
        ServiceNowSyncSession(
            client = client,
            mapper = mapper,
            properties = properties,
            graphStore = graphStore,
            clock = clock,
            since = since,
        )

    /**
     * What this instance has already put in the graph.
     *
     * For the interface's own entry points, which a caller can use one at a time: reading incidents
     * without having just read the CIs still has to know which CIs exist, and the graph is the only
     * place that knows.
     */
    private fun idsOf(type: String): Set<String> =
        graphStore
            .findNodes(type, mapOf("sourceSystem" to NAME, "instance" to properties.instance))
            .mapNotNull { it.props["sysId"]?.toString() }
            .toSet()

    private companion object {
        const val NAME = "servicenow"
        const val UNCONFIGURED = "connectors.servicenow needs an instance-url and credentials before it can read anything"
    }
}
