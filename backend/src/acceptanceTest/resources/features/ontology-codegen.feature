Feature: Ontology code generation
  The registry is the single declaration of the model. The GraphQL schema, the frontend types and
  the JSON fixture are rendered from it rather than hand-written, so a consumer that builds against
  a generated file is building against the same ontology the API serves.

  Scenario: The ontology endpoint and the committed snapshot agree
    Given the application is running
    When I GET "/api/v1/ontology"
    Then the response status is 200
    And the response body equals the committed ontology snapshot

  Scenario: Every served node type has a generated GraphQL type
    Given the application is running
    When I GET "/api/v1/ontology"
    Then every served node type has a generated GraphQL type
    And every served edge type is listed in the generated EdgeType enum

  Scenario: Every served node type has a generated TypeScript interface
    Given the application is running
    When I GET "/api/v1/ontology"
    Then every served node type has a generated TypeScript interface
    And the generated TypeScript declares the version the endpoint reports

  Scenario: Generated files announce that they are generated
    Given the application is running
    Then every generated ontology file carries the do-not-edit header
