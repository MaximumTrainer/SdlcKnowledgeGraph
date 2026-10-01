// GENERATED FROM backend/src/main/resources/observability/events.yaml - DO NOT EDIT. Run ./gradlew generateLogEvents.
package com.repodatagraph.observability

import org.slf4j.event.Level

/**
 * Every event the application may log, one function per event declared in events.yaml.
 *
 * Each takes exactly the fields its declaration names, so a caller cannot invent a field or a level,
 * and every value passes through [SensitiveFieldMasker] on its way out (docs/OBSERVABILITY.md).
 */
object LogEvents {
    /** A node was created through the API. Names the properties supplied, never their values. */
    fun nodeCreated(
        type: String,
        key: String,
        properties: List<String>,
    ) =
        EventLog.emit("node.created", Level.INFO, "node created", mapOf("type" to type, "key" to key, "properties" to properties))

    /** A node moved to a new key through its alias, such as a repository renamed on its provider (#88). Names the keys it left and took. */
    fun nodeRenamed(
        type: String,
        from: String,
        to: String,
    ) =
        EventLog.emit("node.renamed", Level.INFO, "node renamed", mapOf("type" to type, "from" to from, "to" to to))

    /** One node was merged into another of its type (#98), by a person through the API or automatically when an artifact's digest was seen. Names both keys, how many edges moved, and who merged it. */
    fun nodeMerged(
        type: String,
        from: String,
        into: String,
        edgesMoved: Int,
        automatic: Boolean,
        by: String,
    ) =
        EventLog.emit("node.merged", Level.INFO, "node merged", mapOf("type" to type, "from" to from, "into" to into, "edgesMoved" to edgesMoved, "automatic" to automatic, "by" to by))

    /** An automatic merge found when an artifact's digest was seen was not made, because the graph changed under it (#98). Names both keys and the refusal; the write that found it stands. */
    fun nodeMergeSkipped(
        type: String,
        from: String,
        into: String,
        reason: String,
    ) =
        EventLog.emit("node.merge.skipped", Level.WARN, "node merge skipped", mapOf("type" to type, "from" to from, "into" to into, "reason" to reason))

    /** A node write was refused by the ontology. Names the fields at fault, never their values. */
    fun nodeRejected(
        type: String,
        fields: List<String>,
    ) =
        EventLog.emit("node.rejected", Level.INFO, "node rejected", mapOf("type" to type, "fields" to fields))

    /** An edge was created through the API. */
    fun edgeCreated(
        type: String,
        from: String,
        to: String,
    ) =
        EventLog.emit("edge.created", Level.INFO, "edge created", mapOf("type" to type, "from" to from, "to" to to))

    /** A graph store operation failed for a reason other than the domain refusing it, such as Neo4j being unreachable. */
    fun graphStoreFailed(
        operation: String,
        cause: Throwable,
    ) =
        EventLog.emit("graph.store.failed", Level.ERROR, "graph store operation failed", mapOf("operation" to operation), cause = cause)

    /** Counting the graph's nodes and edges by type failed, so sdlc_graph_nodes and sdlc_graph_edges keep their last values. */
    fun graphCountFailed(
        cause: Throwable,
    ) =
        EventLog.emit("graph.count.failed", Level.WARN, "graph count failed; keeping the last counts", mapOf(), cause = cause)

    /** A request was refused with 400 because its arguments could not be read. */
    fun httpRequestRejected(
        cause: Throwable,
    ) =
        EventLog.emit("http.request.rejected", Level.DEBUG, "request rejected as malformed", mapOf(), cause = cause)

    /** The deploy pipeline reported a deployment, which was applied. */
    fun ingestDeploymentReceived(
        deployments: Int,
        created: Boolean,
    ) =
        EventLog.emit("ingest.deployment.received", Level.INFO, "deployment report received", mapOf("deployments" to deployments, "created" to created))

    /** The dogfood seed posted a batch, which was applied. */
    fun ingestSeedReceived(
        nodes: Int,
        edges: Int,
        created: Boolean,
    ) =
        EventLog.emit("ingest.seed.received", Level.INFO, "seed batch received", mapOf("nodes" to nodes, "edges" to edges, "created" to created))

    /** Someone posted to an ingest endpoint without the ingest token. */
    fun ingestUnauthorized(
        endpoint: String,
    ) =
        EventLog.emit("ingest.unauthorized", Level.WARN, "ingest refused without a valid token", mapOf("endpoint" to endpoint), security = true)

