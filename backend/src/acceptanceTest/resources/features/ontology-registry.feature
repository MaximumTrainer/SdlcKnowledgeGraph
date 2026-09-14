Feature: Ontology registry
  The ontology is the declared contract for what may exist in the graph. It is served from the
  registry rather than hand-written per consumer, so the API, the schema and the user interface
  cannot drift apart.

  Scenario: The ontology is served from the registry
    Given the application is running
    When I GET "/api/v1/ontology"
    Then the response status is 200
    And the ontology version is "1.0.0"
    And the ontology declares the node types:
      | Repository        |
      | Team              |
      | Service           |
      | Pipeline          |
      | Artifact          |
      | Deployment        |
      | Environment       |
      | CloudResource     |
      | ConfigurationItem |
      | Ontology          |
      | SyncRun           |
      | ConnectorState    |
    And every edge type declares a non-empty inverse

  Scenario: An edge type states what it connects and how to traverse it backwards
    Given the application is running
    When I GET "/api/v1/ontology"
    Then the edge type "BUILT_FROM" has inverse "BUILDS"
    And the edge type "BUILT_FROM" goes from "Artifact" to "Repository"

  Scenario: A known node type can be fetched on its own
    Given the application is running
    When I GET "/api/v1/ontology/nodes/Repository"
    Then the response status is 200
    And the node type identity is "host, org, name"

  Scenario: An unknown node type is reported rather than guessed at
    Given the application is running
    When I GET "/api/v1/ontology/nodes/Nonsense"
    Then the response status is 404
    And the error message is "unknown node type"

  Scenario: The ontology response is cacheable
    Given the application is running
    When I GET "/api/v1/ontology"
    Then the response has an ETag
    And the response is cacheable for 300 seconds

  Scenario: The graph records which ontology version built it
    Given the application is running
    When I count the Ontology nodes in the graph
    Then there is exactly one Ontology node with version "1.0.0"
