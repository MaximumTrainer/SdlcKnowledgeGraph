Feature: agents and connectors are named principals
  The second slice of authentication (#115, ADR-0005). A connector or an agent signs in with the
  OAuth 2 client-credentials grant, and its token passes the same gate a user's does - but only once
  a user has registered the client as a service principal, owned by a team. A write it makes records
  its registered name, principalType "service" and the team it acts for. A client Keycloak trusts but
  the graph has no registration for is refused, whatever it asks for.

  The development realm has three confidential clients: github-connector and triage-agent, the
  examples the e2e stack ships, and rogue-agent, which nothing registers.

  Scenario: a registered agent writes with its own identity
    Given service principal "triage-agent" is registered with ownedBy "team-payments"
    And "triage-agent" holds a client-credentials token
    When it POSTs a Deployment node
    Then the response is 201
    And the node's provenance has writtenBy "triage-agent", principalType "service", onBehalfOfTeam "team-payments"

  Scenario: a registered agent reads through the same gate as a user
    Given service principal "github-connector" is registered with ownedBy "team-platform"
    And "github-connector" holds a client-credentials token
    When it calls GET /api/v1/nodes/Repository
    Then the response is 200

  Scenario: an unregistered client is refused
    Given Keycloak client "rogue-agent" holds a valid token
    And no service principal "rogue-agent" is registered
    When it calls GET /api/v1/nodes/Repository
    Then the response is 403 with error "unregistered service principal"
    And the refusal names client "rogue-agent"

  Scenario: an unregistered client is refused on GraphQL too
    Given Keycloak client "rogue-agent" holds a valid token
    When it sends the GraphQL query "{ repositories { id } }"
    Then the response is 403 with error "unregistered service principal"

  Scenario: only users can register principals
    Given service principal "triage-agent" is registered with ownedBy "team-payments"
    And "triage-agent" holds a token
    When it POSTs /api/v1/service-principals
    Then the response is 403
    And the error is not "unregistered service principal"

  Scenario: a user registers and lists service principals
    Given "dan" has signed in through the UI
    And team "team-platform" exists
    When "dan" registers service principal "github-connector" with ownedBy "team-platform"
    Then the response is 201
    And the service principals listed include "github-connector" owned by "team-platform", registered by "dan"

  Scenario: a service principal is owned by a team the graph knows
    Given "dan" has signed in through the UI
    When "dan" registers service principal "github-connector" with ownedBy "team-nobody"
    Then the response is 400

  Scenario: a deregistered client is refused like an unregistered one
    Given service principal "triage-agent" is registered with ownedBy "team-payments"
    And "triage-agent" holds a client-credentials token
    When "dan" deregisters service principal "triage-agent"
    Then the response is 200
    And the service principals listed include "triage-agent" with a validTo
    When it calls GET /api/v1/nodes/Repository
    Then the response is 403 with error "unregistered service principal"
