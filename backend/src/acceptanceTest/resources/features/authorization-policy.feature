Feature: one authorisation policy decides every request
  Every request is put to one policy as a subject, an action and a resource (#30, #95, ADR-0020).
  The policy is Rego, compiled to WebAssembly and evaluated inside the API, so it decides the same
  way on every instance with nothing else to run.

  The shipped policy grants exactly what the scopes always granted. Roles narrow that, for a token
  that carries them; owning a node lets a team's members curate it; and each node type and property
  carries a sensitivity label, so a reader sees only what they are cleared for.

  Scenario: the policy in force is published
    When I GET "/api/v1/policy"
    Then the response status is 200
    And the body field "revision" is "sdlc-authz-1.0.0"
    And the body field "engine" is "embedded-wasm"
    And the body field "failMode" is "closed"

  Scenario: a caller whose token carries no roles keeps everything its scopes allow
    When "dan" with scopes "graph:read graph:write" and no roles creates a Team named "platform"
    Then the response status is 201

  Scenario: a role narrows what the scopes allow, and the refusal names the rule and why
    Given a Team named "platform" exists
    When "vera" with scopes "graph:read graph:write" and roles "viewer" renames the Team "platform"
    Then the response status is 403
    And the body field "error" is "policy denied"
    And the body field "policy" is "roles"
    And the body field "reason" is "the role viewer may not update Team"

  Scenario: a member of a team that owns a repository may curate it whatever their role
    Given a Team named "payments" exists
    And the repository "https://github.com/acme/payments" is owned by the Team "payments"
    When "vera" with scopes "graph:read graph:write", roles "viewer" and groups "/payments" changes the default branch of "github.com/acme/payments"
    Then the response status is 200

  Scenario: a property above the reader's clearance is redacted, and named
    Given a Team named "platform" with email "platform@acme.example" exists
    When "vera" with scopes "graph:read" and roles "viewer" reads the Team "platform"
    Then the response status is 200
    And the node has no property "email"
    And the node's redacted properties are "email"
    When "dan" with scopes "graph:read" and no roles reads the Team "platform"
    Then the node's property "email" is "platform@acme.example"

  Scenario: a type above the reader's clearance is not shown at all
    When "vera" with scopes "graph:read" and roles "viewer" lists the service principals
    Then the response status is 403
    And the body field "policy" is "sensitivity"

  Scenario: a caller may ask the policy what it would decide for them
    When "vera" with scopes "graph:read graph:write" and roles "viewer" asks the policy to explain "update" on "Repository"
    Then the response status is 200
    And the body field "allow" is "false"
    And the body field "policy" is "roles"
    And the body field "clearance" is "internal"

  Scenario: an agent may not roll back on a cause a rule only inferred
    Given a deployment "payments-2" to "production" reported 10 minutes ago, after "payments-1"
    And an incident "INC1" inferred at confidence 0.6 to be caused by "payments-2"
    When an agent asks the policy whether it may "rollback" on "payments-2" and the cause of "INC1"
    Then the response status is 200
    And the body field "allow" is "false"
    And a reason is "inferred cause: CAUSED_BY:Incident:INC1>Deployment:payments-2 was inferred by a rule at confidence 0.6, not reported by its system of record"

  Scenario: an agent may roll back a recent deployment its system of record reported
    Given a deployment "payments-2" to "production" reported 10 minutes ago, after "payments-1"
    When an agent asks the policy whether it may "rollback" on "payments-2"
    Then the response status is 200
    And the body field "allow" is "true"
