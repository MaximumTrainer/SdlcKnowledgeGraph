Feature: Impact analysis for a change
  A coding agent asks before it edits: "I am about to change this repository - what runs on it, in
  which environments, who owns each of those things, and which matters most?" (#87). What it gets
  back feeds a deterministic, budget-bounded brief, so the answer is ranked, bounded and stable: the
  same request gives the same bytes, and every hit cites the facts that put it there.

  The walk is #21's blast radius: the edges the ontology flags `impact: propagates`, read the way a
  change travels. Each hit is scored 1 / (1 + hops), weighted by the criticality of the environment
  it runs in (Environment.tier), and boosted when a requested path names an IaC file that names it.

  Background:
    Given a Repository "github.com/acme/payments" owned by Team "billing"
    And a Service "checkout" that DEPENDS_ON Repository "github.com/acme/payments" and is owned by Team "storefront"
    And an Artifact from "github.com/acme/payments" deployed to Environment "production" with tier "production"
    And the same Artifact deployed to Environment "staging" with tier "pre_production"

  Scenario: Production outranks staging at the same distance
    When I POST /api/v1/impact with repositoryKey "github.com/acme/payments" and depth 2
    Then the response status is 200
    And the first hit is the "production" deployment
    And the "staging" deployment appears after it
    And every hit has a non-empty "citation.edgePath"
    And the scoring version is "1"

  Scenario: Owners are resolved on every hit
    When I POST /api/v1/impact with repositoryKey "github.com/acme/payments"
    Then the hit for Service "checkout" lists owner "storefront"
    And the hit for the "production" deployment lists owner "billing"

  Scenario: Identical requests are byte-identical
    When I POST /api/v1/impact twice with the same body
    Then both response bodies are identical

  Scenario: Depth is bounded
    When I POST /api/v1/impact with depth 9
    Then the response status is 400
    And the problem detail mentions "depth"

  Scenario: The limit is bounded
    When I POST /api/v1/impact with limit 501
    Then the response status is 400
    And the problem detail mentions "limit"

  Scenario: A repository the graph does not hold is not found
    When I POST /api/v1/impact with repositoryKey "github.com/acme/nothing"
    Then the response status is 404

  Scenario: A path filter that cannot be evaluated says so
    Given the repository has no manifest or IaC index
    When I POST /api/v1/impact with paths ["src/billing/invoice.kt"]
    Then the response status is 200
    And "pathFilter" is "not_applied"
    And the body has 4 hits and "truncated" is false

  Scenario: A path that names an IaC file boosts what that file names
    Given the repository holds IaC file "infra/db.tf" naming "arn:aws:rds:eu-west-1:1:db:payments"
    And the repository OWNS_RESOURCE CloudResource "arn:aws:rds:eu-west-1:1:db:payments"
    When I POST /api/v1/impact with paths ["infra/db.tf"]
    Then the response status is 200
    And "pathFilter" is "applied"
    And the matched paths are "infra/db.tf"
    And the first hit is CloudResource "aws:arn:aws:rds:eu-west-1:1:db:payments" with pathMatched true

  Scenario: A sha no Change in the graph carries cannot scope the answer
    When I POST /api/v1/impact with sha "4f1c2d9"
    Then the response status is 200
    And "changeScope" is "unknown"
    And the body has 4 hits and "truncated" is false

  Scenario: A sha restricts deployments to those whose artifact contains the change
    # #85 made changes part of the graph: an Artifact CONTAINS a Change, identified in its repository
    # by its sha. An abbreviated sha finds the Change it begins, as git would.
    Given the Artifact deployed to "production" CONTAINS Change "4f1c2d9e0a" in "github.com/acme/payments"
    And another Artifact from "github.com/acme/payments" deployed to Environment "development" with tier "development"
    When I POST /api/v1/impact with sha "4f1c2d9"
    Then the response status is 200
    And "changeScope" is "applied"
    And the first hit is the "production" deployment
    And the "staging" deployment appears after it
    And no hit is the "development" deployment

  Scenario: Results are truncated honestly
    Given 60 services DEPENDS_ON the repository
    When I POST /api/v1/impact with limit 50
    Then the body has 50 hits and "truncated" is true

  Scenario: The same ranking through GraphQL
    When I query GraphQL for the change impact of "github.com/acme/payments"
    Then the GraphQL change impact's first hit is the "production" deployment
