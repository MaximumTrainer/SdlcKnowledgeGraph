# Adapters

An adapter, or connector, reads a source system and produces graph changes. Connectors are how the
graph stops being a hand-maintained diagram and starts reflecting reality.

> **Status: the mechanism is built; GitHub is the first connector on it.**
> [#22](../../issues/22) implemented the SPI, `AdapterRegistry`, `SyncService`, `GraphDeltaWriter`,
> `SyncScheduler`, the `connectors.*` configuration, the `/api/v1/connectors` and `/api/v1/webhooks`
> endpoints, the `SyncRun` and `ConnectorState` nodes and the `PRODUCED` edge.
> [#23](../../issues/23) added the GitHub connector: repositories, their topics, and team ownership
> read from CODEOWNERS (see [below](#the-github-connector)). Dependency manifests and IaC files are
> still to come, as are GitHub webhooks. Nothing else is connected yet: ServiceNow is
> [#24](../../issues/24), AWS [#25](../../issues/25), and link resolution [#28](../../issues/28), so
> everything outside GitHub is still entered by hand (see the [user guide](USER-GUIDE.md)).

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
when, and how confident it was. See [ONTOLOGY.md](ONTOLOGY.md).

## Two specialisations

Service-management tools differ in their APIs but not in their concepts, so they share a shape.
Writing ServiceNow against this interface is what makes Jira Service Management
([#35](../../issues/35)) a small piece of work rather than a second integration from scratch.

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
[ADR-0005](adr/0005-auth-oidc-github-first.md) introduces, but must verify their own signature. A
connector that cannot verify a signature must reject the request.

## Testing rule

Every connector ships a fake of its source system, using WireMock for HTTP APIs or SDK-level mocks
for cloud providers. Acceptance tests run entirely offline against the fake, so CI never depends on
a third-party service being reachable or on credentials existing.

Tests that talk to a real system are allowed, but they sit behind `-Dconnectors.live=true` and are
excluded from CI.

## Linking code to infrastructure

A cloud resource rarely says which repository produced it, so the link resolution engine
([#28](../../issues/28)) proposes links from evidence and scores its confidence.

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

## Writing a connector

A connector says **what it saw** and **when**. Everything else — identity, provenance, the run it
belonged to, whether a fact is still current — is the mechanism's job. That division is what makes a
connector small, and what stops six connectors each getting provenance slightly wrong.

1. **Write the acceptance feature first**, against a fake source server. `FakeConnector` in
   `src/testSupport` already proves the mechanism: that a run is recorded, that provenance is
   stamped, that a tombstone closes rather than deletes. Your feature only needs to prove the part
   that is about *your* source system.
2. **Implement `SourceConnector`**, or `ItsmConnector` / `CloudConnector` if one fits.
3. **Declare every node and edge type you produce** in `descriptor()`. `AdapterRegistry` checks them
   against the ontology at startup and refuses to boot on an unknown one, because a connector writing
   an undeclared type fills the graph with nodes no traversal can reach.
4. **Return pages, not everything.** An estate does not fit in memory, and a page that fails leaves
   the pages before it intact — the run is marked `PARTIAL` rather than lost.
5. **Report a watermark** on each page if the source can say where you got to. It is stored only after
   a wholly successful run, so a partial one is retried rather than skipped.
6. **Never construct provenance.** Set `observedAt` when the source says the fact was true, and
   `confidence`/`inferred` when you are guessing rather than reporting. Who reported it and in which
   run is stamped for you.
7. **Never invent an identifier.** Return the properties an identity is derived from and let the
   resolver derive the key, or the same thing seen by two connectors becomes two nodes.
8. **Emit a tombstone** when the source stops reporting something. It closes the fact's validity; it
   does not delete it. A bad day at the source must not erase history.
9. **Implement `verifyWebhook`** if you accept webhooks, using `WebhookSignatureVerifier` for the
   constant-time HMAC compare. It defaults to refusing everything, which is the right default.
10. **Register configuration** under `connectors.<name>`, with credentials from environment
    variables. Connectors are disabled by default: being on the classpath is not consent to reach a
    real system.

### Configuration

```yaml
connectors:
  settings:
    github:
      enabled: true
      schedule: "0 */15 * * * *"   # cron; the default is every quarter hour
      webhook-secret: ${GITHUB_WEBHOOK_SECRET:}
```

A disabled connector is still listed by `GET /api/v1/connectors` with `enabled: false`, so "why is
nothing syncing" is answered by looking rather than by guessing which bean failed to load.

## The GitHub connector

The first connector on the SPI, and the one the others are modelled on.

| It reads | It writes |
| --- | --- |
| `GET /orgs/{org}/repos`, every page | a `Repository` per repository, keyed on its remote |
| each repository's `topics` | `topics` on that node |
| `CODEOWNERS`, `.github/CODEOWNERS`, `docs/CODEOWNERS` | a `Team` per owning team, and an `OWNED_BY` edge carrying the patterns it was named against |
| `archived` | a tombstone, closing the repository's validity |

Four decisions in it are worth knowing about.

**Ownership comes from CODEOWNERS and nowhere else.** The org a repository sits in, who pushed to it
last, who has admin — all of these look like ownership and none of them is a statement of it. A
guess recorded at full confidence cannot afterwards be told apart from a fact somebody declared, so
a repository with no CODEOWNERS gets no `OWNED_BY` edge at all.

**An individual is not a Team.** `@acme/platform-team` becomes a `Team`; `@some-person` is recorded
in the repository's `codeowners` property and nothing else. A `Team` is named `host/org/team`, so two
orgs — or a public GitHub and an Enterprise Server — can each have a "platform" team without
silently becoming one node.

**Every run lists every repository, including an incremental one.** Listing is one request per
hundred repositories; reading CODEOWNERS is one request per repository, and that is what the
watermark saves. Filtering the listing itself would be cheaper and wrong: an archived repository's
`pushed_at` never moves again, so a connector that looked only at what changed would never see a
repository being retired.

**A spent rate limit is read from the header, not inferred from the status.** A 403 also means "your
token may not do that". A limit is an instruction rather than an error, so a reset that is seconds
away is waited out; one an hour away ends the run with the reset time in its error, because syncs
share a pool of four threads and a run that sleeps for an hour stops every other connector. A 5xx is
retried three times; a 404 for CODEOWNERS is an answer, not a failure.

### Configuring it

```yaml
connectors:
  settings:
    github:
      enabled: true               # off by default, like every connector
      schedule: "0 */15 * * * *"
  github:
    orgs: ${GITHUB_ORGS:}         # comma-separated; every org is read by the same run
    token: ${GITHUB_TOKEN:}       # a fine-grained token: repository metadata and contents, read-only
    base-url: https://api.github.com    # override for GitHub Enterprise Server
    wait-for-reset-seconds: 60          # how long a run will wait out a rate limit before giving up
```

The token needs read access to repository metadata and contents, and nothing else — contents only so
that CODEOWNERS can be read. A connector with no org or no token reports itself `DOWN` with the
reason rather than failing at the first sync.

Two things [#23](../../issues/23) asks for are deliberately not here yet. **GitHub App
authentication** is not implemented; a fine-grained personal access token is, and the two differ only
in how a token is obtained. **Repositories that disappear from a full sync** are not tombstoned —
only archived ones are. Recognising "the source stopped reporting this" needs the sync engine to
compare a full run against what the connector produced last time, which is a mechanism every
connector should share rather than six connectors each getting subtly wrong, so it is
[#150](../../issues/150).

### What a run records

`SyncRun` holds the connector, the mode (`FULL`, `INCREMENTAL`, `WEBHOOK`), the counts, the
watermark and the error if there was one. `ConnectorState` holds where the last successful run got
to. Every node a run writes also gets a `PRODUCED` edge from the run, so "show me everything that
run wrote" is one traversal rather than a scan — which is what makes a bad sync reversible rather
than merely auditable.
