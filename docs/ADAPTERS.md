# Adapters

An adapter, or connector, reads a source system and produces graph changes. Connectors are how the
graph stops being a hand-maintained diagram and starts reflecting reality.

> **Status: the mechanism is built; GitHub is the first connector on it.**
> [#22](../../issues/22) implemented the SPI, `AdapterRegistry`, `SyncService`, `GraphDeltaWriter`,
> `SyncScheduler`, the `connectors.*` configuration, the `/api/v1/connectors` and `/api/v1/webhooks`
> endpoints, the `SyncRun` and `ConnectorState` nodes and the `PRODUCED` edge.
> [#23](../../issues/23) added the GitHub connector: repositories, their topics, team ownership read
> from CODEOWNERS, the dependencies declared in seven manifest formats, and an index of
> infrastructure-as-code files, and webhooks for near-real-time change (see
> [below](#the-github-connector)). [#24](../../issues/24) added ServiceNow: configuration items,
> CMDB relationships, changes and incidents (see [below](#the-servicenow-connector)), and with it the
> `ItsmConnector` shape that Jira Service Management ([#35](../../issues/35)) will reuse. The clouds
> are still to come - AWS [#25](../../issues/25) - as is link resolution
> [#28](../../issues/28), so anything outside GitHub and the CMDB is still entered by hand (see the
> [user guide](USER-GUIDE.md)).

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

A Repository upsert that carries `provider` and `providerId` lands on the node already holding that
id, even under a new remote ([#88](../../issues/88)). A renamed or transferred repository is moved
to its new key with its edges, its old key recorded in `previousKeys`, and a `node.renamed` event
logged. When another node already holds the new remote, the upsert is written to that node without
the id, so the sync does not fail; merging the two is left to a person.
[ADR-0013](adr/0013-provider-id-is-an-alias-not-the-key.md) has the reasoning.

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
| `GET /api/v1/connectors` | List connectors, capabilities, health, last run and [freshness](#freshness) |
| `GET /api/v1/connectors/{name}` | One connector, with what `ConnectorState` remembers and its freshness |
| `POST /api/v1/connectors/{name}/sync?mode=full\|incremental` | Trigger a run |
| `GET /api/v1/connectors/{name}/runs` | Recent `SyncRun` history of one connector |
| `GET /api/v1/sync-runs?connector=&status=&from=&to=&page=&size=` | Every connector's runs, newest first, a page at a time ([Browsing the run history](#browsing-the-run-history)) |
| `GET /api/v1/sync-runs/{id}` | One run in full, with its whole error and `details` |
| `POST /api/v1/connectors/{name}/webhook` | Receive an event, signature verified, 202 Accepted |

Webhook endpoints will be exempt from the bearer authentication that
[ADR-0005](adr/0005-auth-oidc-github-first.md) introduces, but must verify their own signature. A
connector that cannot verify a signature must reject the request.

## Source systems and write scopes

Every fact names the system that stated it, `provenance.sourceSystem`, and the systems a fact may
name are declared once, in the registry's
[`sources.yaml`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/backend/src/main/resources/ontology/v1/sources.yaml)
([#117](../../issues/117)). `GET /api/v1/ontology` publishes them under `sources`, and
`SourceSystemsIT` fails while a connector the application runs stamps one that is not declared.

A connector running inside the application stamps its source on what it writes and is not held to
any scope: a scheduled run is not a request. A connector or agent writing **through the API**, as a
service principal, names the source in the write (`{"props": ..., "provenance": {"sourceSystem":
"github"}}`) and needs `graph:write` plus that source's own scope, so it can assert its own system's
facts and nobody else's ([AUTH](AUTH.md#source-scopes)):

| Source | Stated by | Scope to write it through the API |
| --- | --- | --- |
| `manual` | a person or a service principal, for itself | `graph:write` |
| `github` | [the GitHub connector](#the-github-connector) | `graph:write:github` |
| `github-actions` | [deployment reports](#self-ingestion-deployments-from-the-pipeline), through `INGEST_TOKEN` today | `graph:write:github-actions` |
| `servicenow` | [the ServiceNow connector](#the-servicenow-connector) | `graph:write:servicenow` |
| `aws` | the AWS connector, still to come ([#25](../../issues/25)) | `graph:write:aws` |
| `dogfood-seed` | [the seed job](#seeding-this-repository-on-the-dogfood-instance), through `INGEST_TOKEN` | `graph:write:dogfood-seed` |
| `sdlc-knowledge-graph` | the application itself, for its `SyncRun` and `ConnectorState` records | `graph:write:sdlc-knowledge-graph`, which no one should be given |

A source name is lower-case words joined by hyphens, because it is the last segment of a scope. A
qualified name such as `servicenow:prod` cannot be declared; a second instance of a system is told
apart by its facts' keys and properties, not by a second source.

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

## The ServiceNow connector

The CMDB is where an enterprise has already written down what its services are called and who
supports them. Reading it is how the graph stops being a developer's view of the estate and becomes
the same estate operations already argues about.

| It reads | It writes |
| --- | --- |
| `cmdb_ci_service`, `cmdb_ci_app`, `cmdb_ci_business_app` | a `ConfigurationItem` per row, keyed `servicenow:<instance>:<sys_id>` |
| `cmdb_rel_ci` where the type is a configured dependency | `ConfigurationItem DEPENDS_ON ConfigurationItem {kind: cmdb}` |
| `change_request` | a `ChangeRequest`, and `AFFECTS` to the CI it names |
| `incident` | an `Incident`, `AFFECTS` to its CI, and `CAUSED_BY` to the change when one is recorded |
| a custom field naming a repository | `Repository RELATES_TO_CI ConfigurationItem` at confidence 0.95 |
| `operational_status` of `Retired` | a tombstone, closing the CI's validity |

What makes this worth connecting is not the inventory. It is that a change and an incident both point
at the same configuration item, so "was this outage something we approved" becomes a traversal rather
than a meeting.

Four decisions are worth knowing about.

**The four reads happen in order, and that order is the design.** Configuration items first, because
everything else points at one; then relationships, changes and incidents, each using the sys_ids the
earlier passes actually ingested. An edge written before its far end exists is an edge the writer
refuses.

**A row referring to something outside the synced tables is skipped.** A CMDB relates services to
hardware, contracts, locations and people. Following those would pull an entire enterprise's asset
register into a graph about software, and an edge to a node that was never ingested is worse than no
edge — a traversal finds it and then finds nothing on the other side.

**A repository named in a custom field becomes an edge only if the graph already has that
repository.** A CMDB field is a claim that a repository exists somewhere, not evidence of one here.
Creating one from it would fill the graph with repositories nobody can open. The link is recorded at
0.95 rather than 1.0: it rests on somebody having filled in a field correctly, which is a better
guess than a name match and still a guess.

**A retired CI is closed, not dropped.** "This used to be a service" answers half the questions asked
of a CMDB six months later.

### Configuring it

```yaml
connectors:
  settings:
    servicenow:
      enabled: true
      schedule: "0 */15 * * * *"
  servicenow:
    instance-url: ${SERVICENOW_URL:}
    instance-name: ""                 # defaults to the host of instance-url; set it to keep keys
                                      # stable if the instance moves behind a new domain
    auth:
      mode: BASIC                     # OAUTH is configurable and not yet implemented
      username: ${SERVICENOW_USERNAME:}
      password: ${SERVICENOW_PASSWORD:}
    ci-tables: [cmdb_ci_service, cmdb_ci_app, cmdb_ci_business_app]
    repo-url-field: u_repository_url  # the custom field, if your instance has one
    dependency-rel-types: ["Depends on::Used by"]
    page-size: 500
    change-lookback-days: 90          # a first sync would otherwise read every change ever raised
    incident-lookback-days: 90
```

The integration user needs **read** on the CI tables listed, on `cmdb_rel_ci`, `change_request`,
`incident` and `sys_properties` — the last only because the health check reads one row from it — and
nothing else. The connector never writes to ServiceNow.

Two things [#24](../../issues/24) asks for are not here. **OAuth client credentials** is configurable
and refused at the health check: the token exchange is a second endpoint with its own refresh, and
shipping a half-built one would be worse than saying so. **Retiring CIs that vanish from a full
sync** is not done: the sync engine reconciles only connectors whose full sync is complete
([#150](../../issues/150)), and this one's is bounded by the lookback windows and the configured
tables, so it declares `fullSyncIsComplete = false`. A CI the CMDB marks `Retired` is closed.

### Implementing `ItsmConnector` for another tool

`ItsmConnector` adds three entry points to `SourceConnector` — `configurationItems`,
`changeRequests`, `incidents` — each taking a watermark and returning deltas. The contract they carry
is the one above: identity is `<tool>:<instance>:<id>`, a change and an incident both `AFFECTS` the
thing they are about, and an id referring to something outside what was ingested is skipped rather
than turned into a dangling edge. A tool that can answer those three questions is a few hundred lines
of client and mapper, which is the whole point of the interface existing.

## Writing a connector

A connector says **what it saw** and **when**. Everything else — identity, provenance, the run it
belonged to, whether a fact is still current — is the mechanism's job. That division is what makes a
connector small, and what stops six connectors each getting provenance slightly wrong.

1. **Write the acceptance feature first**, against a fake source server. `FakeConnector` in
   `src/testSupport` already proves the mechanism: that a run is recorded, that provenance is
   stamped, that a tombstone closes rather than deletes. Your feature only needs to prove the part
   that is about *your* source system.
2. **Implement `SourceConnector`**, or `ItsmConnector` / `CloudConnector` if one fits.
3. **Declare your source system** in `sources.yaml`, as your descriptor's `sourceSystem`, and run
   `./gradlew generateOntology`. `SourceSystemsIT` fails until you do, and a principal writing your
   system's facts through the API needs `graph:write:<your source>`
   ([above](#source-systems-and-write-scopes)).
4. **Declare every node and edge type you produce** in `descriptor()`. `AdapterRegistry` checks them
   against the ontology at startup and refuses to boot on an unknown one, because a connector writing
   an undeclared type fills the graph with nodes no traversal can reach.
5. **Return pages, not everything.** An estate does not fit in memory, and a page that fails leaves
   the pages before it intact — the run is marked `PARTIAL` rather than lost.
6. **Report a watermark** on each page if the source can say where you got to. It is stored only after
   a wholly successful run, so a partial one is retried rather than skipped.
7. **Never construct provenance.** Set `observedAt` when the source says the fact was true, and
   `confidence`/`inferred` when you are guessing rather than reporting. Who reported it and in which
   run is stamped for you.
8. **Never invent an identifier.** Return the properties an identity is derived from and let the
   resolver derive the key, or the same thing seen by two connectors becomes two nodes.
9. **Emit a tombstone** when the source reports that something has ended. It closes the fact's
   validity; it does not delete it. A bad day at the source must not erase history. Facts the source
   simply stops mentioning are closed for you by reconciliation, below, if your full sync is complete.
10. **Implement `verifyWebhook`** if you accept webhooks, using `WebhookSignatureVerifier` for the
   constant-time HMAC compare. It defaults to refusing everything, which is the right default.
11. **Register configuration** under `connectors.<name>`, with credentials from environment
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
      freshness-threshold: PT6H     # stale after this long without a success; see Freshness
```

A disabled connector is still listed by `GET /api/v1/connectors` with `enabled: false`, so "why is
nothing syncing" is answered by looking rather than by guessing which bean failed to load.

### Freshness

An answer built on a week-old snapshot is worse than no answer, so each connector says how long it
has been since it last succeeded (#29). `freshness-threshold` is an ISO-8601 duration. Left unset it
is `PT2H`, or `PT24H` for a connector with the `WEBHOOK` capability, whose scheduled run only
catches what its webhooks missed and may reasonably run nightly.

`GET /api/v1/connectors` and `GET /api/v1/connectors/{name}` carry it as:

```json
"freshness": {
  "lastSuccessAt": "2026-09-29T09:00:00Z",
  "ageSeconds": 10800,
  "thresholdSeconds": 3600,
  "stale": true
}
```

- `lastSuccessAt` and `ageSeconds` are `null` for a connector that has never succeeded, rather than
  a made-up age: "never" and "long ago" call for different fixes.
- `stale` is only ever true for an **enabled** connector whose last success is older than its
  threshold. A connector that has never succeeded is measured from when the instance started
  instead, so a new deployment gets one threshold to finish its first run before it is called stale.
- Only a `SUCCESS` counts. A `PARTIAL` run read part of the estate and cannot vouch for the rest, so
  a connector whose runs always come back partial goes stale. Webhook runs do not count either: one
  event says nothing about whether the rest of the estate is current.

The **Connectors** screen of the web interface shows each connector's age since its last success, or
`never`, with a `stale` badge beside a stale one, and its last run's status
([User guide](USER-GUIDE.md#connectors-and-sync-runs)).

While any enabled connector is stale, the `connectors` component of `/actuator/health` is `DOWN`
and names them; see [OBSERVABILITY.md](OBSERVABILITY.md#health). It stays out of the readiness
probe unless `observability.freshness-affects-readiness` is set.

A connector's freshness is about the connector. What its facts are worth is measured per source
system instead (#93): each source has a freshness window, facts it has not stated again within it
are read back `stale`, and the `freshness` health component, which only ever warns, reports each
source's lag. See [ONTOLOGY.md](ONTOLOGY.md#freshness-and-reading-as-of-an-instant) and
[OBSERVABILITY.md](OBSERVABILITY.md#source-lag).

## The GitHub connector

The first connector on the SPI, and the one the others are modelled on.

| It reads | It writes |
| --- | --- |
| `GET /orgs/{org}/repos`, every page | a `Repository` per repository, keyed on its remote |
| each repository's `topics` | `topics` on that node |
| `CODEOWNERS`, `.github/CODEOWNERS`, `docs/CODEOWNERS` | a `Team` per owning team, and an `OWNED_BY` edge carrying the patterns it was named against |
| `archived` | a tombstone, closing the repository's validity |

Several decisions in it are worth knowing about.

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

**A manifest is a declaration, not a build.** What is recorded is the version a repository *asks
for* — `^4.18.0` — not the version some build resolved today. The second is a fact about one build on
one day; the first is a fact about the repository, and only that outlives the build. For the same
reason a lockfile is **off by default**: it is the transitive closure, thousands of packages for a
repository that declares twenty, and recording it turns "who depends on this" into a question about
npm's install graph. Turn it on for "which repositories ship this exact vulnerable version", which is
the one question it answers better.

**Runtime and dev are kept apart.** A graph that cannot tell "this service ships this library" from
"somebody's test uses it" answers "what is affected by this CVE" with a list twice as long as the
truth, which is the same as answering nothing.

**A dependency on our own package is marked as the guess it is.** `@acme/billing` resolves to the
repository that publishes `@acme/billing` — matched by package name, held back until every repository
in the run has been read, and recorded at confidence 0.9 with `inferred: true`. It rests on a naming
convention, not on anything GitHub said, and a reviewer has to be able to tell it from a dependency
read straight out of a file. A name that matches the prefix but nothing in the org becomes an
ordinary `Library` after all, rather than being dropped.

**An IaC file is evidence, not infrastructure.** The graph records that a repository contains a file
claiming a bucket called `acme-payments-receipts` should exist. Whether one does, and whether it is
the one the cloud connector found, is the link engine's question ([#28](../../issues/28)) — and
keeping the two apart is what stops a plan nobody applied from becoming an asserted fact. Extraction
is deliberately shallow: identifiers a file states literally, never evaluated. A Terraform
configuration is a program, and running one against every repository in an estate is not something an
ingestion job should do, so a resource named entirely by interpolation is not indexed. YAML is only
treated as CloudFormation when its *name* says so, or every CI workflow in the estate would arrive as
infrastructure.

**One delta per repository.** A repository whose manifest will not parse costs that repository, not
the hundred either side of it: it is still recorded, without its dependencies, the run finishes
everything else and is then marked `PARTIAL` with the reason on the run record.

### Webhooks

A schedule says what was true a quarter of an hour ago. A webhook says what is true now, which is the
difference between a graph somebody consults and a graph somebody trusts.

Point a GitHub webhook at `POST /api/v1/webhooks/github`, content type `application/json`, with the
same secret as `connectors.settings.github.webhook-secret`, and subscribe to **Repositories**,
**Pushes** and **Teams**. Everything else is accepted and ignored.

| Event | What happens |
| --- | --- |
| `repository` | the repository is read back from the API and re-recorded; an archived one is closed |
| `push` to the default branch | only if it touched CODEOWNERS, a manifest or an IaC path — otherwise nothing |
| `team` | the team's name is upserted; membership is not ownership, which comes from CODEOWNERS |
| anything else | `204`, because the event was genuine and refusing it would have GitHub retrying for ever |

Three things about this are deliberate.

**A webhook is a hint, not a payload to believe.** It says "something changed here"; what changed is
then read back from the API through exactly the same code path a scheduled run uses. Trusting the
payload would mean a graph whose contents depend on which fields GitHub happened to include in which
event, and a second mapping to keep in step with the first.

**Nothing unverified reaches the connector.** `X-Hub-Signature-256` is checked with a constant-time
compare before `onWebhook` is called at all, and a signature under a scheme this code does not
implement fails rather than being read as though it were the one it does. An unverified body gets a
`401` that says nothing about why: a response distinguishing "no signature" from "wrong signature"
helps whoever is guessing at one.

**The same delivery twice is applied once.** GitHub redelivers whenever it is unsure the first
attempt landed. `X-GitHub-Delivery` is stored on the `SyncRun` as `sourceId`, and a repeat returns
`202` naming the run that already applied it. The check reads the graph rather than process memory,
so it survives a restart and works across instances. This lives in `SyncService` rather than in the
connector — every provider redelivers, and only the header name differs.

### Configuring it

```yaml
connectors:
  settings:
    github:
      enabled: true               # off by default, like every connector
      schedule: "0 */15 * * * *"
      webhook-secret: ${GITHUB_WEBHOOK_SECRET:}
  github:
    orgs: ${GITHUB_ORGS:}         # comma-separated; every org is read by the same run
    token: ${GITHUB_TOKEN:}       # a fine-grained token: repository metadata and contents, read-only
    base-url: https://api.github.com    # override for GitHub Enterprise Server
    wait-for-reset-seconds: 60          # how long a run will wait out a rate limit before giving up
    manifests:
      enabled: true
      internal-package-prefixes: ["@acme/", "com.acme"]  # what this organisation publishes under
      include-lockfiles: false          # the transitive closure; see above before turning this on
      max-file-bytes: 1048576           # anything larger is skipped and logged
    iac:
      enabled: true
```

The token needs read access to repository metadata and contents, and nothing else — contents only so
that CODEOWNERS can be read. A connector with no org or no token reports itself `DOWN` with the
reason rather than failing at the first sync.

One thing [#23](../../issues/23) asks for is deliberately not here: **GitHub App authentication**
is not implemented; a fine-grained personal access token is, and the two differ only in how a token
is obtained. **Repositories that disappear from a full sync** — deleted, transferred, or moved out of
the token's scope — are closed by reconciliation (below), as is a team or library no repository
mentions any more. A manifest that cannot be read costs that repository its dependencies for the
run, so a library only it used is closed until the next full sync reads it again, which reopens it.

### Reconciliation: what a full sync stops reporting

A tombstone covers a source that says something ended. The other way a fact stops being true is that
the source simply stops mentioning it, and the sync engine handles that once for every connector
([#150](../../issues/150)).

After a `FULL` run that finishes `SUCCESS`, every node still open whose provenance names the
connector's `sourceSystem`, and which was last asserted **before the run began**, is closed: its
`validTo` is set and the count is added to the run's tombstones. Nothing is deleted, and a node that
is reported again later is reopened by that write.

It does not run:

- after an `INCREMENTAL` run, which only asked what changed;
- after a `PARTIAL` or `FAILED` run, which cannot tell "gone" from "not read" — a partial run that
  closed the estate it failed to read would be the most destructive bug this system could have;
- for a connector whose descriptor says `fullSyncIsComplete = false`, because its full sync is
  scoped (one account of several, a time window, some tables of many) and would otherwise close
  everything outside the scope on every run. ServiceNow says so; the fake and GitHub connectors
  do not.

Selecting by "asserted before the run began", rather than by what the run itself wrote, also spares a
node that a webhook asserted while the run was going.

### What a run records

`SyncRun` holds the connector, the mode (`FULL`, `INCREMENTAL`, `WEBHOOK`), the counts, the
watermark and the error if there was one. `ConnectorState` is written after every scheduled or
manual run, whatever its status: `lastRunId`, `lastRunStatus` (and `lastStatus`, the same value
under its older name) and `lastFinishedAt` describe that run; `watermark` and `lastSuccessAt` move
only on a `SUCCESS`; `consecutiveFailures` counts the `PARTIAL` and `FAILED` runs since then and
goes back to 0 on the next success. A webhook run does not touch it. Every node a run writes also gets a `PRODUCED` edge from the run, so "show me everything that
run wrote" is one traversal rather than a scan — which is what makes a bad sync reversible rather
than merely auditable.

### Browsing the run history

`GET /api/v1/sync-runs` lists the recorded runs of every connector, newest `startedAt` first (#29,
FR5). Every filter is optional:

| Parameter | Meaning |
| --- | --- |
| `connector` | Only this connector's runs. A connector that no longer exists is an empty page, not an error: its runs are history. |
| `status` | `RUNNING`, `SUCCESS`, `PARTIAL` or `FAILED`, in any case. Anything else is a 400. |
| `from`, `to` | Runs that started at or after `from` and before `to`, both ISO-8601 instants such as `2026-09-01T00:00:00Z`. The window is half-open, so adjacent windows never count a run twice. Anything that is not an instant, or a `from` that is not before `to`, is a 400. |
| `page`, `size` | Offset paging: `page` from 0, `size` from 1 to 100, 20 by default. Out of range is a 400. |

```json
{
  "items": [
    {
      "id": "0b8f…", "connector": "github", "sourceSystem": "github", "mode": "FULL",
      "status": "PARTIAL", "startedAt": "2026-09-29T09:00:00Z", "finishedAt": "2026-09-29T09:01:30Z",
      "durationMs": 90000, "nodesUpserted": 3, "edgesUpserted": 2, "tombstones": 0,
      "error": "page 2 failed: …"
    }
  ],
  "page": 0, "size": 20, "totalElements": 41, "totalPages": 3
}
```

`durationMs` and `finishedAt` are null while a run is going. `error` in a summary is cut to 200
characters, ending in `…` when it was cut; `GET /api/v1/sync-runs/{id}` has the whole of it, plus
`watermark`, `sourceId` (the delivery behind a webhook run) and `details`, and answers 404
`{"error": "sync run not found", "id": …}` for a run that was never recorded or has been pruned.

`details` is where a connector's own account of a run belongs - a summary per account or region,
say. No connector records one yet, so it is always an empty object today; a connector that starts
recording one needs a declared `SyncRun` property to hold it, and this is where it will appear.

The history is read through its own port, `SyncRunStore`, rather than through `GraphStore`, whose
listing is in key order. Runs finished longer ago than `observability.sync-run-retention` (30 days by
default) are pruned nightly; what they produced stays, still naming the run in its provenance
([OBSERVABILITY.md](OBSERVABILITY.md#sync-run-retention)). A read-only deployment serves both
endpoints, as it does every read.

The **Sync runs** page of the web interface (`/sync-runs`) is this endpoint as a table, with its
filters and paging, and opens a run from it in full
([User guide](USER-GUIDE.md#connectors-and-sync-runs)).

### What a run reports

A connector reports nothing to the metrics itself. `SyncService` counts what the connector hands it,
so the meters in [OBSERVABILITY.md](OBSERVABILITY.md#metrics) are only as complete as what the
connector returns:

- **Pages.** Each `GraphDelta` returned from `sync` counts as a page in `sdlc_sync_pages_total`
  and is logged as `sync.page`. A connector that returns its whole estate as one page reports one
  page, however long it took; one that returns a page per request lets an operator see progress.
- **Nodes, edges and tombstones.** Counted as the delta writer applied them, so a connector reports
  them by returning them, never by counting them itself.
- **Failures.** A page that throws while it is read, or cannot be written, counts as a `page` error
  and makes the run `PARTIAL`; an exception from `sync` itself, before any page, counts as a `run`
  error and makes it `FAILED`. Throw rather than returning an empty page, or a failure reads as a
  quiet success.
- **Freshness.** Counts from the last run that succeeded in full (`ConnectorState.lastSuccessAt`).
  A connector whose full syncs always come back partial is never fresh, which is the point; see
  [Freshness](#freshness).
- **Webhooks.** `verifyWebhook` returning false counts as `rejected`, `onWebhook` returning null as
  `ignored`, and a `deliveryId` lets a redelivery count as `ignored` rather than a second run.

## Self-ingestion: deployments from the pipeline

"Why did the deployment fail" has no answer in a graph that never hears about deployments. So the
pipeline that deploys this project is itself a source system. After each deploy it posts what it
deployed to `POST /api/v1/ingest/deployment`, and the report is recorded through the `github-actions`
connector like any other webhook: in a `SyncRun`, deduplicated, with that connector's provenance.

```json
{
  "repository": "github.com/maximumtrainer/sdlcknowledgegraph",
  "commitSha": "5efa09d68706304efec8ec74349dd72ed44912cb",
  "artifacts": [
    { "name": "ghcr.io/maximumtrainer/sdlc-graph-backend", "digest": "sha256:…", "tag": "5efa09d" }
  ],
  "environment": "production",
  "status": "SUCCESS",
  "deployedAt": "2026-09-29T12:00:00Z",
  "deployedBy": "octocat",
  "runUrl": "https://github.com/maximumtrainer/sdlcknowledgegraph/actions/runs/42",
  "pipeline": { "provider": "github-actions", "workflowPath": ".github/workflows/deploy-dogfood.yml" }
}
```

`status` is `SUCCESS` or `FAILED`, and `deployedAt` is an ISO-8601 instant. `deployedBy` is
optional, and so is `pipeline.provider`, which defaults to `github-actions`. An artifact needs a
`digest` or a `tag`; a digest is preferred, because it is what identifies the artifact.

The report becomes:

| Fact | Key |
| --- | --- |
| `Repository`, merged into the one already there | `github.com/org/name` |
| `Pipeline`, with `lastRunStatus` `success` or `failure` | `github-actions:<repoKey>:<workflowPath>` |
| `Artifact` per entry, with `commitSha` and the tag as `version` | `<registry>/<name>@<digest>` |
| `Deployment` per artifact, with `status`, `deployedBy` | `<artifactKey>#<environmentKey>#<epoch seconds>` |
| `Environment`, with `prod`, `stg` and the other aliases resolved, typed by its name where that is a type the ontology knows and `other` where it is not | `production`, `staging`, … |
| `HAS_PIPELINE`, `BUILT_FROM {commitSha}`, `DEPLOYED_TO`, `TO_ENVIRONMENT` | between the above |

A Deployment keeps the report's `SUCCESS` or `FAILED`, which is what why-failed reads, while the
pipeline's last run is told in the words `Pipeline.lastRunStatus` allows, the ones the seed uses too
([#81](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/81)). A pipeline provider is
written as reported, even one outside `Pipeline.provider`'s enum, because it is part of the key; the
enum conformance report ([ONTOLOGY.md](ONTOLOGY.md#enums-and-the-writers-they-had-to-agree-with))
lists any such value.

Every fact has provenance `sourceSystem=github-actions`, with `sourceId` set to the run URL and
`observedAt` set to the time of the deploy. `GET /api/v1/graph/deployments?repoId=…` then lists the
deployment with that provenance.

| Answer | When |
| --- | --- |
| `202 {deploymentIds, created, nodes, edges}` | recorded; `created` is false when this exact report had already been applied |
| `400 {error, fields}` | the report is invalid; `fields` names every bad field, not only the first |
| `401` | no `Authorization: Bearer <token>` header, or the wrong token |
| `503` | this instance has no `ingest.token` (`INGEST_TOKEN`), so nobody may report to it |

The token is checked before the body is read, so a caller without it learns nothing from the
validation errors. The same report posted twice is one delivery: it is named by a hash of its bytes,
and a retrying workflow step applies it once.

The dogfood deploy is the first reporter. After every deploy, including a failed one, it posts both
images by digest (`scripts/deployment-report.mjs`), when the `dogfood` environment has an
`INGEST_TOKEN` (`fly/README.md`).

This is one of the two writes a read-only instance still accepts ([Deployment contract](DEPLOYMENT.md),
D6), because it has its own token. The same connector answers `POST /api/v1/webhooks/github-actions`
with the same payload and token, which is the generic webhook route that #22 set up.

## Seeding: this repository on the dogfood instance

Until the GitHub connector (#23) can read a repository for itself, the dogfood instance learns about
this one from a seed (#47): the repository, the teams in `CODEOWNERS`, the workflows, and the
repositories it depends on. The instance is read-only, so the seed writes through its own endpoint,
`POST /api/v1/ingest/seed`, behind the same `INGEST_TOKEN` as deployment reports, and is recorded
through the `dogfood-seed` connector.

```json
{
  "nodes": [
    { "type": "Repository", "sourceId": "https://github.com/MaximumTrainer/SdlcKnowledgeGraph",
      "props": { "url": "https://github.com/MaximumTrainer/SdlcKnowledgeGraph", "defaultBranch": "main",
                 "topics": [], "codeowners": ["@maximumtrainer"] } },
    { "type": "Team", "props": { "name": "maximumtrainer" } },
    { "type": "Pipeline", "props": { "provider": "github-actions", "repoKey": "github.com/maximumtrainer/sdlcknowledgegraph",
                                     "workflowPath": ".github/workflows/ci.yml", "name": "ci.yml",
                                     "repoId": "github.com/maximumtrainer/sdlcknowledgegraph", "lastRunStatus": "success" } }
  ],
  "edges": [
    { "type": "OWNED_BY", "from": 0, "to": 1, "props": { "pathPatterns": ["*"] } },
    { "type": "HAS_PIPELINE", "from": 0, "to": 2 }
  ]
}
```

An edge names its ends by their position in `nodes`. A seed may write only `Repository`, `Team` and
`Pipeline` nodes and `OWNED_BY`, `HAS_PIPELINE` and `DEPENDS_ON` edges. Every property is checked
against the ontology exactly as the node and edge APIs check it, enums and formats included, and a
Repository's `url` is judged as it will be stored, so any remote form passes its `url` format. An
edge only joins the types the ontology lets it join. A batch holds at most 500 nodes and 2000 edges.

The answers are those of the deployment endpoint: `202 {created, nodes, edges}`, `400 {error, fields}`
naming every problem, `401` without the token and `503` on an instance with none. Keys are derived,
so seeding again changes properties such as a pipeline's `lastRunStatus` and never adds nodes; the
same batch twice is one delivery. Every fact has provenance `sourceSystem=dogfood-seed`, so it can be
told from what the GitHub connector writes once it replaces the seed.

The writer is `scripts/dogfood-seed.mjs`, run daily and on demand by `.github/workflows/dogfood-seed.yml`.
It reads the repository, `CODEOWNERS`, each workflow file's latest run on the default branch (its
conclusion, or its status while it has none, folded onto `success`, `failure`, `cancelled`,
`in_progress` or `unknown`), and the
dependencies in `frontend/package.json` and `backend/build.gradle.kts` that name a git remote. Registry
versions and local paths are not repositories, so they are left out. It fails when the instance cannot
be reached, and when the instance holds more nodes than `SEED_NODE_CEILING` (1000), the cheap sign
that something else is writing to it.
