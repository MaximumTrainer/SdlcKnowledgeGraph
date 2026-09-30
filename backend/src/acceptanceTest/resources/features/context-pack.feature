Feature: An agent gets exactly the subgraph a task needs
  Given a node and the shape of a task, the graph answers with the bounded subgraph that matters and
  nothing else (#96), so an agent's window holds tens of relevant nodes rather than hundreds of
  files. The shape is a named traversal template declared in the registry's templates.yaml, and the
  budget caps the nodes the pack holds: nearest and most confident first, with what was cut counted.

  Scenario: Change impact is bounded and ranked
    Given Repository "settlement-api" with 7 transitive dependants and 40 unrelated repositories in the graph
    When a context pack is requested from Repository "settlement-api" with template "change-impact" and budget 20
    Then the response status is 200
    And the pack contains the 7 dependants, their owners and current production deployments
    And it contains no repository outside that traversal
    And it contains no deployment a later one has superseded
    And the pack says it is not truncated
    And the pack's nodes are nearest first

  Scenario: Truncation is reported
    Given a start node whose change-impact traversal reaches 35 nodes
    When a context pack is requested from that node with template "change-impact" and budget 10
    Then 10 nodes are returned with truncated true and cut 25

  Scenario: Evidence travels with the pack
    Given a DEPENDS_ON edge with manifest "package.json"
    When it appears in a pack
    Then the edge carries manifest "package.json" and its provenance summary
    And every node in the pack carries a provenance summary

  Scenario: Incident triage starts from a cloud resource
    Given CloudResource "orders-db" owned by Repository "orders" with its pipeline, CI and a production deployment
    And Repository "orders" DEPENDS_ON Repository "pricing" as an api, deployed to production
    And Repository "orders" DEPENDS_ON Repository "json-utils" as a library
    When a context pack is requested from CloudResource "orders-db" with template "incident-triage" and budget 50
    Then the response status is 200
    And the pack holds Repository "orders", its pipeline, its CI and its current deployment
    And the pack holds Repository "pricing" and its current deployment
    And the pack does not hold Repository "json-utils"
    And the pack's BUILT_FROM edges carry their commitSha

  Scenario: Data consumers of a cloud resource, with their owners
    Given CloudResource "ledger-bucket" read by Repository "reconciler" owned by Team "finance" as data
    And CloudResource "ledger-bucket" read by Repository "exporter" owned by Team "analytics" as data
    And CloudResource "ledger-bucket" called by Repository "ledger-admin" as an api
    When a context pack is requested from CloudResource "ledger-bucket" with template "data-consumers" and budget 50
    Then the response status is 200
    And the pack holds Repository "reconciler" and Team "finance"
    And the pack holds Repository "exporter" and Team "analytics"
    And the pack does not hold Repository "ledger-admin"

  Scenario: A pack as of an instant is the graph as it was then
    Given Repository "settlement-api" with 7 transitive dependants and 40 unrelated repositories in the graph
    And Repository "late-joiner" began to depend on "settlement-api" on "2026-09-20T00:00:00Z"
    When a context pack is requested from Repository "settlement-api" with template "change-impact", budget 50 and asOf "2026-09-10T00:00:00Z"
    Then the response status is 200
    And the pack's asOf is "2026-09-10T00:00:00Z"
    And the pack does not hold Repository "late-joiner"
    But a context pack requested now holds Repository "late-joiner"

  Scenario: The shipped templates are published with the registry
    When I GET "/api/v1/ontology"
    Then the response status is 200
    And the ontology declares the templates "change-impact", "incident-triage" and "data-consumers"

  Scenario: An unknown template is refused, naming the ones there are
    Given Repository "settlement-api" with 7 transitive dependants and 40 unrelated repositories in the graph
    When a context pack is requested from Repository "settlement-api" with template "everything" and budget 20
    Then the response status is 400
    And the refusal names the field "template" and mentions "change-impact"

  Scenario: A start node the template does not start from is refused
    Given CloudResource "ledger-bucket" read by Repository "reconciler" owned by Team "finance" as data
    When a context pack is requested from CloudResource "ledger-bucket" with template "change-impact" and budget 20
    Then the response status is 400
    And the refusal names the field "startId" and mentions "Repository"

  Scenario: A budget out of bounds is refused
    Given Repository "settlement-api" with 7 transitive dependants and 40 unrelated repositories in the graph
    When a context pack is requested from Repository "settlement-api" with template "change-impact" and budget 501
    Then the response status is 400
    And the refusal names the field "budget" and mentions "500"

  Scenario: A start node the graph does not hold is not found
    When a context pack is requested from Repository "nowhere" with template "change-impact" and budget 20
    Then the response status is 404

  Scenario: The same pack through GraphQL
    Given Repository "settlement-api" with 7 transitive dependants and 40 unrelated repositories in the graph
    When I query GraphQL for the "change-impact" context pack of Repository "settlement-api" with budget 20
    Then the GraphQL context pack holds the same nodes as the REST one, in the same order
