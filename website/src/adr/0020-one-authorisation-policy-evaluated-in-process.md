<!-- GENERATED FROM docs/adr/0020-one-authorisation-policy-evaluated-in-process.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0020: One authorisation policy, in Rego, evaluated inside the API

## Status

Accepted. Implemented by [#30](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/30) and [#95](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/95).

## Context

Until now the API decided access in two places. Route scopes came from `ScopePolicy`
([#116](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/116)), and source scopes from `ScopeSourceWriteAuthorization`
([#117](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/117)). Both were Kotlin.

[#30](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/30) asks for role-based access, team ownership, sensitivity labels and
redaction, decided by Open Policy Agent (OPA) as policy-as-code. [#95](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/95) asks for
OPA on every request, a documented refusal body, a confidence rule on provenance, and a queryable
"agent-actions" policy. That policy decides whether an agent may act on its own judgement, starting
with rollback. The MCP server ([#31](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/31)) will need the same answers.

Three constraints shaped the design:

- **Nothing may break.** Every existing caller, test and deployment must behave as before.
- **The dogfood must keep fitting the free tier.** It runs on fly.io as one backend machine (512 MB)
  and one Neo4j machine ([ADR-0010](/adr/0010-dogfood-on-fly-io)). A third machine for OPA would cost
  money and add a dependency that can fail.
- **REST, GraphQL and MCP must not disagree.** They need one decision path, not three.

## Decision

- **One subject, action and resource decision.** Every request under `/api/v1` and `/graphql` is
  put to `data.sdlc.authz.decision` once for each thing it does. The inputs are:
  - **Subject:** a user, service, agent or anonymous caller, with its scopes, roles and teams.
  - **Action:** `read`, `query`, `create`, `update`, `delete`, `link`, `sync` or `admin`. The action
    follows the route family's scopes, so it does not have to be declared again.
  - **Resource:** a node type, one node, an edge, an endpoint or the policy itself.

  The filter `data.sdlc.authz.filter` decides which nodes of a read the caller may see, and which
  properties to remove from each. GraphQL is decided before the document runs, as it was for scopes.
  The MCP server will ask the same `PolicyDecisionPoint`.

- **OPA runs in-process, as WebAssembly, not as a sidecar.**
  - `opa build --target wasm` compiles the Rego under `policy/` and its data into a bundle
    (`backend/src/main/resources/policy/bundle.tar.gz`). The bundle is committed, and
    `node scripts/opa.mjs check` fails while it differs from what the Rego compiles to.
  - The API evaluates the bundle with `opa-java-wasm`, a pure-Java WebAssembly runtime. A decision
    takes tens of microseconds. Compiling the bundle once at startup takes about a second.
  - No deployment needs anything new. The fly.io dogfood stays at two machines and its
    configuration is unchanged.
  - The decision cannot be "OPA is unreachable", because there is no network hop. Compose's
    `governance` profile still runs OPA, serving the same `policy/` directory, but only so people
    can explore the policy. The API never calls it.
  - We rejected a sidecar for three reasons:
    - It needs a third machine, or a second process on the 512 MB one.
    - It needs a failure mode for when it cannot be reached.
    - A sidecar loaded from a different copy of the policy could disagree with the API.

  `SDLC_POLICY_BUNDLE` can point the API at another bundle, built by the same command, so a
  deployment can change its policy without a rebuild.

- **The policy fails closed.** When the policy cannot be evaluated, the request is refused with
  `503 {"error": "policy_unavailable"}`. This applies to reads as well as writes: an answer the
  policy has not checked is not one the API gives. A bundle without its data, or one that does not
  compile, stops the API at startup.

- **The shipped policy changes nothing.**
  - It grants exactly what the scopes grant: `graph:read` to read, `graph:write` to write, and
    `graph:write` plus `graph:admin` to administer. `DefaultPolicyParityTest` holds the policy to
    that for every mapped route and every combination of scopes.
  - A scope refusal keeps its body, `403 {"error": "insufficient scope", required, held}`, and its
    challenge header. Scopes are the first rule evaluated, so a caller that handled that refusal
    still gets it.
  - Roles apply only to a token that carries the roles claim (`sdlc_roles` by default). A token
    without the claim is judged by its scopes alone, and is cleared for every sensitivity level.
    That is every token issued today, so no caller sees less than before.
  - Roles narrow scopes and never widen them.

- **Any other refusal names its rule.** The body is
  `403 {"error": "policy denied", "policy": "<rule>", "reason": "<why>"}`, from #95. #30 suggested
  `{error: "forbidden"}`, but a caller cannot act on that, and #95's body tells it which rule
  refused and why.

- **Sensitivity labels live in the ontology registry.** They are `public`, `internal`,
  `confidential` and `restricted`, set on node types and on properties.
  - A type with no label is `internal`.
  - A property is labelled only when it is more sensitive than its type. The lint enforces this as
    ONT015.
  - An edge is as sensitive as the more sensitive of the types it joins.

  The registry generates the policy's data (`policy/sdlc/ontology/data.json`), so the labels cannot
  drift from the types. 1.10.0 labels three things:
  - `ServicePrincipal` is `restricted`;
  - `Team.email` is `confidential`;
  - `Incident.shortDescription` is `confidential`.

  A node of a type above the reader's clearance is hidden. A property above it is redacted and
  named in `redacted`. A read that cannot be filtered node by node, such as impact analysis or a
  context pack, is answered only to a reader cleared for everything the registry declares.

- **Ownership is looked up only when it could change the answer.** A member of a team that owns a
  node may curate it, whatever their role. The owners are read from the graph only when the
  caller's roles refused the request, the caller belongs to a team, and the request is about one
  node. No other request reads the graph to be decided.

- **Agents are service principals registered with `kind: agent`.** The policy judges an agent by
  the rules a user with the same roles and scopes meets, with one more rule: an agent may never
  administer the graph.

- **The agent-actions policy is a question the agent asks.**
  - The agent calls `POST /api/v1/policy/evaluate` with an action and the ids of the graph facts it
    relies on.
  - The API looks each fact up and gives the policy what the graph holds: whether it exists, its
    confidence, whether a rule inferred it, and its age. For a deployment, it also gives whether an
    earlier artifact went to the same environment.
  - The policy answers with every reason it refused, or the facts that satisfied it. A fact the
    agent made up counts for nothing.
  - Rollback needs a deployment its system of record reported at confidence 1.0, within the last
    hour, with something to roll back to, and no inferred cause among the facts.

### Where the two issues disagreed

| Question | #30 | #95 | Decided |
| --- | --- | --- | --- |
| Refusal body | `{error: "forbidden"}` | `{error: "policy denied", policy, reason}` | #95's; scope refusals keep theirs |
| Who may read | by role | "any authenticated principal" | `graph:read`, as today: dropping it would widen access for a token without it |
| May a service delete? | not stated | no | Yes by default (`service_may_delete: true`), because connectors retire what their source stops reporting. One line of data turns it off |
| Where governance is documented | not stated | `SECURITY.md` | `docs/GOVERNANCE.md`, beside AUTH.md. `SECURITY.md` is GitHub's place for reporting vulnerabilities |
| Unreachable OPA | "deny writes, document reads" | not stated | Moot in-process. A failed evaluation refuses everything |
| Person to team membership | `Person MEMBER_OF Team` | not stated | Teams come from the token's groups claim. A `Person` type is left for when people are modelled |
| Explaining decisions | for any subject | not stated | Only the caller's own. Asking about others would tell one subject what another may do |

## Consequences

- One decision path serves REST and GraphQL, and will serve MCP. A rule is written once, in Rego,
  and tested with `opa test`. The hooks and CI run `fmt`, `test` and `check`.
- Every request pays tens of microseconds for a decision, plus one filter evaluation per
  node-returning read. The policy is compiled once per JVM.
- Changing the policy means editing `policy/`, running `node scripts/opa.mjs build` and committing
  the bundle. The pre-commit hook refuses a stale bundle.
- The OPA version is pinned in one place, `scripts/opa.mjs`, by version and digest. The compose
  profile uses the same image. The policy is compiled with `opa-java-wasm`'s supported built-ins,
  which exclude a few, such as `time.parse_rfc3339_ns`. The API therefore hands the policy ages as
  minutes rather than timestamps.
- Roles and clearances are data (`policy/sdlc/config/data.json`), so adding a role does not change
  any Rego.
