<!-- GENERATED FROM docs/adr/0005-auth-oidc-github-first.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0005: OIDC-based identity, with GitHub as the first provider

## Status

Accepted, amended by [#114](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/114) (AUTH-1, below), which is the first slice to be
built, by [#115](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/115) (AUTH-2, machine principals, below), by
[#116](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/116) (AUTH-3, scopes, below), by [#117](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/117) (AUTH-4,
source-scoped writes, below) and by [#118](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/118) (AUTH-5, the login by default, which
removes the development bypass the earlier amendments describe). The original work items are
[#3](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/3) and [#2](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/2), tracked under [#94](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/94).

## Context

The application currently has no security layer at all. Every endpoint is open, GraphiQL is enabled
unconditionally, and there is no notion of who made a change. That is untenable before connectors
start holding credentials for GitHub, ServiceNow and cloud accounts, and before the graph starts
answering questions about production infrastructure.

Three kinds of caller need to be distinguished:

- People using the web interface.
- Connectors and other machine clients running scheduled syncs.
- AI agents querying the graph for context.

The Harness article is explicit that the third category is not a special case. Access control should
apply to an AI agent exactly as it applies to the person it acts for. An agent that can read
subgraphs its user cannot read is a data leak with extra steps. That argues for one identity model
with a subject kind attached, not a separate bypass path for automation.

Because the graph is about code repositories, GitHub is the natural first identity provider: the
people who use it already have accounts, and repository ownership data is meaningful against GitHub
identities. But this is intended to be deployable inside an enterprise, where Entra ID or Okta will
be the required provider.

A complication: GitHub's OAuth 2 implementation is not OpenID Connect. It issues no id_token and
publishes no OIDC discovery document, so a pure resource-server-plus-OIDC-login design cannot treat
it as just another issuer.

## Decision

Model every caller as a subject with a `kind` of `user`, `service` or `agent`. Authorisation
decisions take the subject and the resource, never the transport.

For the browser, use Spring Security `oauth2Login` as a backend-for-frontend: the server holds the
session, sets an HTTP-only cookie, and the single-page application never handles a token. GitHub is
configured as the first registration. Because Spring supports OAuth 2 and OIDC registrations side by
side, adding Entra ID, Okta or Keycloak is configuration under
`spring.security.oauth2.client.registration`, not code.

For machine callers, run as an OAuth 2 resource server validating JWTs from a configurable issuer,
plus backend-issued API keys stored hashed for connectors and agents that cannot do an OAuth flow.

Keep a small set of endpoints public: health, the ontology document, and the API documentation.

Exempt webhook endpoints from bearer authentication, because the sending system cannot hold our
credentials, and require each connector to verify its own webhook signature instead.

Record the subject and its kind on every audit event, so it is possible to ask what an agent did.

Defer fine-grained authorisation to policy-as-code with OPA in [#30](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/30). This
decision establishes who the caller is; that one decides what they may see.

## Consequences

One identity model covers people, connectors and agents, so the agent case cannot quietly acquire
more access than the human case.

The backend-for-frontend pattern keeps tokens out of the browser, which removes a class of
cross-site scripting risk and is what makes the GitHub OAuth 2 flow workable despite the absence of
an id_token.

Switching to an enterprise provider is a configuration change and a redeploy, which is the property
an enterprise deployment needs.

Local development needs an identity provider. Keycloak runs in the compose stack under the `auth`
profile, with a dev realm, so nobody needs a GitHub OAuth app to work on unrelated features.

Sessions mean CSRF protection has to be configured properly for the single-page application, and the
frontend needs to handle a 401 by restarting the login flow rather than showing an error.

API keys are a long-lived credential. They are stored hashed, issued only to administrators, and
should be scoped and revocable. They exist because scheduled connectors and external agents cannot
always complete an interactive flow, not as a convenience.

## Amendment: AUTH-1, Keycloak-issued JWTs and the resource-server pattern (#114)

The walking skeleton changes two things in the decision above, and fixes a third.

**The API is a resource server for people too, not only for machines.** Every request under `/api`
and `/graphql` needs a bearer JWT, validated against one configured issuer (`AUTH_ISSUER_URI`, with
`AUTH_JWK_SET_URI` when the API reaches the issuer at another address than it signs as). Locally and
in the tests the issuer is Keycloak, from the compose `auth` profile, with a committed development
realm (`sdlc`, public client `sdlc-ui`, user `dan`). Any OIDC provider that issues JWTs - Entra ID,
Okta, Keycloak in front of GitHub - is the same configuration. GitHub's plain OAuth 2, which issues no
JWT, is reached through such a broker rather than registered directly.

**The web interface holds the token, instead of a backend-for-frontend session.** The single-page
application signs in with the authorization code flow and PKCE against a public client, keeps the
tokens in `sessionStorage`, renews them silently with the refresh token, and sends the access token
on every API call; a 401 restarts the login. That trades the BFF's protection of tokens from
cross-site scripting for one validation path shared by people, connectors and agents, no server-side
session, and no CSRF surface, since no cookie carries a credential. The risk it accepts is managed by
short-lived access tokens (five minutes in the development realm) and by keeping the page free of
third-party script. Whether the interface signs in at all is read at runtime from
`/auth-config.json`, so one image serves a deployment with an identity provider and one without.

**Who wrote each fact is recorded.** Every node and edge written through the API carries `writtenBy`
(the token's `sub`, which does not change when a username does) and `principalType` (`user`) in its
provenance, declared in the ontology registry's provenance envelope.

What stays public, whoever asks: the probes, `/actuator/info` and `/actuator/prometheus` (the
scraper inside the deployment holds no user token), the ontology document and the API
documentation, the webhook receivers (which verify the sender's signature), and the ingest endpoints,
which keep their own bearer token (see AUTH-2 below).

AUTH-1 had no scopes and no 403s: any valid token could do anything an anonymous caller could before.
AUTH-3 (below) adds them.

**The development bypass.** `AUTH_DISABLED=true` lets every request through and records its writes
as `writtenBy: anonymous`. It logs a security warning (`auth.disabled`) on every start and is refused
at startup under any profile named `prod`. The default compose stack and the dogfood instance run
with it, since neither has an identity provider yet; the dogfood instance is also read-only, which is
what keeps it safe ([Deployment](/guide/deployment), D4).

## Amendment: AUTH-2, machine principals (#115)

**Connectors and agents authenticate with the OAuth 2 client-credentials grant, from the same issuer
as people.** Each is a confidential client of the identity provider, and its token passes the same
gate as a user's. Backend-issued API keys, which the original decision allowed for callers that
cannot do an OAuth flow, are not built: every caller so far can do client credentials, and a second
credential type is a second thing to store, rotate and revoke. They stay available if a caller that
cannot turns up.

**A client is a principal only once a user has registered it.** `POST /api/v1/service-principals`
records a name (the client id), the key of the Team that owns it and a description; only a user may
call it, and the team must exist in the graph. Every request under `/api` and `/graphql` made with
the token of a client that has no current registration is refused with
`403 {"error": "unregistered service principal", clientId}`. So adding a client to the identity
provider is not by itself a way in, and every machine that can write has a team answering for it.
Deregistering sets the registration's `validTo` and keeps it, because what the service wrote still
names it. Registrations are `ServicePrincipal` nodes, a meta type of the ontology, whose provenance
records who registered them and for how long.

**What is a machine's token is decided by the token, not by configuration.** A token is a service's
when Keycloak issued it to a client's service account (`preferred_username` starting
`service-account-`, a prefix Keycloak reserves) or its `sub` is the client id (RFC 9068); the client
is `azp`, then `client_id`. A person's token also names a client, so that alone does not count. Other
providers' marks (Entra ID's `idtyp`, Auth0's `gty`) are added when one is deployed.

**The name is not checked against the identity provider.** Doing so would need its admin API and a
credential with rights over clients, held by the API. A registration only ever admits a token the
provider signed for a client of that name, so one whose client does not exist admits nobody.

**Writes say who and for whom.** A service principal's writes record its registered name as
`writtenBy`, `principalType: service`, and its owning team as `onBehalfOfTeam`, a new field of the
provenance envelope. Connectors and agents share the `service` kind; the original decision's
separate `agent` kind is not needed until something treats the two differently, which is AUTH-3's
scopes at the earliest.

**Unchanged.** The ingest endpoints keep their shared bearer token for now; their callers can be
moved onto client credentials as a change of their own. A scheduled connector run inside the
application writes as no principal. `AUTH_DISABLED=true` behaves as before: everyone is anonymous
and nothing is refused.

### Open question: agents acting for a user

An agent answering a person's question should see no more than that person can. Client credentials
give the agent its own identity, not the user's, so the graph cannot tell whose question it is
answering. OAuth 2 token exchange (RFC 8693) is the likely answer: the agent exchanges the user's
token for one naming both, and provenance records the user as well as the agent. With scopes in place
(AUTH-3, below) that can now mean something; it is left to a separate issue.

## Amendment: AUTH-3, scopes (#116)

**Two scopes, read and write, enforced in the API.** `graph:read` lets a principal read the graph
(every `GET` under `/api/v1`, every GraphQL query) and `graph:write` change it (every `POST`, `PUT`,
`PATCH` and `DELETE` under `/api/v1`, every GraphQL mutation). They are ordinary OAuth 2 scopes in
the token's `scope` claim (or `scp`), issued by the identity provider: in Keycloak, client scopes
gated by the realm roles `graph-reader` and `graph-writer`, so one client issues each user what their
roles allow. Enforcement is in the backend rather than a gateway, so a refusal is explained the way
every other refusal is: `403 {"error": "insufficient scope", "required": [...], "held": [...]}`.

**Declared per route family, checked by walking the routes.** The requirement lives in one table
(`ScopePolicy`) keyed by method and path pattern, not on each handler, and a test reads every mapped
handler and fails while one has no requirement and is not on the explicit public allowlist. A new
endpoint is either covered by its family or breaks the build.

**GraphQL is refused with the same HTTP 403**, before the document runs, rather than as a GraphQL
error in a 200: one refusal shape for both APIs, and no partly executed mutation. Every operation in
the document counts, and a document that cannot be shown to hold only reads needs `graph:write`.

**Refusals come in order:** no valid token (401), an unregistered client (403, AUTH-2), then missing
scopes (403). The ontology stays public, which more than satisfies "any valid token may read it".
The service principal registry needs `graph:write` to change and `graph:read` to list, on top of
AUTH-2's users-only rule. The web interface hides what the user's scopes do not allow, and shows the
refusal's reason if one slips through. `AUTH_DISABLED=true` checks no scopes.

**Out of scope:** per-source write scopes (AUTH-4, below) and anything finer, which is policy
([#95](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/95)), not scopes.

## Amendment: AUTH-4, source-scoped writes (#117)

**A write names the system it speaks for, and needs that system's scope.** A node or edge written
through the API may carry `provenance.sourceSystem`; one that names none is `manual`, as every API
write was before. Naming `manual` needs `graph:write`; naming any other source needs
`graph:write:<source>` as well. A connector granted `graph:write:github` can assert GitHub's facts and
no one else's, and a person, granted no source scope, only their own word. This is what lets a fact's
provenance be trusted as far as the principal behind it is, and it is the precondition for a
confidence policy ([#95](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/95)) and for connectors that write through the API.

**The sources are declared in the registry.** `sources.yaml`, beside the node, edge and provenance
declarations, lists every source a fact may name; it is published by `GET /api/v1/ontology`, rendered
into `ontology.json` and held there by the drift check. A write naming an undeclared source is
malformed, not unauthorised: it is refused with `400` and the list of known sources, whoever sends it
and with the development bypass on. Every source the application's own connectors and ingest
endpoints stamp is declared too, and a test fails when one is not.

**Checked in the application, refused like any missing scope.** The source is in the request body,
so the check cannot be a route rule; the node and edge services ask a port whether the principal may
write as the source, before anything touches the store, and the adapter reads the token's graph
scopes as the route check does. The refusal is the AUTH-3 one - the same `403 {"error":
"insufficient scope", "required": [...], "held": [...]}`, challenge and security event - with
`required` naming `graph:write` and the source's scope. The development bypass checks no source
scopes.

**Granted in the identity provider, not in the registry.** Each source but `manual` is a Keycloak
client scope with no role mapping, granted by assigning it to a client; a service principal may hold
several, and users are given none. The service principal registry records who owns a client, not
what it may write: the token is the authority, so there is one place to grant or revoke a source.

**Out of scope.** GraphQL mutations and deletes name no source and are unchanged; the ingest
endpoints and scheduled connector runs stamp their own source without a scope check. Rules on how
far a source's facts are trusted relative to another's are policy (#95), not scopes.

## Amendment: AUTH-5, the login by default (#118)

**The default stack signs in.** `docker compose up` starts Keycloak with the development realm, and
the API and the web interface trust it; the browser suite signs in through its login page. The
`auth` profile and `compose.auth.yaml` are gone, since there is one stack.

**The development bypass is removed.** `AUTH_DISABLED` no longer exists, and setting it stops the API
at startup with an unknown-setting error rather than being ignored. There is no anonymous principal:
a write through the API always names who made it, and one that reaches the store with nobody
authenticated fails. What the earlier amendments say the bypass does no longer applies.

**Without an identity provider, read only.** The dogfood instance has no identity provider and should
not need one to be browsed. An instance with no `AUTH_ISSUER_URI` therefore runs the anonymous
read-only mode: reads for anyone, no token decoded, every write refused, the ingest endpoints behind
their own token as before. The API starts that way only with `SDLC_READ_ONLY=true`, and refuses to
start otherwise, naming both settings - the startup refusal [#48](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/48) FR6 deferred
until authentication existed ([Deployment](/guide/deployment), D13). It logs the security event
`auth.anonymous.readonly` on every start and reports the mode on `/actuator/info`. The alternative,
an identity provider for the dogfood instance, costs a machine the free tier does not have, and buys
nothing a public, read-only instance needs.

**Tests get a principal from the test source set, not from the application.** The suites that
exercise the graph rather than the login replace the JWT decoder with one that accepts a single
test token, so the real chain runs and no production switch exists to be left on.

**Production federation is configuration.** Pointing `AUTH_ISSUER_URI` and the web interface's
`OIDC_AUTHORITY` at another provider is documented in [Authentication](/guide/auth); nothing is built per
provider.
