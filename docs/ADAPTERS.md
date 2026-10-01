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
> [below](#the-github-connector)). [#86](../../issues/86) completed it: a pipeline per workflow,
> dependencies resolved to whichever repository publishes the package, forks, a `sourceId` on every
> fact, and runs that count what they wrote, left unchanged and could not read. [#24](../../issues/24) added ServiceNow: configuration items,
> CMDB relationships, changes and incidents (see [below](#the-servicenow-connector)), and with it the
> `ItsmConnector` shape that Jira Service Management ([#35](../../issues/35)) will reuse. The clouds
> are still to come - AWS [#25](../../issues/25) - so anything outside GitHub and the CMDB is still
> entered by hand, though link resolution [#28](../../issues/28) now proposes which repository owns
> each cloud resource (see the
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
| `GET /api/v1/connectors` | List connectors, their `version`, capabilities, health, last run and [freshness](#freshness) |
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
| `github-actions` | [deployment reports](#self-ingestion-deployments-from-the-pipeline), through `INGEST_TOKEN`, and [the github-actions connector](#the-github-actions-connector-runs-packages-and-deployments-from-github) | `graph:write:github-actions` |
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

A cloud resource rarely says which repository produced it. The link resolution engine
([#28](../../issues/28)) proposes owners from the evidence that connectors write, and scores each
proposal:

| Rule | Evidence | Confidence |
| --- | --- | --- |
| Manual link | A person stated or accepted it | 1.0 |
| Tag | `CloudResource.tags` has `repo=`, `repository=`, `source-repo=` or `git-repo=` naming a known repository | 0.95 |
| Deployment record | A `Deployment` whose `targetResourceKeys` names the resource, of an artifact built from the repository | 0.9 |
| Infrastructure as code | An `IacFile` of the repository whose `resourceRefs` names the resource | 0.7 |
| Naming convention | The resource's name, without environment, account and region, is the repository's or a package's name | 0.4 |

The highest-confidence proposal wins. At 0.5 and above the engine writes an inferred
`OWNS_RESOURCE` under the source `link-engine`, with the rule and its evidence. Below that, it
writes a `CANDIDATE_LINK`, which a person reviews on the Links page. Accepting a candidate makes a
manual owner. Rejecting one leaves a tombstone, which the engine keeps to until the evidence
changes. Re-running the engine is idempotent.

A resolution is a sync run of `link-engine`. It starts in three ways:

- on request, through `POST /api/v1/links/resolve`;
- nightly;
- after a run of any connector in `links.triggers`, scoped to what that run wrote.

What this means for a connector:

- A cloud connector should write its tags as `CloudResource.tags`, a list of `key=value` entries.
- A CI connector that knows where it deployed should write `Deployment.targetResourceKeys`.

Neither connector writes ownership itself. [ONTOLOGY.md](ONTOLOGY.md#linking-code-to-infrastructure)
has the rules in full.

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
| each file directly under `.github/workflows/` ending `.yml` or `.yaml`, from the branch listing | a `Pipeline` keyed `github-actions:<repoKey>:<workflowPath>`, and a `HAS_PIPELINE` edge ([#86](../../issues/86)) |
| the manifests (`package.json`, `build.gradle(.kts)`, `pom.xml`, `go.mod`, `requirements.txt`, `pyproject.toml`, and lockfiles when asked) | `DEPENDS_ON {kind: library, manifest}` to the `Repository` that publishes the package, inferred at 0.9, or else to a `Library` |
| `fork`, and a fork's own `parent` | `forkOf` on the repository: the key of what it was forked from |
| `archived` | a tombstone, closing the repository's validity |

Every node and edge it writes has `sourceSystem: github`, a `sourceId` saying where in GitHub it
was stated - the repository's node id, a team's `org/slug`, `org/repo:path` for a file, a
manifest or a workflow, `ecosystem:name` for a library - and `observedAt` from GitHub's `pushed_at`.
What GitHub reports is recorded at confidence 1.0; a dependency resolved to a repository is
`inferred: true` at 0.9.

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

**A dependency on a package a repository publishes is marked as the guess it is.** Every dependency
is held back until every repository in the run has been read, then resolved to the repository that
publishes that package name (`Repository.packageNames`, from its own manifest) - by what this run
read and, for repositories it did not read, by what the graph already holds - and recorded at
confidence 0.9 with `inferred: true`, its `manifest` naming the file (#86). It rests on a name
matching, not on anything GitHub said, and a reviewer has to be able to tell it from a dependency
read straight out of a file. No prefix is needed for this; a name nothing publishes becomes an
ordinary `Library`, and a name two repositories publish resolves to neither. A fork carries its
upstream's manifest, so it is never what a dependency resolves to unless
`manifests.resolve-to-forks` says so. Nothing is inferred beyond what a manifest states outright:
that is the link engine's ([#28](../../issues/28)).
[ADR-0017](adr/0017-github-connector-resolves-by-what-repositories-publish.md) has the reasoning.

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
| `push` to the default branch | only if it touched CODEOWNERS, a manifest, a workflow or an IaC path — otherwise nothing |
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
      internal-package-prefixes: ["@acme/", "com.acme"]  # a webhook's hint; see below
      include-lockfiles: false          # the transitive closure; see above before turning this on
      max-file-bytes: 1048576           # anything larger is skipped and logged
      resolve-to-forks: false           # a fork carries its upstream's package names
    iac:
      enabled: true
    pipelines:
      enabled: true                     # a Pipeline per .github/workflows file (#86)
```

`internal-package-prefixes` no longer decides what resolves to a repository: a scheduled run
resolves any name a repository publishes. A webhook has read one repository, so a dependency named
under one of these prefixes that nothing is known to publish yet is left to the next scheduled run
(`github.dependencies.deferred`) rather than recorded as a library.

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

### Per-connector rules and grace periods

What reconciliation retires can be tuned per connector
([#33](../../issues/33), [ADR-0014](adr/0014-data-lifecycle.md)), under
`connectors.settings.<name>.lifecycle`:

```yaml
connectors:
  settings:
    github:
      lifecycle:
        missing-from-full-sync: tombstone   # or ignore: retire nothing the run stops reporting
        grace-period: P7D                   # spare what the source last stated within the last 7 days
```

`tombstone` with no grace (`PT0S`) is the default and is exactly the behaviour above. A grace period
moves the line from "asserted before the run began" to "asserted before the run began, less the
grace", so a fact a source drops for one run and reports again in the next is never closed; it is
closed by the first successful full sync after the grace has passed. `ignore` leaves the connector's
facts to its tombstones alone. A negative grace period stops startup. There is no setting that lets a
`PARTIAL` or `FAILED` run retire anything: `requireSuccessfulRun` is always true.

Everything a tombstone or reconciliation closes records why (`source-deleted` or
`missing-from-sync`), and its current edges are closed with it
([ONTOLOGY.md](ONTOLOGY.md#history-retirement-and-archival)). `GET /api/v1/lifecycle` lists each
connector's rules, and the web interface's Lifecycle page shows them.

### What a run records

`SyncRun` holds the connector, the mode (`FULL`, `INCREMENTAL`, `WEBHOOK`), the counts, the
watermark and the error if there was one. `nodesUpserted` and `edgesUpserted` count every fact the
run asserted; `written` and `unchanged` split the same facts by whether the graph moved, so a run
over an estate that did not change reports `written: 0` (#86). A fact stated again is still written,
because freshness and reconciliation count from when its source last stated it; only the count says
nothing moved. `failed` counts what the run could not read or write - each item a connector names,
such as a repository whose manifest does not parse, each page that could not be written, and a run
that failed outright as one - and `connectorVersion` is the version the connector's descriptor
declares (GitHub's is 2.0.0), so a run can be read against the code that produced it. A run
recorded before these existed reads them as `null`. `ConnectorState` is written after every scheduled or
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
      "written": 1, "unchanged": 4, "failed": 1, "connectorVersion": "2.0.0",
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

## The github-actions connector: runs, packages and deployments from GitHub

A pipeline that does not report its deploys leaves nothing behind, and most never will be changed to.
So the `github-actions` connector also reads what GitHub Actions did from GitHub itself
([#90](../../issues/90)): the workflow runs of the GitHub connector's owners, the package versions
each run published to GitHub Packages, and the deployments each made through the Deployments API.
It writes the same facts, under the same keys and `sourceSystem=github-actions`, as a deployment
report does, so the Artifact, Environment and Pipeline a report and the connector both see are one
node each. [ADR-0018](adr/0018-ci-deployments-read-from-github-actions.md) has the reasoning.

| Fact | From |
| --- | --- |
| `Artifact`, with `commitSha` and the version | a package version created while a run of its repository was running: a container image as `ghcr.io/<owner>/<package>@<digest>` with its first tag but `latest` as `version`; an npm or Maven package as `<name>:<version>` at confidence 0.8, folded into its digest's node once one is known ([#98](../../issues/98)) |
| `BUILT_FROM {commitSha}` to the `Repository`, and `HAS_PIPELINE` to the run's workflow | the run |
| `Deployment` per artifact, with `status`, `deployedBy` and `deployedAt` | a deployment and its newest status that is not `inactive`: `success` is `SUCCESS`, `failure` and `error` are `FAILED`, `in_progress` is `IN_PROGRESS`, anything else `PENDING` |
| `Environment`, with `prod`, `stg` and the other aliases resolved | the deployment's environment |
| `DEPLOYED_TO`, `TO_ENVIRONMENT` | between the above, beginning when the deployment was created |

Every fact's `sourceId` is the run's id and its `observedAt` the run's completion. A deployment is
credited to the run its statuses' log URL names, or else to the newest run of its commit that
published something; one that deployed nothing the connector can name is logged as
`actions.deployment.unattributed` and not recorded. A package version two runs of the repository
could have published is credited to neither (`actions.package.ambiguous`).

A successful deployment closes the previous successful deployment of the same image (registry and
name) to the same environment: that Deployment and its edges get `validTo` set to the newer one's
`deployedAt` and the retirement reason `superseded`, and drop out of what is current while staying in
its history. A failed deployment replaces nothing. GitHub's own `inactive` status on the older
deployment is not read as how it went.

A run reads, per repository, the runs that completed after the watermark and the deployments whose
newest status is after it; a first or full run looks back `lookback`. It is not a complete statement
of the source, so it retires nothing it did not see. A repository GitHub refuses to show is counted
as unreadable and the run goes on, partial.

### Its webhook

In the organisation's or repository's webhook settings, point a webhook at
`/api/v1/webhooks/github-actions`, content type `application/json`, with the secret set to
`connectors.settings.github-actions.webhook-secret`, and choose the **Workflow runs** and
**Deployment statuses** events. A `workflow_run` event with action `completed` reads that run back,
with what it published and the deployments of its commit it made; a `deployment_status` event reads
that deployment back. The payload is only a hint, as for the GitHub connector, and an event about an
owner not in `connectors.github.orgs` is ignored (`actions.webhook.ignored`).

The route is the one deployment reports also use. A request carrying `X-Hub-Signature-256` is judged
by that signature alone, and refused (`401`) when it does not match, whatever else it carries; a
request without one is a deployment report and needs the ingest token. GitHub's `X-GitHub-Delivery`
names a delivery, so a redelivered event is applied once.

### Configuring it

It reads with the GitHub connector's owners and token, and runs only when switched on:

```yaml
connectors:
  settings:
    github-actions:
      enabled: true              # off by default; deployment reports work either way
      schedule: "0 */15 * * * *" # a Spring cron; every 15 minutes if unset
      webhook-secret: ${GITHUB_ACTIONS_WEBHOOK_SECRET:}
  github:
    orgs: ${GITHUB_ORGS:}
    token: ${GITHUB_TOKEN:}
    deployments:
      lookback: P7D              # how far back a first or full run reads
      max-run-duration: PT6H     # how long a run can take; runs created this much earlier are listed
      package-types: [container, npm, maven]
      registries:                # where each kind of package is served; GitHub's own by default
        container: ghcr.io
```

The token needs, beyond what the GitHub connector needs, read access to **Actions** and
**Deployments** on the repositories and `read:packages` for the owner's packages. Without `connectors.github.orgs` and a token, a run fails saying so
and the health check reports the connector down unless it is accepting deployment reports. The token
is never logged.

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

The GitHub connector now reads everything the seed does and more, and its facts carry
`sourceSystem=github`, so the two can run side by side and the seed can be retired once the
connector has run;
[Running the GitHub connector on the dogfood instance](#running-the-github-connector-on-the-dogfood-instance)
says how.

The writer is `scripts/dogfood-seed.mjs`, run daily and on demand by `.github/workflows/dogfood-seed.yml`.
It reads the repository, `CODEOWNERS`, each workflow file's latest run on the default branch (its
conclusion, or its status while it has none, folded onto `success`, `failure`, `cancelled`,
`in_progress` or `unknown`), and the
dependencies in `frontend/package.json` and `backend/build.gradle.kts` that name a git remote. Registry
versions and local paths are not repositories, so they are left out. It fails when the instance cannot
be reached, and when the instance holds more nodes than `SEED_NODE_CEILING` (1000), the cheap sign
that something else is writing to it.

### Running the GitHub connector on the dogfood instance

The dogfood instance does not run the GitHub connector until someone gives it the three settings
below; with none of them it runs exactly as before. It is read-only, so it cannot be asked to sync
and takes no webhooks: the connector runs on its schedule only, as a write the operator configured,
through the same `GraphDeltaWriter` and provenance as any run
([ADR-0017](adr/0017-github-connector-resolves-by-what-repositories-publish.md#running-it-and-read-only-instances)).

| Setting | Where | Value for this repository |
| --- | --- | --- |
| `CONNECTORS_SETTINGS_GITHUB_ENABLED` | `[env]` in `fly/fly.backend.toml` | `"true"` |
| `GITHUB_ORGS` | `[env]` in `fly/fly.backend.toml` | `"MaximumTrainer"` |
| `GITHUB_TOKEN` | a fly secret, never a file: `fly secrets set GITHUB_TOKEN=... --app sdlc-graph-backend` | a fine-grained personal access token with read-only **Metadata** and **Contents** on the repositories to read |
| `CONNECTORS_SETTINGS_GITHUB_SCHEDULE` | `[env]`, optional | a Spring cron; every 15 minutes if unset. `"0 0 */6 * * *"` reads four times a day |
| `CONNECTORS_SETTINGS_GITHUB_FRESHNESS_THRESHOLD` | `[env]`, optional | an ISO-8601 duration longer than the schedule's gap, such as `"PT12H"` |

`MaximumTrainer` is a user account, not an organisation. GitHub answers 404 for a user asked about
as an organisation, so the connector then lists the account's own repositories under `/users`
instead, and GitHub lists only a user's public repositories there. The `config-secrets` guard refuses
a token in `fly/` or any other deployment configuration, and the connector never logs it.

Once a run has succeeded, `GET /api/v1/sync-runs?connector=github` shows what it wrote, and the
repository, its `CODEOWNERS` teams, its workflows and its dependencies carry `sourceSystem=github`
beside the seed's `dogfood-seed`. The seed can then be retired by disabling
`.github/workflows/dogfood-seed.yml`; what only the seed wrote, such as a pipeline's `lastRunStatus`,
stays as the seed last wrote it.

### Running the github-actions connector on the dogfood instance

It is off there too, and turning on the GitHub connector does not turn it on. With the GitHub
connector's `GITHUB_ORGS` and `GITHUB_TOKEN` in place, it needs:

| Setting | Where | Value for this repository |
| --- | --- | --- |
| `CONNECTORS_SETTINGS_GITHUB_ACTIONS_ENABLED` | `[env]` in `fly/fly.backend.toml` | `"true"` |
| `CONNECTORS_SETTINGS_GITHUB_ACTIONS_SCHEDULE` | `[env]`, optional | a Spring cron; every 15 minutes if unset |
| `GITHUB_TOKEN`'s permissions | the token itself | **Actions** and **Deployments** read on the repository, and `read:packages` |

No webhook secret: the instance is read-only, so it takes no webhooks and the connector runs on its
schedule only. Deployment reports keep arriving through `POST /api/v1/ingest/deployment` beside it.

What it will find is limited, and the reason is the deploy, not the connector. The `deploy` job runs
in the `dogfood` environment, so GitHub records a deployment for every deploy, but the images go to
`registry.fly.io`, not GitHub Packages, so no run of this repository publishes a package the connector
can see. Each of those deployments is then logged as `actions.deployment.unattributed` and not
recorded, and the deployment reports stay the instance's record of its own deploys. Pushing the images
to `ghcr.io` as well, or reading a registry other than GitHub Packages, would change that; neither is
part of [#90](../../issues/90).