    /** A user registered an identity provider client as a service principal, owned by a team. From now on the client's tokens are let in and its writes are attributed to it. */
    fun principalRegistered(
        name: String,
        ownedBy: String,
        registeredBy: String,
    ) =
        EventLog.emit("principal.registered", Level.INFO, "service principal registered", mapOf("name" to name, "ownedBy" to ownedBy, "registeredBy" to registeredBy), security = true)

    /** A user ended a service principal's registration. The record is kept with a validTo, and the client's tokens are refused from now on. */
    fun principalDeregistered(
        name: String,
        deregisteredBy: String,
    ) =
        EventLog.emit("principal.deregistered", Level.INFO, "service principal deregistered", mapOf("name" to name, "deregisteredBy" to deregisteredBy), security = true)

    /** A token the identity provider signed for a client, with no user in it, was refused with 403 because no current service principal registration names the client. Either a client nobody registered, or one since deregistered, is calling the API. */
    fun principalRefused(
        clientId: String,
    ) =
        EventLog.emit("principal.refused", Level.WARN, "token from an unregistered client refused", mapOf("clientId" to clientId), security = true)

    /** A principal the API knows was refused with 403 because its token lacks a graph scope the request needs - graph:read to read, graph:write to change the graph. Names the principal, the method and the scopes, never the path, which can hold a node's key. */
    fun scopeRefused(
        principal: String,
        method: String,
        required: List<String>,
        held: List<String>,
    ) =
        EventLog.emit("scope.refused", Level.WARN, "request refused for insufficient scope", mapOf("principal" to principal, "method" to method, "required" to required, "held" to held), security = true)

    /** The API started with no AUTH_ISSUER_URI, the anonymous read-only mode (#118). Reads need no token and every write is refused as read-only, so nothing is written by nobody; AuthGuard lets this start only with SDLC_READ_ONLY=true. Logged on every start. */
    fun authAnonymousReadonly() =
        EventLog.emit("auth.anonymous.readonly", Level.WARN, "no identity provider; serving reads to anyone and refusing every write", mapOf(), security = true)

    /** The store has a uniqueness constraint on the key of every node type. */
    fun ontologyConstraintsEnsured(
        nodeTypes: Int,
    ) =
        EventLog.emit("ontology.constraints.ensured", Level.INFO, "key constraints ensured", mapOf("nodeTypes" to nodeTypes))

    /** The ontology version this instance runs was written to the graph. */
    fun ontologyVersionRecorded(
        version: String,
    ) =
        EventLog.emit("ontology.version.recorded", Level.INFO, "ontology version recorded", mapOf("version" to version))

    /** A shipped migration was recorded as applied without running, because the graph began on its version or a newer one and never held the shape it repairs (#33). */
    fun ontologyMigrationBaselined(
        migration: String,
    ) =
        EventLog.emit("ontology.migration.baselined", Level.INFO, "ontology migration recorded as a baseline", mapOf("migration" to migration))

    /** A migration ran in a transaction of its own and was recorded on the graph with its checksum (#33). */
    fun ontologyMigrationApplied(
        migration: String,
        statements: Int,
        durationMs: Int,
    ) =
        EventLog.emit("ontology.migration.applied", Level.INFO, "ontology migration applied", mapOf("migration" to migration, "statements" to statements, "durationMs" to durationMs))

    /** A migration failed; its transaction was rolled back, so the graph is as it was before it, and nothing after it ran. At startup the application stops (#33). */
    fun ontologyMigrationFailed(
        migration: String,
        cause: Throwable,
    ) =
        EventLog.emit("ontology.migration.failed", Level.ERROR, "ontology migration failed and was rolled back", mapOf("migration" to migration), cause = cause)

    /** In manual mode the application started with migrations the graph has not had; an admin applies them from the lifecycle page. Health is not affected (#33). */
    fun ontologyMigrationsPending(
        dbVersion: String,
        registryVersion: String,
        pending: List<String>,
    ) =
        EventLog.emit("ontology.migrations.pending", Level.WARN, "ontology migrations are pending", mapOf("dbVersion" to dbVersion, "registryVersion" to registryVersion, "pending" to pending))

