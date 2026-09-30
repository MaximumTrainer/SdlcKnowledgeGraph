<!-- GENERATED FROM docs/adr/0005-auth-oidc-github-first.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0005: OIDC-based identity, with GitHub as the first provider

## Status

Accepted, amended by [#114](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/114) (AUTH-1, below), which is the first slice to be
built. The rest of the sequence - machine principals (AUTH-2), scopes (AUTH-3) and making the login
the default (AUTH-5) - elaborates that slice without changing its shape. The original work items are
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
which keep their own bearer token until pipelines are principals of their own.

There are no scopes and no 403s yet: any valid token may do anything an anonymous caller could before.

**The development bypass.** `AUTH_DISABLED=true` lets every request through and records its writes
as `writtenBy: anonymous`. It logs a security warning (`auth.disabled`) on every start and is refused
at startup under any profile named `prod`. The default compose stack and the dogfood instance run
with it, since neither has an identity provider yet; the dogfood instance is also read-only, which is
what keeps it safe ([Deployment](/guide/deployment), D4).

### Placeholder: machine principals (AUTH-2)

Connectors, the deploy pipeline and agents are not principals yet. Their writes carry no `writtenBy`,
and the ingest endpoints keep a shared bearer token. AUTH-2 decides how they become principals -
client-credentials tokens from the same issuer, backend-issued API keys stored hashed, or both - and
what `principalType` each records (`service`, `agent`). This section is to be replaced by that
decision.
