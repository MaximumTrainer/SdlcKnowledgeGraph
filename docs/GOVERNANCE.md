# Governance: who may do what, and what an agent may do alone

Every request to the API is decided by one authorisation policy, written in Rego under
[`policy/`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/tree/main/policy)
([#30](../../issues/30), [#95](../../issues/95)). The API compiles the policy to WebAssembly and
evaluates it in its own process, so no Open Policy Agent server has to run beside it
([ADR-0020](adr/0020-one-authorisation-policy-evaluated-in-process.md)). REST and GraphQL ask the
same question, and so will the MCP server ([#31](../../issues/31)).

[AUTH.md](AUTH.md) covers how a caller is identified: tokens, scopes and service principals. This
page covers what the policy does with that identity.

## The question the policy answers

Each request is asked as a **subject**, an **action** and a **resource**:

| | Read from |
| --- | --- |
| Subject | The token. A user is its `sub`; a registered service principal is its name, and its `kind` is `service` or `agent`. With no identity provider, the caller is `anonymous`. The subject also carries the token's `graph:` scopes, its roles (the `sdlc_roles` claim) and its teams (the `groups` claim; a service principal also belongs to the team that owns it). |
| Action | The route: `read` (`GET`), `query` (a read sent as a POST, such as impact or a context pack), `create`, `update`, `delete` (by method), `link` (a relationship), `sync` (a connector run) or `admin` (anything needing `graph:admin`). A GraphQL document is asked about each thing it does. |
| Resource | A node type, or one node, where the path names it (`/api/v1/nodes/{type}/{key}`). Otherwise an edge, an endpoint, or the policy itself. |

The answer is allowed or not, the rule that decided, why, and, for a read, which properties to
take out. The rules are checked in a fixed order, and the first rule that refuses names the
refusal:

1. **scopes**: what the token must hold for the action. These are the scopes in [AUTH.md](AUTH.md#scopes), unchanged.
2. **provenance.confidence**: a write naming a source other than `manual` is recorded at
   confidence 1.0, which claims to be that system of record, so it needs `graph:write:<source>`
   ([#117](../../issues/117)).
3. **agents**: an agent may never `admin`.
4. **services.delete**: a service or agent may not delete. This rule is off by default
   (`service_may_delete: true`), because connectors retire what their source stops reporting.
5. **roles**: what the subject's roles permit. Applies only to a token that carries roles.
6. **sensitivity**: whether the subject is cleared for the type.

## Refusals

A missing scope is the refusal it has always been:

```json
403 {"error": "insufficient scope", "required": ["graph:write"], "held": ["graph:read"]}
```

Any other rule names itself and says why:

```json
403 {"error": "policy denied", "policy": "roles", "reason": "the role viewer may not update Repository"}
403 {"error": "policy denied", "policy": "agents", "reason": "agents may not perform admin actions"}
403 {"error": "policy denied", "policy": "sensitivity", "reason": "ServicePrincipal is restricted, above what the subject is cleared for (internal)"}
```

A policy that cannot be evaluated refuses the request, whether it reads or writes:

```json
503 {"error": "policy_unavailable"}
```

Each refusal is logged as a security event: `scope.refused`, `policy.denied`, or
`policy.unavailable` ([OBSERVABILITY.md](OBSERVABILITY.md)). The web interface shows a
`policy denied` refusal in a banner that names the rule and the reason.

## The default policy changes nothing

The policy ships granting exactly what the scopes always granted. A token without the roles claim,
which is every token the development realm and today's deployments issue, is judged by its scopes
alone. It is also cleared for every sensitivity level, so it sees everything it saw before.
`DefaultPolicyParityTest` checks every mapped route against every combination of scopes to hold the
policy to that.

## Roles

A token that carries the `sdlc_roles` claim (set by `SDLC_POLICY_ROLES_CLAIM`) is judged by its
scopes and by its roles. A role narrows what the scopes allow and never widens it: a viewer holding
`graph:write` still may not write. A token whose only roles are ones the policy does not know is
refused.

| Role | Actions | Cleared for |
| --- | --- | --- |
| `viewer` | read, query | internal |
| `curator` | read, query, create, update, link | internal |
| `operator` | the curator's, and sync | confidential |
| `admin` | everything | restricted |
| `agent-reader` | read, query | internal |
| `agent-curator` | read, query, link | internal |

The roles are data, not Rego
([`policy/sdlc/config/data.json`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/policy/sdlc/config/data.json)).
Users, services and agents are given them the same way. In Keycloak, the development realm makes
them client roles of `sdlc-ui` and maps them into `sdlc_roles` with a client-role mapper. Its
`viewer` user (password `viewer`) carries `viewer`.

### Owning a node

A member of a team that owns a node may act on it as a curator, whatever their global role. The
subject's teams come from the token's `groups` claim (set by `OIDC_GROUPS_CLAIM`), read as team keys:
Keycloak's leading `/` is dropped and the name is lower-cased, so `/Payments` is the team
`payments`.

A node is owned by the teams it is `OWNED_BY`. A cloud resource with no owner of its own is owned by
the teams that own the repository or service that `OWNS_RESOURCE` it, and a team owns itself. The
owners are read only when they could change the answer: when roles refused the request, the caller
belongs to a team, and the request is about one node.

## Sensitivity

Every node type has a label in the [ontology registry](ONTOLOGY.md#sensitivity): `public`,
`internal`, `confidential` or `restricted`, with `internal` for a type that declares none. A
property is labelled where it is more sensitive than its type, and an edge is as sensitive as the
more sensitive type it joins. The labels in 1.10.0 are:

| What | Label |
| --- | --- |
| `ServicePrincipal` | restricted |
| `Team.email` | confidential |
| `Incident.shortDescription` | confidential |
| everything else | internal |

For a reader with a lower clearance:

- **A node of a type above their clearance is hidden.** Reading it directly is refused
  (`policy: sensitivity`). A list, a node's relationships, or a neighbourhood leaves it out, and a
  neighbourhood counts what it left out in `truncatedByPolicy`. In GraphQL, `node` reads it as null
  and `edges` leaves it out.
- **A property above their clearance is taken out** and named in the node's `redacted`, so a
  reader can tell a redacted value from one that was never recorded.
- **A read that cannot be filtered node by node** (impact, change impact, context packs, lineage,
  and GraphQL beyond `node` and `edges`) is answered only to a reader cleared for everything the
  registry labels.

Each filtered read that hid or redacted anything is logged as `policy.redacted`. The event names
the subject, how many nodes were hidden and which properties were taken out, never their values.

## Asking the policy

| | |
| --- | --- |
| `GET /api/v1/policy` | The policy in force: `revision` (from the bundle's `.manifest`), `loadedAt`, `engine: embedded-wasm`, `status: UP` and `failMode: closed` |
| `POST /api/v1/policy/explain` | `{"action": "update", "resource": {"type": "Repository", "key": "github.com/acme/payments"}}`: what the policy decides for the caller, by which rule and why, the caller as the policy sees them (kind, scopes, roles, teams) and their clearance. `key` is optional, and with one the caller's ownership counts |
| `POST /api/v1/policy/evaluate` | The agent-actions policy, below |

Both POSTs only read and need `graph:read`. They only explain the caller's own access: no request
tells one subject what another may do. The web interface's **Policy** page (`/admin/policy`) shows
the policy in force and asks `explain` for the signed-in user.

## The agent-actions policy

An agent asks whether it may take an action on its own by naming the action and the graph facts
it relies on:

```http
POST /api/v1/policy/evaluate
{"action": "rollback", "facts": ["Deployment:payments-2", "CAUSED_BY:Incident:INC1>Deployment:payments-2"]}
```

A fact is a node id (`Type:key`) or an edge id as the graph view writes it (`TYPE:from>to`). The
API looks each one up and gives the policy what the graph holds, never what the agent says:

- whether the fact exists;
- its confidence, and whether a rule inferred it;
- how many minutes ago its source observed it (a deployment's age counts from when it was
  deployed);
- for a deployment, its environment and whether a different artifact went there before it.

The answer is every reason the policy refused, or the facts that satisfied it:

```json
{
  "allow": false,
  "policy": "agent-actions",
  "action": "rollback",
  "reasons": [
    "inferred cause: CAUSED_BY:Incident:INC1>Deployment:payments-2 was inferred by a rule at confidence 0.6, not reported by its system of record"
  ],
  "facts": [ ... ],
  "subject": { "id": "incident-bot", "kind": "agent", ... }
}
```

Rollback is allowed only when all of these hold:

- every fact is in the graph;
- one of them is a Deployment;
- no fact was inferred by a rule;
- the deployment was reported at confidence 1.0;
- the deployment was observed within the last 60 minutes (`agent_actions.rollback.max_age_minutes`);
- a different artifact was deployed to the same environment before it.

An action the policy has no rules for is refused, so a new agent action is refused until a rule is
written for it. Adding one means adding `agent_actions.<action>` to the config data and its rules to
[`agent_actions.rego`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/policy/sdlc/agent_actions/agent_actions.rego),
with Rego unit tests beside them.

## Agents

An agent is a service principal registered with `"kind": "agent"`
([AUTH.md](AUTH.md#registering-one)); one registered without a kind is a `service`. An agent is
judged by the same rules a user with the same roles and scopes meets, with one more: it may never
`admin`, whatever its scopes. A rise in `sdlc_authz_decisions_total{kind="agent",decision="deny"}`
is an agent trying what it may not.

## Changing the policy

The Rego is under `policy/sdlc/`: `authz/` for the request decision, `agent_actions/` for the
agent-actions policy, and `config/data.json` for the roles, clearances and limits. The
sensitivity labels are generated into `ontology/data.json` from the registry by
`./gradlew generateOntology`.

```bash
node scripts/opa.mjs fmt     # the Rego is formatted as opa fmt writes it
node scripts/opa.mjs test    # every Rego unit test passes
node scripts/opa.mjs build   # compile to backend/src/main/resources/policy/bundle.tar.gz
node scripts/opa.mjs check   # the committed bundle is what the Rego compiles to
```

OPA runs from an image pinned by version and digest (`scripts/opa.mjs`), so nobody installs it.
The pre-commit hook checks `fmt` and `check`, the pre-push hook runs `test`, and CI runs all three.
Commit the rebuilt bundle with the Rego that changed it. The bundle's `.manifest` names its
revision, which `GET /api/v1/policy` reports. Change the revision when the rules change.

To explore the policy, `docker compose --profile governance up -d opa` serves `policy/` on port
8181:

```bash
curl -s localhost:8181/v1/data/sdlc/authz/decision -d '{"input": {"subject": {"kind": "user",
  "scopes": ["graph:read", "graph:write"], "roles": ["viewer"]}, "action": "update",
  "resource": {"kind": "node", "type": "Repository"}}}'
```

A deployment can enforce another bundle, built by the same command, without a rebuild by setting
`SDLC_POLICY_BUNDLE` to its path ([DEPLOYMENT.md](DEPLOYMENT.md)). A bundle the API cannot load
stops it at startup.
