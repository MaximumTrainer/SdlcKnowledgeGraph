<!-- GENERATED FROM docs/ADAPTERS.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# Adapters

An adapter, or connector, reads a source system and produces graph changes. Connectors are how the
graph stops being a hand-maintained diagram and starts reflecting reality.

> **Status: design, not yet implemented.** Nothing on this page exists in the codebase today. There
> is no `SourceConnector` interface, no `AdapterRegistry`, no `connectors.*` configuration, no
> `/api/v1/connectors` endpoints and no link resolution engine; `SyncRun` is declared in the
> ontology registry but nothing writes one, and every node and edge is currently entered by hand
> (see the [user guide](/guide/user-guide)). This document is the target contract that the M2
> milestone builds: the SPI in [#22](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/22), with reference implementations in
> [#23](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/23) (GitHub), [#24](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/24) (ServiceNow) and [#25](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/25)
> (AWS), and link resolution in [#28](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/28). Read every present-tense sentence below as
> a requirement on that work.

## The contract

```kotlin
interface SourceConnector {
    fun descriptor(): ConnectorDescriptor
    fun healthCheck(): HealthStatus
    fun discover(): DiscoveryResult
    fun sync(request: SyncRequest): Sequence<GraphDelta>
    fun onWebhook(event: WebhookEvent): GraphDelta? = null
    fun verifyWebhook(headers: Map<String, String>, body: ByteArray): Boolean = false
}
```

`descriptor()` declares the connector's name, the source system it speaks for, the node and edge
types it produces, and its capabilities: `FULL`, `INCREMENTAL`, `WEBHOOK`, `DISCOVERY`.

`discover()` reports what the current credentials can see: organisations, cloud accounts,
subscriptions, ServiceNow instances. It exists so an operator can confirm scope before a first sync.

`sync()` returns a lazy sequence of deltas rather than one large result, so a connector can page
through a big estate without holding it in memory. A `SyncRequest` with `since == null` is a full
sync; otherwise it is incremental from the last watermark.

```kotlin
data class GraphDelta(
    val nodes: List<NodeUpsert>,
    val edges: List<EdgeUpsert>,
    val tombstones: List<NodeKey>,
    val watermark: Instant?,
)
```

Every upsert carries its `Provenance`, so the graph always knows which connector asserted a fact,
when, and how confident it was. See [ONTOLOGY.md](/guide/ontology).

## Two specialisations

Service-management tools differ in their APIs but not in their concepts, so they share a shape.
Writing ServiceNow against this interface is what makes Jira Service Management
([#35](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/35)) a small piece of work rather than a second integration from scratch.

```kotlin
interface ItsmConnector : SourceConnector {
    fun configurationItems(since: Instant?): Sequence<ConfigurationItemRecord>
    fun changeRequests(since: Instant?): Sequence<ChangeRecord>
    fun incidents(since: Instant?): Sequence<IncidentRecord>
}

interface CloudConnector : SourceConnector {
    fun resources(since: Instant?, scope: CloudScope): Sequence<CloudResourceRecord>
}
```

A `CloudResourceRecord` is provider-neutral: provider, account, resource id, type, name, region and
tags. AWS is the reference implementation; Azure and GCP reuse the tag extraction and diffing logic
rather than reimplementing it.

## Running connectors

Connectors are Spring beans collected by an `AdapterRegistry`. Configuration is per connector, and
credentials always come from the environment:

```yaml
connectors:
  github:
    enabled: true
    schedule: "0 */15 * * * *"
    org: acme
    token: ${GITHUB_TOKEN}
  aws:
    enabled: false
    schedule: "0 0 * * * *"
    accounts: [123456789012]
    role-arn: ${AWS_SYNC_ROLE_ARN}
```

A disabled connector is still registered and visible, it is simply not scheduled.

Each run creates a `SyncRun` node recording the connector, start and finish time, status, counts of
nodes and edges written, the watermark reached, and any error. Every fact written during that run
references the run in its provenance, so a bad sync can be identified and reversed.

Operational endpoints:

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/connectors` | List connectors, capabilities and health |
| `POST /api/v1/connectors/{name}/sync?mode=full\|incremental` | Trigger a run |
| `GET /api/v1/connectors/{name}/runs` | Recent `SyncRun` history |
| `POST /api/v1/connectors/{name}/webhook` | Receive an event, signature verified, 202 Accepted |

Webhook endpoints will be exempt from the bearer authentication that
[ADR-0005](/adr/0005-auth-oidc-github-first) introduces, but must verify their own signature. A
connector that cannot verify a signature must reject the request.

## Testing rule

Every connector ships a fake of its source system, using WireMock for HTTP APIs or SDK-level mocks
for cloud providers. Acceptance tests run entirely offline against the fake, so CI never depends on
a third-party service being reachable or on credentials existing.

Tests that talk to a real system are allowed, but they sit behind `-Dconnectors.live=true` and are
excluded from CI.

## Linking code to infrastructure

A cloud resource rarely says which repository produced it, so the link resolution engine
([#28](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/28)) proposes links from evidence and scores its confidence.

| Rule | Evidence | Confidence | Inferred |
| --- | --- | --- | --- |
| Manual link | A person asserted it | 1.0 | no |
| Tag match | `repo=`, `repository=` or `source-repo=` resolves to a known repository | 0.95 | yes |
| Deployment record | A pipeline deployed an artifact built from the repository into this environment | 0.9 | yes |
| Infrastructure as code | A Terraform, CDK or Bicep file in the repository names the resource | 0.7 | yes |
| Naming convention | The resource name matches a configured service pattern | 0.4 | yes |

The highest-confidence rule wins. At 0.5 and above the engine writes `OWNS_RESOURCE` marked
inferred, with the rule's name in the edge's `rule` property (the registry already declares it).
Below that it writes `CANDIDATE_LINK`, a relationship to be added to the registry with the engine,
which surfaces in a review screen where a person accepts it, promoting it to a manual link, or
rejects it, recording a tombstone with a reason so the rule does not keep re-proposing it.

Re-running the engine is idempotent.

## Adding a connector

1. Write the acceptance feature first, using the fake source server.
2. Implement `SourceConnector`, or `ItsmConnector` or `CloudConnector` if one fits.
3. Declare every node and edge type it produces in `descriptor()`, and add any new types to the
   ontology registry.
4. Derive identity keys with the shared resolver. Never invent an identifier.
5. Set provenance honestly. A guess is `inferred = true` with a confidence below 1.0.
6. Register configuration under `connectors.<name>` with credentials from environment variables.
