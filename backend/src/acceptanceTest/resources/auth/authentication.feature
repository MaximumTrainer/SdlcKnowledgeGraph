Feature: the graph is behind a login
  The first slice of authentication (#114, ADR-0005). A user signs in to the web interface through
  OIDC, the API refuses anything under /api and /graphql that does not carry a valid token, and every
  write records who made it. There are no scopes yet: any signed-in user may do what an anonymous
  caller could before. The probes, /actuator/info and the scraper's /actuator/prometheus stay public,
  and the ingest endpoints keep their own bearer token.

  These scenarios run against their own application context with authentication on, and a Keycloak
  in Testcontainers loaded from the development realm the compose stack uses.

  Scenario: no token, no data
    Given the backend is running with the auth profile
    When GET /api/v1/nodes/Repository is called without a token
    Then the response is 401

  Scenario: a valid token reads
    Given "dan" has signed in through the UI
    When GET /api/v1/nodes/Repository is called with that token
    Then the response is 200

  Scenario: a token the issuer did not sign is refused
    Given the backend is running with the auth profile
    When GET /api/v1/nodes/Repository is called with a forged token
    Then the response is 401

  Scenario: GraphQL is behind the login too
    Given the backend is running with the auth profile
    When the GraphQL query "{ repositories { id } }" is sent without a token
    Then the response is 401

  Scenario: a signed-in user reads and writes
    Given "dan" has signed in through the UI
    When the UI creates a Repository
    Then the response is 201
    And the node's provenance has writtenBy "dan" and principalType "user"

  Scenario: a signed-in user's relationship records who made it
    Given "dan" has signed in through the UI
    When the UI relates a Repository to a Team
    Then the response is 201
    And the edge's provenance has writtenBy "dan" and principalType "user"

  Scenario: health stays public
    When GET /actuator/health is called without a token
    Then the response is 200

  Scenario Outline: the operational endpoints stay public
    When GET <path> is called without a token
    Then the response is 200

    Examples:
      | path                        |
      | /actuator/health/liveness   |
      | /actuator/health/readiness  |
      | /actuator/info              |
      | /actuator/prometheus        |

  Scenario: the ingest endpoints keep their own token
    When POST /api/v1/ingest/seed is called with the ingest token and no OIDC token
    Then the response is not 401