    /** The archive is enabled and will run on a schedule, in the mode it names (#33). */
    fun lifecycleArchiveScheduled(
        cron: String,
        mode: String,
    ) =
        EventLog.emit("lifecycle.archive.scheduled", Level.INFO, "archive scheduled", mapOf("cron" to cron, "mode" to mode))

    /** An archive run finished. A dry run changed nothing; export wrote the file; purge wrote the file and then deleted what it wrote (#33). */
    fun lifecycleArchiveFinished(
        mode: String,
        dryRun: Boolean,
        cutoff: java.time.Instant,
        nodes: Int,
        edges: Int,
    ) =
        EventLog.emit("lifecycle.archive.finished", Level.INFO, "archive run finished", mapOf("mode" to mode, "dryRun" to dryRun, "cutoff" to cutoff, "nodes" to nodes, "edges" to edges))

    /** A scheduled archive run could not finish. Nothing is purged that was not first written; the next run retries (#33). */
    fun lifecycleArchiveFailed(
        mode: String,
        cause: Throwable,
    ) =
        EventLog.emit("lifecycle.archive.failed", Level.WARN, "archive run failed", mapOf("mode" to mode), cause = cause)

    /** The link engine will resolve every cloud resource on a schedule, as a FULL run of link-engine. */
    fun linksScheduled(
        cron: String,
    ) =
        EventLog.emit("links.scheduled", Level.INFO, "link resolution scheduled", mapOf("cron" to cron))

    /** A connector's run touched resources or repositories, so a resolution scoped to them was started. Names both runs. */
    fun linksResolutionTriggered(
        connector: String,
        runId: String,
        linkRunId: String,
    ) =
        EventLog.emit("links.resolution.triggered", Level.INFO, "link resolution triggered by a sync run", mapOf("connector" to connector, "runId" to runId, "linkRunId" to linkRunId))

    /** A link rule could not read a resource's evidence, so that resource was not resolved in this run, which is partial. */
    fun linksRuleFailed(
        runId: String,
        resourceKey: String,
        cause: Throwable,
    ) =
        EventLog.emit("links.rule.failed", Level.WARN, "link rule failed; the resource is left as it was", mapOf("runId" to runId, "resourceKey" to resourceKey), cause = cause)

    /** A person accepted a candidate link, which became a manual OWNS_RESOURCE. Names the candidate, both ends and who accepted it. */
    fun linksCandidateAccepted(
        candidateId: String,
        resourceKey: String,
        repoKey: String,
        by: String,
    ) =
        EventLog.emit("links.candidate.accepted", Level.INFO, "candidate link accepted", mapOf("candidateId" to candidateId, "resourceKey" to resourceKey, "repoKey" to repoKey, "by" to by))

    /** A person rejected a candidate link; it is not proposed again while its evidence is the same. Names the candidate, both ends and who rejected it. */
    fun linksCandidateRejected(
        candidateId: String,
        resourceKey: String,
        repoKey: String,
        by: String,
    ) =
        EventLog.emit("links.candidate.rejected", Level.INFO, "candidate link rejected", mapOf("candidateId" to candidateId, "resourceKey" to resourceKey, "repoKey" to repoKey, "by" to by))

    /** A person stated that a repository owns a cloud resource. Names both ends and who stated it. */
    fun linksManualStated(
        resourceKey: String,
        repoKey: String,
        by: String,
    ) =
        EventLog.emit("links.manual.stated", Level.INFO, "manual link stated", mapOf("resourceKey" to resourceKey, "repoKey" to repoKey, "by" to by))

    /** A person closed a manual link; the edge keeps its history with validTo set. Names both ends and who closed it. */
    fun linksManualClosed(
        resourceKey: String,
        repoKey: String,
        by: String,
    ) =
        EventLog.emit("links.manual.closed", Level.INFO, "manual link closed", mapOf("resourceKey" to resourceKey, "repoKey" to repoKey, "by" to by))

    /** A connector will sync on a schedule. */
    fun connectorScheduled(
        connector: String,
        cron: String,
    ) =
        EventLog.emit("connector.scheduled", Level.INFO, "connector scheduled", mapOf("connector" to connector, "cron" to cron))

    /** A scheduled sync did not start, and why. */
    fun connectorScheduleSkipped(
        connector: String,
        reason: String,
    ) =
        EventLog.emit("connector.schedule.skipped", Level.INFO, "scheduled sync skipped", mapOf("connector" to connector, "reason" to reason))

