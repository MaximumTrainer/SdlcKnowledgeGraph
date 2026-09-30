Feature: Graph visualiser neighbourhood
  A person trusts "what depends on this" more when they can see it than when they read a list (#9).
  The graph view draws the neighbourhood of one node, so the API answers a bounded subgraph ready to
  render: every node with a label taken from the registry's displayProperty, every edge with a
  deterministic id and the confidence and inferred flag of its provenance, so an inferred edge can be
  drawn differently from an observed one.

  Background:
    Given Repository "github.com/acme/payments" owned by Team "platform"
    And Repository "github.com/acme/payments" DEPENDS_ON Repository "github.com/acme/shared-lib" with kind "library"
    And Repository "github.com/acme/payments" OWNS_RESOURCE CloudResource "aws:arn:aws:s3:::acme-logs" with confidence 0.7 inferred

  Scenario: Depth-1 neighbourhood
    When I GET "/api/v1/graph/neighbourhood?nodeId=Repository:github.com/acme/payments"
    Then the response status is 200
    And the neighbourhood root is "Repository:github.com/acme/payments"
    And nodes has 4 entries and edges has 3 entries
    And the OWNS_RESOURCE edge has inferred true and confidence 0.7
    And every node has a non-empty label
    And the node "Team:platform" is labelled "platform"
    And the node "Repository:github.com/acme/shared-lib" is labelled "shared-lib"
    And the edge "DEPENDS_ON:Repository:github.com/acme/payments>Repository:github.com/acme/shared-lib" goes from "Repository:github.com/acme/payments" to "Repository:github.com/acme/shared-lib"
    And truncated is false

  Scenario: Filtering by node type
    When I GET "/api/v1/graph/neighbourhood?nodeId=Repository:github.com/acme/payments&nodeTypes=Repository"
    Then the response status is 200
    And nodes has 2 entries and edges has 1 entry of type "DEPENDS_ON"

  Scenario: Filtering by edge type and direction
    When I GET "/api/v1/graph/neighbourhood?nodeId=Repository:github.com/acme/shared-lib&edgeTypes=DEPENDS_ON&direction=in&depth=2"
    Then the response status is 200
    And nodes has 2 entries and edges has 1 entry of type "DEPENDS_ON"

  Scenario: Depth reaches further
    Given Repository "github.com/acme/checkout" DEPENDS_ON Repository "github.com/acme/shared-lib" with kind "api"
    When I GET "/api/v1/graph/neighbourhood?nodeId=Repository:github.com/acme/payments&depth=2"
    Then the response status is 200
    And the nodes include "Repository:github.com/acme/checkout"

  Scenario: Result is capped
    Given Repository "github.com/acme/hub" DEPENDS_ON 600 distinct repositories
    When I GET "/api/v1/graph/neighbourhood?nodeId=Repository:github.com/acme/hub"
    Then the response status is 200
    And nodes has 500 entries and truncated is true
    And the nodes include "Repository:github.com/acme/hub"

  Scenario: Invalid depth
    When I GET "/api/v1/graph/neighbourhood?nodeId=Repository:github.com/acme/payments&depth=4"
    Then the response status is 400 and field is "depth"

  Scenario: An edge type the ontology does not declare
    When I GET "/api/v1/graph/neighbourhood?nodeId=Repository:github.com/acme/payments&edgeTypes=KNOWS"
    Then the response status is 400 and field is "edgeTypes"

  Scenario: Unknown node
    When I GET "/api/v1/graph/neighbourhood?nodeId=Repository:github.com/acme/nothing-here"
    Then the response status is 404
    And the body field "error" is "node not found"
