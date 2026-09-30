# ADR-0005: OIDC-based identity, with GitHub as the first provider

## Status

Accepted, amended by [#114](../../../issues/114) (AUTH-1, below), which is the first slice to be
built, and by [#115](../../../issues/115) (AUTH-2, machine principals, below). The rest of the
sequence - scopes (AUTH-3) and making the login the default (AUTH-5) - elaborates those slices
without changing their shape. The original work items are
[#3](../../../issues/3) and [#2](../../../issues/2), tracked under [#94](../../../issues/94).

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

Defer fine-grained authorisation to policy-as-code with OPA in [#30](../../../issues/30). This
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

There are no scopes and no 403s yet: any valid token may do anything an anonymous caller could before.

**The development bypass.** `AUTH_DISABLED=true` lets every request through and records its writes
as `writtenBy: anonymous`. It logs a security warning (`auth.disabled`) on every start and is refused
at startup under any profile named `prod`. The default compose stack and the dogfood instance run
with it, since neither has an identity provider yet; the dogfood instance is also read-only, which is
what keeps it safe ([DEPLOYMENT.md](../DEPLOYMENT.md), D4).

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
token for one naming both, and provenance records the user as well as the agent. That needs scopes
(AUTH-3) to mean anything and is left to a separate issue.