    /** A sync run failed as a whole. */
    fun connectorRunFailed(
        connector: String,
        runId: String,
        cause: Throwable,
    ) =
        EventLog.emit("connector.run.failed", Level.ERROR, "connector run failed", mapOf("connector" to connector, "runId" to runId), cause = cause)

    /** A connector failed to read one page, so its run is partial. */
    fun connectorPageFailed(
        connector: String,
        runId: String,
        cause: Throwable,
    ) =
        EventLog.emit("connector.page.failed", Level.WARN, "connector page failed; the run is partial", mapOf("connector" to connector, "runId" to runId), cause = cause)

    /** A page a connector read could not be written to the graph. */
    fun connectorPageUnapplied(
        connector: String,
        runId: String,
        cause: Throwable,
    ) =
        EventLog.emit("connector.page.unapplied", Level.WARN, "connector page could not be applied", mapOf("connector" to connector, "runId" to runId), cause = cause)

    /** A webhook delivery had already been applied by an earlier run, so nothing changed. */
    fun connectorDeliveryDuplicate(
        connector: String,
        deliveryId: String,
        runId: String,
    ) =
        EventLog.emit("connector.delivery.duplicate", Level.INFO, "webhook delivery already applied", mapOf("connector" to connector, "deliveryId" to deliveryId, "runId" to runId))

    /** A verified webhook could not be applied. */
    fun connectorWebhookFailed(
        connector: String,
        runId: String,
        cause: Throwable,
    ) =
        EventLog.emit("connector.webhook.failed", Level.ERROR, "webhook failed to apply", mapOf("connector" to connector, "runId" to runId), cause = cause)

    /** A connector began a run, scheduled, manual or from a webhook. */
    fun syncStarted(
        connector: String,
        syncRunId: String,
        mode: String,
    ) =
        EventLog.emit("sync.started", Level.INFO, "sync run started", mapOf("connector" to connector, "syncRunId" to syncRunId, "mode" to mode))

    /** A page a connector read was written to the graph. Pages count from 1. */
    fun syncPage(
        connector: String,
        syncRunId: String,
        page: Int,
        nodes: Int,
        edges: Int,
    ) =
        EventLog.emit("sync.page", Level.INFO, "sync page applied", mapOf("connector" to connector, "syncRunId" to syncRunId, "page" to page, "nodes" to nodes, "edges" to edges))

    /** A run ended, with how it ended, how long it took and what it wrote. */
    fun syncFinished(
        connector: String,
        syncRunId: String,
        mode: String,
        status: String,
        durationMs: Int,
        nodesUpserted: Int,
        edgesUpserted: Int,
        tombstones: Int,
    ) =
        EventLog.emit("sync.finished", Level.INFO, "sync run finished", mapOf("connector" to connector, "syncRunId" to syncRunId, "mode" to mode, "status" to status, "durationMs" to durationMs, "nodesUpserted" to nodesUpserted, "edgesUpserted" to edgesUpserted, "tombstones" to tombstones))

    /** The retention job deleted the runs that finished before olderThan (#29, FR6). Logged even when it deleted none, so a quiet night still shows the job ran. */
    fun syncRunsPruned(
        deleted: Int,
        olderThan: java.time.Instant,
    ) =
        EventLog.emit("sync.runs.pruned", Level.INFO, "old sync runs pruned", mapOf("deleted" to deleted, "olderThan" to olderThan))

    /** The retention job could not finish; the runs it had not reached are pruned the next night. */
    fun syncRunsPruneFailed(
        deleted: Int,
        cause: Throwable,
    ) =
        EventLog.emit("sync.runs.prune.failed", Level.WARN, "pruning old sync runs failed", mapOf("deleted" to deleted), cause = cause)

    /** The GitHub health check could not reach GitHub. */
    fun githubUnreachable(
        reason: String,
    ) =
        EventLog.emit("github.unreachable", Level.WARN, "GitHub is not reachable", mapOf("reason" to reason))

    /** A GitHub request failed in a way worth retrying. */
    fun githubRetrying(
        reason: String,
        attempt: Int,
        maxAttempts: Int,
    ) =
        EventLog.emit("github.retrying", Level.INFO, "GitHub request failed; retrying", mapOf("reason" to reason, "attempt" to attempt, "maxAttempts" to maxAttempts))

