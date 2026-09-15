package com.repodatagraph.domain.port.out.connector

import java.time.Instant

/**
 * What a system of record has to implement to reach the graph.
 *
 * There is one of these rather than an ingestion path per system, because the interesting part of a
 * connector is *where the facts come from*, not how they are written down. Identity, provenance and
 * freshness are properties of the mechanism around this interface, so a connector that forgets to
 * stamp provenance is not possible - it never gets the chance.
 *
 * A connector produces [GraphDelta] pages rather than one result. An estate does not fit in memory,
 * and a page that fails should not lose the pages that worked (see SyncService).
 */
interface SourceConnector {
    /** Who this connector is and what it can do. Read once at startup and validated against the ontology. */
    fun descriptor(): ConnectorDescriptor

    /** Whether the source system is reachable now. Reported by `GET /api/v1/connectors/{name}/health`. */
    fun healthCheck(): HealthStatus

    /** What this connector can see, without ingesting it. Checks credentials and scope before a first sync. */
    fun discover(): DiscoveryResult

    /**
     * The facts this connector has, as pages.
     *
     * A `since` of null means everything; otherwise only what changed after it. Returning a
     * [GraphDelta.watermark] is how the connector says where the next incremental run should start.
     */
    fun sync(request: SyncRequest): Sequence<GraphDelta>

    /**
     * What a webhook payload means, if anything. Returning null means "nothing to record".
     *
     * Only called after [verifyWebhook] has returned true.
     */
    fun onWebhook(event: WebhookEvent): GraphDelta? = null

    /**
     * Whether this payload really came from the source system.
     *
     * Defaults to false rather than true: a connector that has not thought about signatures refuses
     * webhooks rather than accepting forged ones. See WebhookSignatureVerifier for the usual HMAC
     * implementation.
     */
    fun verifyWebhook(
        headers: Map<String, String>,
        body: ByteArray,
    ): Boolean = false

    /**
     * The source system's own id for this delivery, if it sends one.
     *
     * Deduplication lives in the mechanism rather than in each connector, because every provider
     * redelivers - GitHub does it whenever it is unsure the first attempt landed - and applying one
     * event twice writes the same facts under two runs, so "what did that run change" stops having a
     * single answer. What differs per provider is only which header carries the id.
     */
    fun deliveryId(event: WebhookEvent): String? = null
}

/** A service management system: configuration items, changes and incidents. */
interface ItsmConnector : SourceConnector {
    fun configurationItems(since: Instant?): Sequence<GraphDelta>

    fun changeRequests(since: Instant?): Sequence<GraphDelta>

    fun incidents(since: Instant?): Sequence<GraphDelta>
}

/** A cloud provider, whose resources are scoped by account, subscription or project. */
interface CloudConnector : SourceConnector {
    fun resources(
        since: Instant?,
        scope: CloudScope,
    ): Sequence<GraphDelta>
}
