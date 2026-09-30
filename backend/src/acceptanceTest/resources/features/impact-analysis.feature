Feature: Impact analysis and blast radius
  The two questions a minimum viable graph must answer first are "what depends on this change?" and
  "why did the deployment fail?" (#21). Both are multi-hop walks, and both are only worth trusting
  when the answer says how it got there: every affected node carries the path that reached it and a
  confidence, the product of the provenance confidence of the edges on that path.

  Which edges a change travels along is declared in the ontology registry (`impact: propagates`),
  never in the query, so a relationship added to the registry joins the blast radius without a code
  change.

  Background:
    Given Repository "github.com/acme/shared-lib" owned by Team "platform"
    And Repository "github.com/acme/payments" DEPENDS_ON "github.com/acme/shared-lib" with confidence 1.0
    And Repository "github.com/acme/checkout" DEPENDS_ON "github.com/acme/payments" with confidence 0.95
    And Repository "github.com/acme/payments" owned by Team "payments-team"
    And Repository "github.com/acme/payments" OWNS_RESOURCE CloudResource "aws:arn:aws:rds:eu-west-1:1:db:payments" with confidence 0.4 inferred

  Scenario: What depends on this change
    When I GET "/api/v1/graph/impact?nodeId=Repository:github.com/acme/shared-lib&depth=3"
    Then the response status is 200
    And the impact root is "Repository:github.com/acme/shared-lib"
    And affected contains "Repository:github.com/acme/payments" at distance 1 with confidence 1.0
    And affected contains "Repository:github.com/acme/checkout" at distance 2 with confidence 0.95
    And the path to "Repository:github.com/acme/checkout" is "DEPENDED_ON_BY, DEPENDED_ON_BY"
    And affected does not contain "CloudResource:aws:arn:aws:rds:eu-west-1:1:db:payments"
    And byType.Repository equals 2
    And byType.excluded equals 1

  Scenario: Low-confidence inferred edges are included when asked
    When I GET "/api/v1/graph/impact?nodeId=Repository:github.com/acme/shared-lib&minConfidence=0.3"
    Then the response status is 200
    And affected contains "CloudResource:aws:arn:aws:rds:eu-west-1:1:db:payments" with inferred true and confidence 0.4
    And the path to "CloudResource:aws:arn:aws:rds:eu-west-1:1:db:payments" is "DEPENDED_ON_BY, OWNS_RESOURCE"

  Scenario: Upstream reads the same edges the other way
    When I GET "/api/v1/graph/impact?nodeId=Repository:github.com/acme/checkout&direction=upstream"
    Then the response status is 200
    And affected contains "Repository:github.com/acme/shared-lib" at distance 2 with confidence 0.95
    And the path to "Repository:github.com/acme/shared-lib" is "DEPENDS_ON, DEPENDS_ON"

  Scenario: Why did the deployment fail
    Given Artifact "ghcr.io/acme/payments@sha256:aaa" BUILT_FROM "github.com/acme/payments" at commit "c1" deployed to "staging" with status "SUCCESS" at "2026-09-01T10:00:00Z"
    And Artifact "ghcr.io/acme/shared-lib@sha256:bbb" BUILT_FROM "github.com/acme/shared-lib" at commit "s9" deployed to "staging" with status "SUCCESS" at "2026-09-02T09:00:00Z"
    And Artifact "ghcr.io/acme/payments@sha256:ccc" BUILT_FROM "github.com/acme/payments" at commit "c2" deployed to "staging" with status "FAILED" at "2026-09-02T10:00:00Z"
    When I ask why the last deployment failed
    Then the response status is 200
    And status is "FAILED" and commitSha is "c2"
    And precedingSuccessfulDeployment.commitSha is "c1"
    And changedDependencies contains "github.com/acme/shared-lib" with commitSha "s9"

  Scenario: A deployment that succeeded has nothing to explain
    Given Artifact "ghcr.io/acme/payments@sha256:aaa" BUILT_FROM "github.com/acme/payments" at commit "c1" deployed to "staging" with status "SUCCESS" at "2026-09-01T10:00:00Z"
    When I ask why the last deployment failed
    Then the response status is 200
    And status is "SUCCESS" and commitSha is "c1"
    And there are no reasons

  Scenario: Asking about a deployment that does not exist
    When I GET "/api/v1/graph/why-failed?deploymentId=Deployment:nothing-here"
    Then the response status is 404

  Scenario: Who owns a cloud resource
    When I GET "/api/v1/graph/owners?nodeId=CloudResource:aws:arn:aws:rds:eu-west-1:1:db:payments"
    Then the response status is 200
    And owners contains Team "payments-team" via "OWNED_BY_REPO, OWNED_BY" with confidence 0.4
    And owners does not contain Team "platform"

  Scenario: A node nobody owns has no owners, not a 404
    When I GET "/api/v1/graph/owners?nodeId=Repository:github.com/acme/checkout"
    Then the response status is 200
    And there are no owners

  Scenario: Invalid depth is rejected
    When I GET "/api/v1/graph/impact?nodeId=Repository:github.com/acme/shared-lib&depth=9"
    Then the response status is 400 and field is "depth"

  Scenario: A malformed node id is rejected
    When I GET "/api/v1/graph/impact?nodeId=not-a-node-id"
    Then the response status is 400 and field is "nodeId"

  Scenario: A node id that resolves to nothing is not found
    When I GET "/api/v1/graph/impact?nodeId=Repository:github.com/acme/nothing"
    Then the response status is 404

  Scenario: The same blast radius through GraphQL
    When I query GraphQL for the impact of "Repository:github.com/acme/shared-lib" with minConfidence 0.3
    Then the GraphQL impact lists "Repository:github.com/acme/checkout" as a "RepositoryNode" with confidence 0.95
    And the GraphQL impact lists "CloudResource:aws:arn:aws:rds:eu-west-1:1:db:payments" as a "CloudResourceNode" with confidence 0.4

  Scenario: Why a deployment failed, through GraphQL
    Given Artifact "ghcr.io/acme/payments@sha256:aaa" BUILT_FROM "github.com/acme/payments" at commit "c1" deployed to "staging" with status "SUCCESS" at "2026-09-01T10:00:00Z"
    And Artifact "ghcr.io/acme/payments@sha256:ccc" BUILT_FROM "github.com/acme/payments" at commit "c2" deployed to "staging" with status "FAILED" at "2026-09-02T10:00:00Z"
    When I query GraphQL for why the last deployment failed
    Then the GraphQL answer has status "FAILED" and preceding commit "c1"