    /** GitHub's rate limit was reached; the connector waits for it rather than failing. */
    fun githubRateLimited(
        waitSeconds: Int,
    ) =
        EventLog.emit("github.rate.limited", Level.INFO, "waiting for the GitHub rate limit to reset", mapOf("waitSeconds" to waitSeconds))

    /** A GitHub webhook of a type the connector does not use. */
    fun githubWebhookIgnored(
        eventType: String,
    ) =
        EventLog.emit("github.webhook.ignored", Level.DEBUG, "GitHub webhook ignored", mapOf("eventType" to eventType))

    /** A GitHub webhook passed its signature check but its body could not be read. */
    fun githubWebhookMalformed(
        reason: String,
    ) =
        EventLog.emit("github.webhook.malformed", Level.WARN, "verified GitHub webhook was not JSON", mapOf("reason" to reason))

    /** Dependencies on repositories not yet known were deferred to a later sync. */
    fun githubDependenciesDeferred(
        count: Int,
    ) =
        EventLog.emit("github.dependencies.deferred", Level.DEBUG, "internal dependencies deferred", mapOf("count" to count))

    /** A manifest or IaC file could not be read; the repository is still recorded. */
    fun githubFileUnreadable(
        path: String,
        repository: String,
    ) =
        EventLog.emit("github.file.unreadable", Level.WARN, "could not read a file", mapOf("path" to path, "repository" to repository))

    /** A file was too large to read. */
    fun githubFileSkipped(
        path: String,
        repository: String,
        bytes: Int,
    ) =
        EventLog.emit("github.file.skipped", Level.INFO, "file skipped as too large", mapOf("path" to path, "repository" to repository, "bytes" to bytes))

    /** A GitHub deployment was read whose run published no package version the connector could attribute to it, so there is no artifact to record it against. */
    fun actionsDeploymentUnattributed(
        repository: String,
        deploymentId: String,
    ) =
        EventLog.emit("actions.deployment.unattributed", Level.INFO, "deployment not recorded; no artifact it deployed is known", mapOf("repository" to repository, "deploymentId" to deploymentId))

    /** A package version was published while two or more runs of its repository were running, so it is credited to none of them rather than to a guess. */
    fun actionsPackageAmbiguous(
        repository: String,
        artifact: String,
    ) =
        EventLog.emit("actions.package.ambiguous", Level.INFO, "package version not attributed; more than one run could have published it", mapOf("repository" to repository, "artifact" to artifact))

    /** A verified GitHub event the github-actions connector does not record, or one about an owner it was not configured to read. */
    fun actionsWebhookIgnored(
        eventType: String,
        reason: String,
    ) =
        EventLog.emit("actions.webhook.ignored", Level.DEBUG, "GitHub Actions webhook ignored", mapOf("eventType" to eventType, "reason" to reason))

    /** The ServiceNow health check could not reach ServiceNow. */
    fun servicenowUnreachable(
        reason: String,
    ) =
        EventLog.emit("servicenow.unreachable", Level.WARN, "ServiceNow is not reachable", mapOf("reason" to reason))

    /** ServiceNow answered the health check with a refusal, usually of the credentials. */
    fun servicenowHealthRefused(
        reason: String,
    ) =
        EventLog.emit("servicenow.health.refused", Level.WARN, "ServiceNow refused the health check", mapOf("reason" to reason))

    /** A ServiceNow request failed in a way worth retrying. */
    fun servicenowRetrying(
        reason: String,
        attempt: Int,
        maxAttempts: Int,
    ) =
        EventLog.emit("servicenow.retrying", Level.INFO, "ServiceNow request failed; retrying", mapOf("reason" to reason, "attempt" to attempt, "maxAttempts" to maxAttempts))

    /** A configuration item's repository field is not a git remote, so no link is written. */
    fun servicenowRemoteInvalid(
        value: String,
        field: String,
        ci: String,
    ) =
        EventLog.emit("servicenow.remote.invalid", Level.INFO, "CI names something that is not a git remote", mapOf("value" to value, "field" to field, "ci" to ci))

    /** A configuration item names a repository the graph does not hold, so no link is written. */
    fun servicenowLinkUnresolved(
        repository: String,
    ) =
        EventLog.emit("servicenow.link.unresolved", Level.INFO, "no repository for the CI's link", mapOf("repository" to repository))
}
