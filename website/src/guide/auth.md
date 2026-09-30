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
same gate. There are no scopes yet: what one principal may do, any may. With the development bypass
(`AUTH_DISABLED=true`) none of this applies: every caller is `anonymous`, a user, and nothing is
refused.

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
has, besides the web interface's public client `sdlc-ui` and the user `dan` (password `dan`), three
confidential clients with the client-credentials grant:

| Client | Secret | Purpose |
| --- | --- | --- |
| `github-connector` | `github-connector-dev-only` | An example connector |
| `triage-agent` | `triage-agent-dev-only` | An example agent |
| `rogue-agent` | `rogue-agent-dev-only` | A client nobody registers, to see the 403 |

The secrets are development values that exist only in that realm, which no deployment imports. None
of the clients is registered when the stack starts: register them as `dan` first.

## What stays as it was

- The ingest endpoints (`/api/v1/ingest/...`) keep their own shared bearer token, `INGEST_TOKEN`,
  which is never decoded as a JWT. Their callers - the deploy pipeline and the seed job - could
  become service principals now, and their writes would then name them; that is a separate change.
- A scheduled connector run inside the application writes as no principal: nobody asked for it.
- Agents are `service` principals like connectors. An agent acting for a particular user (token
  exchange, so that the agent can see no more than that user) is an open question in ADR-0005, not
  part of this.
