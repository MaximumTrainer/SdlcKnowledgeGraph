Feature: Ontology-driven node CRUD
  Every core node type is maintainable by hand through one API keyed by registry type, so a type
  added to the ontology becomes editable without new endpoints or new screens. Every manual write
  carries provenance and resolves to a derived identity key, which is what stops the same real-world
  thing becoming two nodes.

  Scenario: Editing a repository updates it in place
    Given a Repository node exists with url "https://github.com/acme/payments" and description "old"
    When I PUT that node with description "new"
    Then the response status is 200
    And exactly 1 node of type "Repository" has key "github.com/acme/payments"
    And that node has description "new"
    And that node has provenance source "manual"

  Scenario: Creating a node with an existing identity key is rejected
    Given a CloudResource node exists with provider "aws" and resourceId "arn:aws:s3:::acme-logs"
    When I POST a CloudResource with provider "aws" and resourceId "arn:aws:s3:::acme-logs"
    Then the response status is 409
    And the body field "existingId" is the id of the node created earlier

  Scenario: Required properties are enforced from the ontology
    When I POST a Team with no name
    Then the response status is 400
    And the errors contain "name is required"

  Scenario: A property the ontology does not declare is rejected
    When I POST a Team named "platform" with an undeclared property "colour"
    Then the response status is 400
    And the errors contain "not in ontology"

  Scenario: Identity properties cannot be changed on update
    Given a Team node exists with name "platform"
    When I PUT that node with name "core"
    Then the response status is 409
    And the body field "fields" contains "name"

  Scenario: Deleting a node with edges requires cascade
    Given a Team node exists with name "platform"
    And a Repository node exists with url "https://github.com/acme/payments" and description "old"
    And that Repository is OWNED_BY that Team
    When I DELETE the Team node
    Then the response status is 409
    And the body field "edgeCount" is 1
    When I DELETE the Team node with cascade
    Then the response status is 204
    And no OWNED_BY edge exists in the graph

  Scenario: Unknown node type
    When I POST a node of type "Widget" with name "x"
    Then the response status is 404
    And the body field "error" is "unknown node type"

  Scenario: Nodes are listed in key order
    Given a Team node exists with name "platform"
    And a Team node exists with name "core"
    When I GET "/api/v1/nodes/Team"
    Then the response status is 200
    And the listed keys are "core, platform"

  Scenario: The deprecated repository endpoint still creates a node
    When I POST the deprecated repositories endpoint with orgRepo "acme/payments"
    Then the response status is 201
    And the response has header "Deprecation" with value "true"
    And exactly 1 node of type "Repository" has key "github.com/acme/payments"
