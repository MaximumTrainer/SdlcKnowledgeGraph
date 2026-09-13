<!-- GENERATED FROM docs/adr/0005-auth-oidc-github-first.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0005: OIDC-based identity, with GitHub as the first provider

## Status

Accepted, not yet implemented. The work is [#3](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/3) (resource server, principal
kinds, API keys) and [#2](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/2) (user login), both in milestone M2. Until they land the
API and the web interface have no authentication at all.

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
