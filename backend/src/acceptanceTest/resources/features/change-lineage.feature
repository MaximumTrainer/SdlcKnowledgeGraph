Feature: Changes carry their intent through to deployments
  The two target questions are "what depends on this change" and "why did this deployment fail", and
  both cross the join between intent and operations (#85). A Change is a commit range or merge in a
  repository, a PullRequest MERGES it, an Artifact CONTAINS it and it IMPLEMENTS an ExternalWorkItem:
  the task, ticket or issue that asked for it, in the system that owns it (Chorus, Jira, Linear).

  An ExternalWorkItem is a reference, not a copy. It is identified by its URI exactly as written,
  case and all, and the graph stores that URI and enough to display it; its description, acceptance
  criteria and decisions stay authoritative where they are.

  Given a work item, the graph says which environments it is live in; given a deployment, which
  changes and which work items it carries. A deployment whose artifact has no CONTAINS edge says its
  lineage is unknown rather than that it carries nothing.

  Node ids and URIs hold slashes, which a path segment cannot carry, so both queries take them as
  query parameters, as the other traversals do.

  Scenario: A pull request implements an external work item
    Given a Repository node exists with key "github.com/acme/payments"
    And an ExternalWorkItem node exists with uri "chorus://task/01JABC" and system "chorus"
    When I POST /api/v1/nodes/Change with sha "a1b2c3", repositoryKey "github.com/acme/payments", committedAt "2026-09-13T10:00:00Z"
    And I POST /api/v1/edges with type "IMPLEMENTS" from that Change to the ExternalWorkItem
    Then the response status is 201
    And the ExternalWorkItem "chorus://task/01JABC", read back by its uri, lists one inbound IMPLEMENTS edge

  Scenario: A pull request merges the change it proposed
    Given a Repository node exists with key "github.com/acme/payments"
    And a Change "a1b2c3" in "github.com/acme/payments" exists
    When I POST /api/v1/nodes/PullRequest with number 42, repositoryKey "github.com/acme/payments", url "https://github.com/acme/payments/pull/42"
    Then the response status is 201
    And the body field "key" is "github.com/acme/payments/pull/42"
    When I POST /api/v1/edges with type "MERGES" from that PullRequest to that Change
    Then the response status is 201
    And the body field "inverse" is "MERGED_BY"

  Scenario: A work item's identity is its URI, as written
    When I POST an ExternalWorkItem with uri "https://acme.atlassian.net/browse/PAY-42" and system "jira"
    And I POST an ExternalWorkItem with uri "https://acme.atlassian.net/browse/pay-42" and system "jira"
    Then 2 ExternalWorkItem nodes exist
    And the body field "key" is "https://acme.atlassian.net/browse/pay-42"

  Scenario: Where is this work item live
    Given a Change "a1b2c3" IMPLEMENTS ExternalWorkItem "chorus://task/01JABC"
    And an Artifact "ghcr.io/acme/payments:1.4.0" CONTAINS that Change
    And a Deployment of that Artifact to Environment "production" exists
    When I GET /api/v1/work-items/deployments?uri=chorus%3A%2F%2Ftask%2F01JABC
    Then the response status is 200
    And the body lists exactly one deployment with environment "production"
    And that deployment carries the Change "a1b2c3"

  Scenario: What did this deployment carry
    Given a Change "a1b2c3" IMPLEMENTS ExternalWorkItem "chorus://task/01JABC"
    And an Artifact "ghcr.io/acme/payments:1.4.0" CONTAINS that Change
    And a Deployment of that Artifact to Environment "production" exists
    When I GET /api/v1/deployments/work-items for that Deployment
    Then the response status is 200
    And the body has "lineage": "known"
    And the body lists exactly one work item with uri "chorus://task/01JABC"

  Scenario: A deployment with no change lineage says so
    Given a Deployment whose Artifact has no CONTAINS edges
    When I GET /api/v1/deployments/work-items for that Deployment
    Then the response status is 200
    And the body has "lineage": "unknown" and an empty "workItems" list

  Scenario: A work item nothing has shipped yet is live nowhere
    Given an ExternalWorkItem node exists with uri "chorus://task/01JNONE" and system "chorus"
    When I GET /api/v1/work-items/deployments?uri=chorus%3A%2F%2Ftask%2F01JNONE
    Then the response status is 200
    And the body has an empty "deployments" list

  Scenario: An unknown work item is not found
    When I GET /api/v1/work-items/deployments?uri=chorus%3A%2F%2Ftask%2Fnothing
    Then the response status is 404
    And the body names the missing node "ExternalWorkItem:chorus://task/nothing"

  Scenario: An unknown deployment is not found
    When I GET /api/v1/deployments/work-items?deploymentId=Deployment%3Anothing
    Then the response status is 404
    And the body names the missing node "Deployment:nothing"

  Scenario: The ontology drift check still passes
    When I GET "/api/v1/ontology"
    Then the response body equals the committed ontology snapshot
    And frontend/src/generated/ontology.ts declares Change, PullRequest and ExternalWorkItem
