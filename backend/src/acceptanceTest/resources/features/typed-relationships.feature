Feature: Typed relationships
  A relationship is only useful when it is typed, constrained to the node types the ontology allows
  at each end, and traversable in both directions through its declared inverse. One edge is stored
  and read two ways, so "what depends on this" and "what does this depend on" are the same fact.

  Background:
    Given the Repository "https://github.com/acme/payments" is registered
    And the Repository "https://github.com/acme/shared-lib" is registered

  Scenario: A dependency with a kind is created and visible from both ends
    When I POST the edge:
      """
      {"type": "DEPENDS_ON",
       "fromId": "Repository:github.com/acme/payments",
       "toId": "Repository:github.com/acme/shared-lib",
       "props": {"kind": "library", "manifest": "build.gradle.kts"}}
      """
    Then the response status is 201
    And the body field "inverse" is "DEPENDED_ON_BY"
    When I GET the edges of "Repository" "github.com/acme/shared-lib" with direction "in"
    Then the response status is 200
    And one edge is listed with displayName "DEPENDED_ON_BY" and other key "github.com/acme/payments"
    And that listed edge has prop "kind" of "library"

  Scenario: The same edge read from the other end keeps its own name
    Given the edge "DEPENDS_ON" exists from "Repository:github.com/acme/payments" to "Repository:github.com/acme/shared-lib" with kind "library"
    When I GET the edges of "Repository" "github.com/acme/payments" with direction "out"
    Then one edge is listed with displayName "DEPENDS_ON" and other key "github.com/acme/shared-lib"

  Scenario: An edge between disallowed types is rejected
    Given the Team "platform" is registered
    And the Environment "production" is registered
    When I POST the edge:
      """
      {"type": "OWNED_BY", "fromId": "Environment:production", "toId": "Team:platform"}
      """
    Then the response status is 400
    And the body field "error" is "edge not allowed"
    And the allowed pairs contain from "Repository" to "Team"

  Scenario: An edge to a missing node is rejected
    When I POST the edge:
      """
      {"type": "OWNED_BY", "fromId": "Repository:github.com/acme/payments", "toId": "Team:nobody"}
      """
    Then the response status is 404
    And the body field "missing" contains "Team:nobody"

  Scenario: An unknown edge type is rejected
    When I POST the edge:
      """
      {"type": "SMELLS_LIKE", "fromId": "Repository:github.com/acme/payments", "toId": "Repository:github.com/acme/shared-lib"}
      """
    Then the response status is 400
    And the body field "error" is "unknown edge type"

  Scenario: DEPENDS_ON requires a valid kind
    When I POST the edge:
      """
      {"type": "DEPENDS_ON",
       "fromId": "Repository:github.com/acme/payments",
       "toId": "Repository:github.com/acme/shared-lib",
       "props": {"kind": "magic"}}
      """
    Then the response status is 400
    And the errors contain "kind must be one of library, api, event, data"

  Scenario: DEPENDS_ON requires a kind at all
    When I POST the edge:
      """
      {"type": "DEPENDS_ON",
       "fromId": "Repository:github.com/acme/payments",
       "toId": "Repository:github.com/acme/shared-lib"}
      """
    Then the response status is 400
    And the errors contain "kind is required"

  Scenario: A node cannot depend on itself
    When I POST the edge:
      """
      {"type": "DEPENDS_ON",
       "fromId": "Repository:github.com/acme/payments",
       "toId": "Repository:github.com/acme/payments",
       "props": {"kind": "library"}}
      """
    Then the response status is 400
    And the body field "error" is "self edge"

  Scenario: Creating the same edge twice is idempotent
    Given the edge "DEPENDS_ON" exists from "Repository:github.com/acme/payments" to "Repository:github.com/acme/shared-lib" with kind "library"
    When I POST the edge:
      """
      {"type": "DEPENDS_ON",
       "fromId": "Repository:github.com/acme/payments",
       "toId": "Repository:github.com/acme/shared-lib",
       "props": {"kind": "library", "manifest": "build.gradle.kts"}}
      """
    Then the response status is 200
    And exactly 1 "DEPENDS_ON" edges exist in the graph

  Scenario: An edge carries the provenance of the person who stated it
    Given the edge "DEPENDS_ON" exists from "Repository:github.com/acme/payments" to "Repository:github.com/acme/shared-lib" with kind "library"
    When I GET the edges of "Repository" "github.com/acme/payments" with direction "out"
    Then one edge is listed with displayName "DEPENDS_ON" and other key "github.com/acme/shared-lib"
    And that listed edge has provenance source "manual"

  Scenario: Removing an edge leaves both nodes alone
    Given the edge "DEPENDS_ON" exists from "Repository:github.com/acme/payments" to "Repository:github.com/acme/shared-lib" with kind "library"
    When I DELETE the edge "DEPENDS_ON" from "Repository:github.com/acme/payments" to "Repository:github.com/acme/shared-lib"
    Then the response status is 204
    And exactly 0 "DEPENDS_ON" edges exist in the graph
    And exactly 1 node of type "Repository" has key "github.com/acme/payments"

  Scenario: Removing an edge that is not there says so
    When I DELETE the edge "DEPENDS_ON" from "Repository:github.com/acme/payments" to "Repository:github.com/acme/shared-lib"
    Then the response status is 404
