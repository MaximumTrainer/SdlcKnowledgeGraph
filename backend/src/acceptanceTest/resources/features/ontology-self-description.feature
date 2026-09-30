Feature: Self-describing ontology
  The registry is the contract an agent reads to know what it may ask (#81). A property without a
  description, an example or its allowed values leaves a reader guessing, and a guess is a query for
  a value that does not exist. So every property says what it is, shows one value, and names its
  closed set where it has one; the API enforces that set on write and serves the whole contract,
  including as Markdown sized for a prompt.

  The lint that keeps the registry complete runs at build time (./gradlew ontologyLint, part of
  check), against fixture registries in buildSrc's OntologyLintTaskTest. These scenarios cover what
  the running application serves and enforces.

  Scenario: Every property is described with an example
    When I GET "/api/v1/ontology"
    Then the response status is 200
    And the ontology version is "1.5.0"
    And every property of every node and edge type has a description and at least one example
    And every node type has an example node
    And the core node types each name the questions they help answer

  Scenario: Enum values are enforced on write
    When I POST a Deployment with status "DONE" and otherwise valid properties
    Then the response status is 400
    And the errors name the field "status" with the message "status must be one of PENDING, IN_PROGRESS, SUCCESS, FAILED, ROLLED_BACK, CANCELLED"

  Scenario: Formats are enforced on write
    When I POST a PullRequest with url "not a url"
    Then the response status is 400
    And the errors name the field "url" with the message "expected url"

  Scenario: A format is checked only when there is a value
    When I POST a Team named "platform" with email "platform@acme.example"
    Then the response status is 201
    When I POST a Team named "core" with no email
    Then the response status is 201
    When I POST a Team named "infra" with email "not an email"
    Then the response status is 400
    And the errors name the field "email" with the message "expected email"

  Scenario: Deprecations reach the generated types
    Then the generated GraphQL declares "RepositoryNode.serviceId" deprecated
    And the generated TypeScript marks "Repository.serviceId" deprecated
    And an enum "DeploymentStatus" is generated in both the GraphQL and the TypeScript

  Scenario: The Markdown ontology is deterministic and prompt-sized
    When I GET the ontology as Markdown twice
    Then the response status is 200
    And the two Markdown bodies are byte-identical
    And the Markdown body is under 12000 characters
    And the Markdown contains "Which team owns this repository?" under "Repository"

  Scenario: Non-conforming existing data is reported, not rewritten
    Given a Deployment node exists with status "DONE" written before the enum was declared
    When the enum conformance report runs
    Then the report lists "Deployment.status" value "DONE" with count 1
    And reading that Deployment still returns status "DONE"
