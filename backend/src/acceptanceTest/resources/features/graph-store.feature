Feature: Registry-driven graph store
  Every node and edge goes through one store that builds its Cypher from the ontology registry.
  That is what makes identity, provenance and referential honesty properties of the system rather
  than of whichever query happened to be written.

  Scenario: Deployments are visible, because the build relationship is actually written
    Given a Repository "github.com/acme/payments" exists
    And an Artifact "ghcr.io/acme/payments@sha256:abc" built from commit "c1" of that repository
    And a Deployment of that artifact to Environment "staging" with status "SUCCESS"
    When I GET "/api/v1/graph/deployments?repoId=Repository:github.com/acme/payments"
    Then the response status is 200
    And the response contains 1 deployment with status "SUCCESS"

  Scenario: Linking to a node that does not exist is refused, not silently ignored
    Given a Repository "github.com/acme/payments" exists
    When I link Repository "github.com/acme/payments" to Team "platform"
    Then the response status is 404
    And the missing nodes are "Team:platform"
    And no OWNED_BY edge exists in the graph

  Scenario: Linking succeeds once both ends exist
    Given a Repository "github.com/acme/payments" exists
    And a Team "platform" exists
    When I link Repository "github.com/acme/payments" to Team "platform"
    Then the response status is 201
    And one OWNED_BY edge exists in the graph

  Scenario: A node's identity is unique, so re-registering updates rather than duplicates
    Given a Team "platform" exists
    When a Team "platform" is upserted with description "changed"
    Then exactly 1 Team node exists with key "platform"
    And that Team has description "changed"

  Scenario: Provenance is recorded on edges, not just on nodes
    Given a Repository "github.com/acme/payments" exists
    And a Team "platform" exists
    When I link Repository "github.com/acme/payments" to Team "platform"
    Then the OWNED_BY edge has provenance source "manual" and confidence 1.0

  Scenario: A hostile label cannot reach the database
    Given a Repository "github.com/acme/payments" exists
    When I upsert a node of type "Repository) DETACH DELETE (n"
    Then the request is rejected as an unknown node type
    And the Repository "github.com/acme/payments" still exists
