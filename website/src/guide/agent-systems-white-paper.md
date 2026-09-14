<!-- GENERATED FROM docs/AGENT-SYSTEMS-WHITE-PAPER.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# A Queryable World Model for Software Agents

## How the SDLC Knowledge Graph enables agent-based systems to implement and improve the management of software estates

**White paper — September 2026**
**Project:** [MaximumTrainer/SdlcKnowledgeGraph](https://github.com/MaximumTrainer/SdlcKnowledgeGraph)

---

## Executive summary

Large language model agents can now read code, open pull requests, triage alerts and run deployments. Two things limit them in practice. The first is **context**: an agent has a finite window, and an estate of hundreds of repositories, pipelines and cloud resources does not fit in it — so the agent either loads too much and loses focus, or loads too little and guesses. The second is **verification**: an agent handed an alert cannot tell whether it is real, what changed, or whether it is safe to act, because the evidence is spread across systems that do not share identifiers.

The SDLC Knowledge Graph (SKG) is a unified engineering knowledge graph in which each Git repository is a node, surrounded by the teams, pipelines, artifacts, deployments, environments, cloud resources and configuration items that give it meaning. Every fact carries provenance, every node has a derived identity, and the whole model is published as a machine-readable ontology through REST and GraphQL.

This paper sets out the problem, explains how SKG's design addresses it, and walks through worked examples in two areas: keeping an LLM agent's context focused on exactly the part of the estate a task needs, and verifying and resolving incidents automatically with an auditable chain of evidence.

---

## Part 1 — The problem

### 1.1 Agents drown in context or starve for it

An LLM agent reasons over what is in its window. For a software estate, the relevant facts — which repositories a change touches, where the artifact runs, who owns the downstream consumers — are scattered across tools that were never designed to be read together:

| Question an agent must answer | Where the answer lives today |
|---|---|
| Which repositories matter for this task? | Tribal knowledge, monorepo folder conventions, guesswork |
| What does this change touch? | Package manifests, IaC files, service meshes |
| Where is this code running? | CI logs, container registries, Kubernetes, cloud consoles |
| Who owns the thing that broke? | CODEOWNERS files, wikis, ServiceNow, Slack |

Faced with this, agents fail in two symmetrical ways. **Over-loading**: the agent pulls in README after README, manifest after manifest, until the window is full of material irrelevant to the task and its reasoning degrades — the well-documented "lost in the middle" effect. **Under-loading**: the agent stops early, works from the three files it happened to open, and confidently produces a change that breaks a consumer it never saw.

Neither is a model capability problem. Both are the absence of a structure that says *these are the things that matter for this task, and nothing else.*

### 1.2 Agents cannot verify what they are told

Operations agents receive alerts, tickets and pages. Each arrives with an identifier from one system — an ARN, a pod name, a `sys_id` — and none of the surrounding facts. Before acting, a competent responder asks: Is this alert real or noise? Did anything change recently? Is this resource actually in production and actually ours? Has this already been reported under another name?

Answering those questions means joining the alerting system to the deployment history to the code to the ownership record to the service catalogue. Humans do this from memory and Slack. An agent doing it from raw APIs makes dozens of calls, mismatches identifiers between systems, and — critically — cannot say afterwards which fact justified its decision. An agent that cannot verify cannot be trusted to resolve.

### 1.3 Why document retrieval is not enough

The default remedy, retrieval-augmented generation over wikis and READMEs, improves recall but not structure. A vector search cannot tell an agent that a dependency of kind `api` means a contract test is needed before a change ships, that two identifiers in two systems refer to the same database, or that a deployment record is from last Tuesday and may no longer hold. Relationships, identity and provenance must be modelled, not retrieved.

---

## Part 2 — How the SDLC Knowledge Graph solves it

SKG is a deliberately small graph — nine core entity types and nine relationship types — chosen to answer two concrete questions: *what depends on this change and what infrastructure would it touch?* and *why did this deployment fail and who owns what broke?*

**Entity types:** Repository, Team, Service, Pipeline, Artifact, Deployment, Environment, CloudResource, ConfigurationItem.

**Relationship types:** OWNED_BY, OWNS_RESOURCE, DEPENDS_ON, HAS_PIPELINE, RELATES_TO_CI, BUILT_FROM, DEPLOYED_TO, TO_ENVIRONMENT, PROVIDES.

Five design decisions turn this from a database schema into an agent substrate.

### 2.1 Traversal replaces retrieval: the graph scopes the context

The central mechanism for context focus is that a task maps to a traversal, and a traversal returns a bounded, typed subgraph. "Everything relevant to changing `acme/payments`" is not a search; it is the set of nodes reachable by `DEPENDED_ON_BY`, `BUILDS`, `DEPLOYED_TO` and `OWNED_BY` from one node. The agent receives that set — typically tens of nodes, not hundreds of files — and nothing outside it. The graph does the join so the context window does not have to.

### 2.2 The ontology is a registry the agent can read

Node and edge types are declared in YAML, and everything else — the GraphQL schema, the persistence layer, the frontend types and the `GET /api/v1/ontology` payload — is generated from that registry. The schema is therefore discoverable at runtime: an agent framework can generate its tool definitions from the ontology rather than hard-coding them, and stays correct as the model grows.

### 2.3 Identity is derived, so one thing is one node

A node's key is computed from its properties, never assigned. `https://github.com/Acme/Payments.git`, `git@github.com:acme/payments.git` and `acme/payments` all resolve to `github.com/acme/payments`. Cloud resources key on provider and resource ID; artifacts on registry, name and digest; environments through an alias table so `prod`, `prd` and `live` collapse to `production`. Whatever identifier an agent is handed resolves to exactly one node, and re-asserting a fact it has observed is idempotent.

### 2.4 Every fact carries provenance, so the agent can verify

Each node and edge records its source system, source identifier, ingestion time, the time the source says it was true, a confidence score, whether it was inferred or reported by a system of record, a validity interval and the sync run that wrote it. This is the raw material of verification: an agent can distinguish a deployment record written by the CI connector three minutes ago from an ownership edge inferred by a rule six months ago, and act differently on each.

### 2.5 Refusals explain themselves, and agents are governed like people

Every write is validated against the registry; when the API refuses, it says which fields are missing, which end-type pairs an edge *is* allowed to connect, or which properties would change identity — the feedback an agent needs to self-correct. And access control is designed to apply to agents exactly as to people: an agent's writes carry its identity in provenance, and its blast radius is bounded by policy rather than by prompt wording.

### 2.6 Reference architecture

```
┌────────────────────────────────────────────────────────────┐
│  Agents (coding, review, triage, remediation, discovery)    │
└──────────────┬────────────────────────────┬────────────────┘
               │ tool calls                 │ tool calls
      ┌────────▼─────────┐        ┌─────────▼─────────┐
      │ Tool / MCP layer │        │ Systems of record │
      │ generated from   │        │ (GitHub, CI, cloud│
      │ /api/v1/ontology │        │  ServiceNow …)    │
      └────────┬─────────┘        └─────────┬─────────┘
               │ REST / GraphQL             │ connectors
      ┌────────▼────────────────────────────▼─────────┐
      │        SDLC Knowledge Graph (Neo4j)            │
      │  registry-driven ontology · derived identity   │
      │  provenance on every fact · validity intervals │
      │  auth + policy applied to agents and people    │
      └────────────────────────────────────────────────┘
```

The graph sits between agents and systems of record. Agents query it for scoped context and verified facts; connectors keep it in step with reality; agents write back what they observe and do, stamped with provenance.

---

## Part 3 — Worked examples

The examples use a fictional but typical organisation: a payments company with a few hundred repositories, a platform team, AWS infrastructure and ServiceNow for service management. Part 3A concerns keeping an agent's context focused; Part 3B concerns automated incident verification and resolution.

### 3A — Supporting LLM context focus

#### Example 1: Scoping a cross-repository change

**Task.** A coding agent is asked: "Add a `merchant_region` field to the settlement API and make sure consumers handle it."

**Without the graph.** The agent searches the organisation for "settlement". It finds 40 repositories mentioning the word, opens the README of each, loads eleven OpenAPI specs into context, and by the time it reaches the actual consumers its window is 70% noise. It edits the API repository and two consumers it found by string match, misses the one that calls the endpoint through a shared client library, and ships.

**With the graph.** The agent resolves `acme/settlement-api`, then runs one traversal:

```
Repository{settlement-api} —PROVIDES→ Service{settlement}
Service{settlement} —DEPENDED_ON_BY{kind: api}→ Repository | Service   (direct consumers)
Repository{settlement-api} —DEPENDED_ON_BY{kind: library}→ Repository   (via the shared client)
```

It receives seven repositories — four direct API consumers, the shared client library, and two repositories that consume through the client — with each `DEPENDS_ON` edge's `manifest` property naming the file where the dependency is declared. That is the entire context the task needs. The agent loads those seven repositories and the named manifest files, and nothing else. It makes the change in all seven, and the PR description lists exactly which consumers were updated and why, citing the graph edges as evidence.

**What the graph did.** It converted a fuzzy search into a bounded set, kept irrelevant material out of the window, and supplied the one consumer a text search would never have found.

#### Example 2: Giving a review agent only the blast radius

**Task.** A review agent runs on every PR against the shared API gateway repository. Its job is to say what could break.

**Without the graph.** The agent has two options: review only the diff (and know nothing about consumers), or be handed a list of "all services" (and be unable to reason about 200 of them at once). Teams pick the first, and the agent becomes a linter.

**With the graph.** On each PR the agent runs a fixed traversal from the gateway repository: `DEPENDED_ON_BY{kind: api}` → each dependant's current production `Deployment` → its `OWNED_BY` team. The result is a subgraph of typically five to fifteen nodes. The agent reads the diff *alongside that subgraph only*, and writes a review comment structured by downstream service: "Removes header `X-Merchant-Tier`; consumed by `merchant-onboarding` (Team Onboarding, prod since 12 Aug) and `risk-scoring` (Team Risk). The `merchant-onboarding` dependency is inferred from traffic analysis at confidence 0.7 — confirm with the owning team before merge." It requests review from the two named teams.

**What the graph did.** It gave the agent a context window sized to the actual blast radius, plus the provenance to say how sure it was about each item in it.

#### Example 3: Answering an engineer's question without a fishing expedition

**Task.** An engineer asks a chat agent: "Which of our services still write to the legacy `ledger-v1` database?"

**Without the graph.** The agent searches code for connection strings, finds variants (`ledger_v1`, `ledgerv1`, an environment variable with an opaque name), and returns a list it cannot vouch for.

**With the graph.** The agent resolves the `CloudResource` for the RDS instance by its ARN, follows `OWNED_BY_REPO` and `DEPENDED_ON_BY{kind: data}` to every repository and service with a data dependency on it, and returns four services with their owning teams and the manifest each dependency was read from. Context consumed: one query result. Answer quality: every item traceable to a source.

### 3B — Automated incident verification and resolution

#### Example 4: Verifying an alert before anyone is paged

**Situation.** At 02:14 an error-rate alert fires against an ARN for an ECS service. The on-call rota says to page a human — but is this real?

**Without the graph.** Either a human is woken to find out, or a runbook automation restarts the service blindly. Neither verifies anything.

**With the graph.** A verification agent takes the ARN and performs a fixed check sequence, each step a single graph read:

1. **Is this ours and is it production?** Resolve the ARN to a `CloudResource`; follow `OWNED_BY_REPO` to a `Repository` and `OWNED_BY` to a `Team`. It exists, it is owned by Team Payments, and its current `Deployment` is `TO_ENVIRONMENT` → `production`. Real, in scope.
2. **Did anything change?** Read the most recent `Deployment` for the artifact. Provenance shows it was written by the CI connector at 01:58, confidence 1.0, `inferred: false`. Sixteen minutes before the alert. Strong signal.
3. **What changed?** Follow `DEPLOYMENT_OF` → `Artifact` → `BUILT_FROM{commitSha}` → `Repository`. The commit is known; a diff link can be constructed.
4. **Is this already known?** Follow `RELATES_TO_CI` to the ServiceNow `ConfigurationItem` and check for an open incident against it. None.
5. **Could it be upstream?** Traverse `DEPENDS_ON{kind: api}` from the service and check each dependency's recent deployments. None in the last six hours.

Within thirty seconds the agent has a verified conclusion: a real production incident on a Payments-owned service, most likely caused by deployment `d-8f21` of commit `3c9a…`, no upstream cause, not yet reported. It opens the incident against the correct configuration item with all five findings attached, each linked to the graph fact and its provenance. Only now is a human paged — with the investigation already done.

**What the graph did.** It turned five cross-system questions into five reads on one model, and gave every finding a source the responder can check.

#### Example 5: Resolving a verified incident under policy

**Situation.** Continuing from Example 4. The organisation's policy is that agents may roll back a deployment autonomously if — and only if — the causal deployment is confirmed at confidence 1.0, occurred within the last hour, and the previous artifact is still available.

**With the graph.** The remediation agent reads the same facts the verification agent wrote. All three conditions hold, and the policy engine — evaluating the agent's request exactly as it would a human's — permits the rollback. The agent triggers the pipeline (`HAS_PIPELINE` gives it the workflow) to redeploy the previous `Artifact` digest, then writes a new `Deployment` node back to the graph with its own identity in provenance. Error rates fall. The agent updates the incident with the rollback deployment ID, the policy decision that authorised it, and the graph facts that satisfied each condition, then hands off to Team Payments for root cause with a complete, sourced timeline.

Had the causal edge been inferred rather than reported — say a `DEPENDS_ON` at confidence 0.6 — the same policy would have refused autonomous action and the agent would have proposed the rollback for one-tap human approval instead.

**What the graph did.** It made the safety conditions for autonomous action *queryable*, so they could be enforced by policy rather than trusted to a prompt, and it recorded the action as a first-class fact for the next agent or human.

#### Example 6: Suppressing a false alarm

**Situation.** An alert fires for high latency on a `CloudResource` in the `staging` environment, tagged `prd-like` by whoever created it.

**With the graph.** The verification agent resolves the resource and follows its current `Deployment` → `TO_ENVIRONMENT`. The environment alias table has already normalised the target to `staging`, not `production`. Ownership is Team Platform, and the most recent deployment is a scheduled load test recorded by the CI connector ten minutes earlier. The agent annotates the alert as expected, links the load-test deployment as evidence, and does not page. No human is woken for a test that was supposed to be noisy.

**What the graph did.** Derived identity and the environment alias table stopped a mislabelled resource from masquerading as production; provenance explained the latency.

---

## Part 4 — What changes

Across the examples the same shift occurs. Context that an agent used to gather by search, guess and over-loading is instead delivered as a bounded, typed subgraph sized to the task. Incidents that used to be verified by whoever was awake are verified by a fixed sequence of reads against one model, each finding carrying its source. Resolution that used to be either blind automation or a paged human becomes policy-governed autonomy, where the conditions for acting are facts the agent can query and the policy engine can check. And every action writes back, so the graph is more current after the incident than before it.

The SDLC Knowledge Graph does not make agents more capable. It makes them *focused* and *verifiable*: they see exactly the part of the estate a task needs, they can say how sure they are of each fact, and they leave behind an auditable record. Capability without focus produces noise; capability without verification produces confident mistakes. A queryable world model is what turns capability into management.

---

*Model and API details are drawn from the project's README, ONTOLOGY.md and architecture decision records.*
