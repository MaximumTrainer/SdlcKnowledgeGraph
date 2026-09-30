Feature: the development bypass for authentication
  Local work without Keycloak, and an instance that has no identity provider yet, run with
  AUTH_DISABLED=true (#114, FR-5). Every request is then anonymous, and a write says so in its
  provenance. The flag is refused outright in production, so it can never be how a real deployment
  runs.

  This suite runs with the bypass on; the behaviour behind the login is in auth/authentication.feature.

  Scenario: the dev bypass is loud and not for prod
    Given AUTH_DISABLED=true and profile prod
    When the backend starts
    Then startup fails with a message containing "AUTH_DISABLED"

  Scenario: the dev bypass is allowed outside prod
    Given AUTH_DISABLED=true and profile docker
    When the backend starts
    Then startup does not fail on AUTH_DISABLED

  Scenario: authentication on without an issuer refuses to start
    Given AUTH_DISABLED=false, no issuer and profile docker
    When the backend starts
    Then startup fails with a message containing "AUTH_ISSUER_URI"

  Scenario: a write through the bypass is recorded as anonymous
    When I POST a node of type "Team" with name "platform"
    Then the response status is 201
    And the created node's provenance has writtenBy "anonymous" and principalType "user"
