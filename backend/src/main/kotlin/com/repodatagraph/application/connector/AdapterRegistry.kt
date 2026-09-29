package com.repodatagraph.application.connector

import com.repodatagraph.config.ConnectorsProperties
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.SourceConnector
import org.springframework.stereotype.Component
import java.time.Duration

/** A connector that was declared twice, or that intends to write a type nobody declared. */
class ConnectorRegistrationException(
    message: String,
) : IllegalStateException(message)

/** A connector and what configuration says about it. */
data class RegisteredConnector(
    val connector: SourceConnector,
    val enabled: Boolean,
    /** `connectors.settings.<name>.freshness-threshold`, when configuration sets one. */
    private val configuredFreshnessThreshold: Duration? = null,
) {
    val descriptor get() = connector.descriptor()
    val name get() = descriptor.name

    /**
     * How long after its last success this connector counts as stale (#29, FR4).
     *
     * Two hours by default: long enough for a scheduled connector to miss a few runs to a flaky API
     * without paging anyone, short enough that an answer is never more than a morning out of date.
     * A connector that takes webhooks gets a day, because its scheduled run only catches what the
     * webhooks missed and may reasonably be set to run nightly.
     */
    val freshnessThreshold: Duration
        get() =
            configuredFreshnessThreshold
                ?: if (Capability.WEBHOOK in descriptor.capabilities) WEBHOOK_FRESHNESS else SCHEDULED_FRESHNESS

    private companion object {
        val SCHEDULED_FRESHNESS: Duration = Duration.ofHours(2)
        val WEBHOOK_FRESHNESS: Duration = Duration.ofHours(24)
    }
}

/**
 * Every connector the application knows about, checked at startup.
 *
 * Two checks happen here rather than at the first sync, because both failures are silent otherwise.
 * Two connectors claiming one name would mean whichever Spring happened to order last quietly wins,
 * and a connector writing a node type nobody declared would fill the graph with nodes no traversal
 * can reach. Both are cheap to check once and expensive to discover later.
 *
 * A disabled connector is still registered. It is visible in `GET /api/v1/connectors` with
 * `enabled=false`, so "why is nothing syncing" is answered by looking rather than by guessing which
 * bean failed to load.
 */
@Component
class AdapterRegistry(
    connectors: List<SourceConnector>,
    properties: ConnectorsProperties,
    ontology: OntologyRegistry,
) {
    private val byName: Map<String, RegisteredConnector>

    init {
        val duplicates =
            connectors
                .groupBy { it.descriptor().name }
                .filterValues { it.size > 1 }
                .keys
        if (duplicates.isNotEmpty()) {
            throw ConnectorRegistrationException(
                "more than one connector is called ${duplicates.joinToString(", ")}; " +
                    "a connector name is how configuration and every URL addresses it, so it has to be unique",
            )
        }

        connectors.forEach { validateAgainstOntology(it, ontology) }

        byName =
            connectors.associate { connector ->
                val name = connector.descriptor().name
                val settings = properties.settingsFor(name)
                name to RegisteredConnector(connector, settings.enabled, settings.freshnessThreshold)
            }
    }

    fun all(): List<RegisteredConnector> = byName.values.sortedBy { it.name }

    fun enabled(): List<RegisteredConnector> = all().filter { it.enabled }

    fun find(name: String): RegisteredConnector? = byName[name]

    private fun validateAgainstOntology(
        connector: SourceConnector,
        ontology: OntologyRegistry,
    ) {
        val descriptor = connector.descriptor()
        val unknownNodes = descriptor.nodeTypes.filter { ontology.nodeType(it) == null }
        val unknownEdges = descriptor.edgeTypes.filter { ontology.edgeType(it) == null }
        if (unknownNodes.isEmpty() && unknownEdges.isEmpty()) return

        throw ConnectorRegistrationException(
            buildString {
                append("connector '${descriptor.name}' declares types the ontology does not:")
                if (unknownNodes.isNotEmpty()) append(" node types ${unknownNodes.sorted()}")
                if (unknownEdges.isNotEmpty()) append(" edge types ${unknownEdges.sorted()}")
                append(". Add them to the registry, or it will write nodes nothing can traverse to.")
            },
        )
    }
}
