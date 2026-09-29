@events
Feature: Structured, auditable logging
  Every line the application writes is a declared event (#44): a name, a level and the fields the
  registry says it has, in observability/events.yaml. So a machine can read what happened, and a
  value that should not be in a log cannot get into one by accident: a node write names the
  properties it was given, never their values, and a field whose name looks like a secret is masked.

  Scenario: A refused write is logged as one structured event, without the values
    When I POST "/api/v1/nodes/Team" with props {"name": "", "secret": "hunter2"} and X-Request-Id "rejected-1"
    Then exactly one "node.rejected" event is logged
    And it carries requestId "rejected-1"
    And its field "type" is "Team"
    And its field "fields" lists "name" and "secret"
    And no log line contains "hunter2"

  Scenario: A created node is logged by its type, key and property names
    When I POST "/api/v1/nodes/Team" with props {"name": "Platform", "email": "ops@example.com"} and X-Request-Id "created-1"
    Then exactly one "node.created" event is logged
    And its field "key" is "platform"
    And its field "properties" lists "email" and "name"
    And no log line contains "ops@example.com"

  Scenario: A field that looks like a secret never reaches the log
    When the deploy pipeline reports a deployment with the token "test-ingest-token"
    Then exactly one "ingest.deployment.received" event is logged
    And no log line contains "test-ingest-token"
