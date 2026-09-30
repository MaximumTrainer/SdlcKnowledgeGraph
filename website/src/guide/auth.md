<!-- GENERATED FROM docs/AUTH.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# Authentication and principals

Who may call the API, and how each write says who made it. The decisions behind this are in
[ADR-0005](/adr/0005-auth-oidc-github-first); this page is how to work with them.

There are two kinds of principal:

| Principal | Signs in with | `writtenBy` | `principalType` | `onBehalfOfTeam` |
| --- | --- | --- | --- | --- |
| A person | The authorization code flow with PKCE, through the web interface ([#114](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/114)) | The token's `sub` | `user` | absent |
| A connector or an agent | The OAuth 2 client-credentials grant, as a registered service principal ([#115](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/115)) | The registered name, which is its client id | `service` | The key of the Team that owns it |

Both present a bearer JWT from the instance's identity provider (`AUTH_ISSUER_URI`) and pass the
same gate, and what either may do is decided by the [scopes](#scopes) on its token
([#116](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/116)): `graph:read` to read the graph, `graph:write` to change it, and
`graph:write:<source>` to state facts as a system of record rather than as oneself
([#117](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/117), [below](#source-scopes)). With the development bypass
(`AUTH_DISABLED=true`) none of this applies: every caller is `anonymous`, a user, and no scope is
checked.

## Scopes

| Request | Needs |
| --- | --- |
| `GET` (and `HEAD`) under `/api/v1` | `graph:read` |
| `POST`, `PUT`, `PATCH`, `DELETE` under `/api/v1` | `graph:write` |
| A GraphQL query or subscription | `graph:read` |
| A GraphQL mutation | `graph:write` |
| A GraphQL document holding a query and a mutation | both |
| A node or edge write whose `provenance.sourceSystem` is not `manual` | `graph:write` and `graph:write:<source>` ([Source scopes](#source-scopes)) |
| `GET /api/v1/ontology`, `GET /api/v1/ontology/nodes/{type}` | nothing: public, with or without a token |
| The ingest endpoints, the webhook receivers | nothing: they have a credential of their own |

A token without what the request needs is refused before the request reaches the API:

```json
403 {"error": "insufficient scope", "required": ["graph:write"], "held": ["graph:read"]}
```

`required` is everything the request needs and `held` the `graph:` scopes the token carries (and
nothing else it carries), both sorted, so the caller can see what to ask its identity provider for.
The response also carries RFC 6750's `WWW-Authenticate: Bearer error="insufficient_scope"`
challenge, naming the scopes, and the `scope.refused` security event is logged with the principal,
the method and the scopes (never the path, which can hold a node's key).

- **GraphQL is refused the same way**, as an HTTP 403 with the same body, before the document runs,
  rather than as a GraphQL error inside a 200. A caller handles one kind of refusal whichever API it
  uses, and a mutation it may not make is never partly executed. Every operation in the document
  counts, not only the one `operationName` picks, and a document that cannot be shown to hold only
  reads (it does not parse, or is over 256 KiB) needs `graph:write` as well.
- **The ontology stays public.** #116 requires only that any valid token, whatever its scopes, can
  read it, so an agent can always discover the schema. It has been public since #114 (ADR-0005),
  which meets that and more: a token with no graph scope reads it like anyone else.
- **The order of refusals is fixed.** No valid token is a `401`; a token from a client nobody
  registered is `403 unregistered service principal`, whatever scopes it holds; only then are the
  scopes compared.
- **The service principal registry needs a scope too.** Registering or deregistering one needs
  `graph:write` and a user (a service is refused even with `graph:write`); listing them needs
  `graph:read`.
- **Scopes are read from `scope` and `scp`.** OAuth 2's `scope` claim is a space-separated string,
  which is what Keycloak issues; `scp`, an array, is what some other providers use. A token carrying
  both holds what either names.

The requirements are declared once per route family, in
[`ScopePolicy`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/backend/src/main/kotlin/com/repodatagraph/adapter/in/security/ScopePolicy.kt),
not on each handler; its public list is also what the security configuration lets in without a
token, so the two cannot disagree. Two tests keep it honest: `ScopePolicyCoverageTest` reads every
controller's mappings as Spring MVC does and fails while any route falls outside every family, and
the acceptance scenario "every route is guarded" calls every mapped route with a token holding no
graph scope and expects each one not on its explicit allowlist to refuse it. A new controller cannot
be served unguarded without one of them failing.

A path outside every family - an actuator endpoint other than the public ones, the GraphiQL page -
still needs a valid token, as it did before scopes, but no particular scope.

### Source scopes

A node or edge written through the API may say which system of record it speaks for
([#117](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/117)), so that a connector's facts carry its source rather than `manual`:

```json
POST /api/v1/nodes/CloudResource
{"props": {"provider": "aws", "resourceId": "arn:aws:s3:::acme", "resourceType": "s3-bucket", "name": "acme"},
 "provenance": {"sourceSystem": "aws"}}
```

The same `provenance` object is accepted by `PUT /api/v1/nodes/{type}/{key}` and
`POST /api/v1/edges`. It carries `sourceSystem` only: who wrote the fact, when and how sure are the
server's to record. Left out, the write is `manual`, as every write was before.

| `sourceSystem` | Needs |
| --- | --- |
| `manual`, or none | `graph:write` |
| any other declared source, such as `github` | `graph:write` and `graph:write:github` |
| a source `sources.yaml` does not declare | refused with `400`, whatever the token holds |

So a connector granted `graph:write:github` can state GitHub's facts and no one else's, and a person,
granted no source scope, can state only their own word. A write naming a source the token holds no
scope for is refused before anything is written, with the same 403, challenge and `scope.refused`
event as a missing `graph:write`:

```json
403 {"error": "insufficient scope", "required": ["graph:write", "graph:write:aws"],
     "held": ["graph:read", "graph:write", "graph:write:github", "graph:write:github-actions"]}
```

`required` is everything the write needs and `held` every graph scope the token carries. A source
nobody declared is a malformed write rather than a missing permission, so it is answered with the
declared ones, and is refused under the development bypass too:

```json
400 {"error": "unknown source system", "sourceSystem": "jira",
     "known": ["manual", "github", "github-actions", "servicenow", "aws", "dogfood-seed", "sdlc-knowledge-graph"]}
```

The sources are declared in the registry, in
[`sources.yaml`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/backend/src/main/resources/ontology/v1/sources.yaml),
and published by `GET /api/v1/ontology` under `sources`. A name is matched exactly, and is
lower-case words joined by hyphens, because it ends a scope. Which scope each connector needs is in
[Adapters](/guide/adapters#source-systems-and-write-scopes).

- **`graph:write` is still needed.** A source scope adds to it and never stands in for it: a token
  holding `graph:write:github` alone is refused by the route check, naming `graph:write`.
- **Only a write names a source.** Deleting a node or an edge names none and needs `graph:write`,
  whoever wrote what is deleted. The GraphQL mutations name none either: they always write `manual`.
- **Neither the ingest endpoints nor scheduled connector runs are checked.** The deployment and seed
  endpoints stamp their own connector's source (`github-actions`, `dogfood-seed`) and are guarded by
  `INGEST_TOKEN`; a sync the application runs itself is not a request. Every source they stamp is
  declared all the same, which `SourceSystemsIT` checks.
- **The source check runs after the request is let in**, because the source is in the body, and
  before the store is touched. An undeclared source is refused before any scope is compared.

### In the web interface

The web interface reads the scopes from the signed-in user's access token and offers only what they
allow: a user holding `graph:read` alone sees no New, Edit, Delete, relationship or Sync controls.
If a refusal gets through anyway (the token changed, or a page was reached by its address), the page
shows what the request needed and what the user holds in its usual error line. Without a login (the
development bypass) everything is offered, as before.

### Issuing them in Keycloak

`graph:read` and `graph:write` are client scopes with *Include in token scope* on. Each has a role
scope mapping, to the realm roles `graph-reader` and `graph-writer`, and Keycloak only puts a scope
with role mappings into a token when the user (or the client's service account) holds one of those
roles. So the same client, `sdlc-ui`, issues `dan` both scopes and `reader` only `graph:read`:

| Client | `graph:read` | `graph:write` | Source scopes |
| --- | --- | --- | --- |
| `sdlc-ui` | default | default | none |
| `github-connector` | default | default | `graph:write:github`, `graph:write:github-actions`, default |
| `triage-agent` | default | optional: only when the token request asks for `scope=graph:write` | none |

A default scope is in every token the client is issued; an optional one only when asked for, which
is how the same agent gets a read-only token for a task that only reads:

```bash
curl -s http://localhost:8081/realms/sdlc/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=triage-agent -d client_secret=triage-agent-dev-only
# scope: "profile email graph:read"
curl -s http://localhost:8081/realms/sdlc/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=triage-agent -d client_secret=triage-agent-dev-only \
  -d scope=graph:write
# scope: "profile graph:write email graph:read"
```

Asking for a scope a client was never given is refused by Keycloak (`invalid_scope`), not silently
dropped.

Each source but `manual` has a client scope of its own, `graph:write:<source>`, with *Include in
token scope* on and no role scope mapping: it is granted by assigning it to a client, and a client
holds it for every token it is issued. A service principal may be given several; no user is given
any, since `sdlc-ui` has none, so a person can state only `manual` facts. Keycloak is where these
grants live: registering a service principal records its owner, not its scopes, and the API reads
what a principal may do from the token it presents, never from the registry.

A realm export that declares any client scope replaces all of Keycloak's built-in ones, so the
development realm spells those out too (`basic`, which puts `sub` in the token, among them). Keep
them when adding a scope; a realm without `basic` issues tokens the API cannot attribute.

## Service principals

A client of the identity provider is not let in because the identity provider trusts it. A user has
to register it first, naming the team that answers for it; until then, and again after it is
deregistered, every request its token makes under `/api` and `/graphql` is answered

```json
403 {"error": "unregistered service principal", "clientId": "rogue-agent"}
```

and the `principal.refused` security event is logged. 403 rather than 401, because the token is good
and signing in again would get the same one: what is missing is a person's decision.

### Which tokens are a service's

A token is a service's when no user took part in issuing it. The API recognises two marks, and
nothing else:

- **Keycloak** issues a client-credentials token for the client's service account, whose
  `preferred_username` is `service-account-<client id>`. Keycloak reserves that prefix, so no person
  can sign in under it.
- **An issuer following RFC 9068** makes the token's `sub` the client id when there is no resource
  owner.

The client id is `azp`, then `client_id`, then (for a Keycloak token carrying neither) the service
account's name without its prefix. `azp` or `client_id` alone does not make a token a service's: a
person's token names the client they signed in through as well. An identity provider that marks
machine tokens some other way (Entra ID's `idtyp: app`, Auth0's `gty`) is not recognised yet, and
its machine tokens would be treated as users; add the mark to `ServiceTokens` before relying on one.

### Registering one

Only a user may register or deregister a service principal; a service that tries gets
`403 {"error": "only a user may manage service principals"}`. The owner must be the key of a Team
the graph already holds (a Team's key is its name, lower-cased), or the answer is
`400 {"error": "unknown team", "ownedBy": ...}`.

```bash
USER_TOKEN=...   # a signed-in user's access token
curl -X POST http://localhost:8080/api/v1/service-principals \
  -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name": "triage-agent", "ownedBy": "team-payments", "description": "Triages incidents"}'
```

| Request | Result |
| --- | --- |
| `POST /api/v1/service-principals` | `201` with the registration and a `Location`; `400` for a missing field, a name that cannot be a client id or an unknown team; `409` if the name is already registered; `403` for a service |
| `GET /api/v1/service-principals` | `{items: [...]}`, every registration in name order, deregistered ones with their `validTo` |
| `DELETE /api/v1/service-principals/{name}` | `200` with the registration, now with a `validTo`; `404` for a name never registered; `403` for a service |

A registration is `{name, ownedBy, description, registeredBy, validFrom, validTo}`. Deregistering
keeps the record, because the facts the service wrote still name it; the same name can be registered
again later, current from then. A registration is also a `ServicePrincipal` node in the graph, a meta
type, readable through the node API and GraphQL but written only through this API.

The name is not checked against the identity provider when it is registered: that would need the
provider's admin API and a credential for it. It does not need to be, since a registration only
ever lets in a token the provider signed for a client of that name. A registration whose client
never exists admits nobody, and shows in the listing for someone to remove.

### Setting up a client

In Keycloak, a service principal's client is a confidential OpenID Connect client with *Client
authentication* on, *Service accounts roles* on (the client-credentials grant) and every other flow
off. Its client id is the name you register. In the realm export that is:

```json
{
  "clientId": "triage-agent",
  "publicClient": false,
  "clientAuthenticatorType": "client-secret",
  "secret": "<from your secret store>",
  "serviceAccountsEnabled": true,
  "standardFlowEnabled": false,
  "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": false
}
```

Give its client the graph scopes it needs as default client scopes (or `graph:write` as an optional
one, for an agent that should usually only read), and give its service account the matching roles,
`graph-reader` and `graph-writer` (see [Issuing them in Keycloak](#issuing-them-in-keycloak)). A
connector that states its system's facts also needs that source's `graph:write:<source>`, and only
that one ([Source scopes](#source-scopes)).

The connector or agent then asks the token endpoint for a token and sends it like any other:

```bash
TOKEN=$(curl -s http://localhost:8081/realms/sdlc/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=triage-agent -d client_secret=triage-agent-dev-only \
  | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/nodes/Repository
```

What it writes records `writtenBy: "triage-agent"`, `principalType: "service"` and
`onBehalfOfTeam: "team-payments"` in its provenance, and the node's page shows the team.

### The development realm

The realm the compose `auth` profile and the acceptance suite import
([`sdlc-realm.json`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/backend/src/acceptanceTest/resources/keycloak/sdlc-realm.json))
has the web interface's public client `sdlc-ui` and three users, each with their name as password:

| User | Graph scopes | Purpose |
| --- | --- | --- |
| `dan` | `graph:read`, `graph:write` | The developer: reads, writes, registers service principals |
| `reader` | `graph:read` | A read-only user, to see what the web interface hides and the API refuses |
| `visitor` | none | A signed-in user with no graph scope: reads the ontology and nothing else |

and three confidential clients with the client-credentials grant:

| Client | Secret | Graph scopes | Purpose |
| --- | --- | --- | --- |
| `github-connector` | `github-connector-dev-only` | both, and `graph:write:github` and `graph:write:github-actions` | An example connector |
| `triage-agent` | `triage-agent-dev-only` | `graph:read`, and `graph:write` when asked for | An example agent |
| `rogue-agent` | `rogue-agent-dev-only` | none | A client nobody registers, to see the 403 |

The secrets and passwords are development values that exist only in that realm, which no deployment
imports. None of the clients is registered when the stack starts: register them as `dan` first.

## What stays as it was

- The ingest endpoints (`/api/v1/ingest/...`) keep their own shared bearer token, `INGEST_TOKEN`,
  which is never decoded as a JWT. Their callers - the deploy pipeline and the seed job - could
  become service principals now, and their writes would then name them; that is a separate change.
- A scheduled connector run inside the application writes as no principal: nobody asked for it. It
  stamps its own source without a scope check, and so do the ingest endpoints.
- Agents are `service` principals like connectors. An agent acting for a particular user (token
  exchange, so that the agent can see no more than that user) is an open question in ADR-0005, not
  part of this.
