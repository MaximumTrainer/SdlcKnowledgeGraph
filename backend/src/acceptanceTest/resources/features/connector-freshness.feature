@freshness
Feature: Connector freshness an operator can see and alert on
  An answer built on a week-old snapshot is worse than no answer, and a connector that stopped
  syncing looks the same as one with nothing new to say (#29, FR3 and FR4). So what the graph
  remembers about each connector says when it last succeeded and how many runs have failed since,
  GET /api/v1/connectors says how old that success is against the connector's threshold, and the
  `connectors` health component is DOWN while any enabled connector is stale.

  Stale is not unready: an instance with an old graph still answers correctly about what it has, so
  staleness stays out of the readiness probe unless an operator opts in.

  The fake connector's threshold is PT1H in the test profile. Every scenario starts from an empty
  graph, which is a connector that has never succeeded and is still within its first threshold.

  Background:
    Given the fake connector is registered with capabilities FULL, INCREMENTAL and WEBHOOK

  Scenario: Stale connector turns the connectors health DOWN
    Given the connector state for "fake" has lastSuccessAt 3 hours ago
    When I GET "/actuator/health"
    Then the health component "connectors" is "DOWN"
    And the health component "connectors" lists "fake" as stale
    When I list the connectors
    Then the listed connector "fake" has freshness stale true
    And the listed connector "fake" has a freshness age of at least 10800 seconds
    And the listed connector "fake" has a freshness threshold of 3600 seconds

  Scenario: A fresh connector is not stale
    Given the fake connector will return 2 Repository nodes and 1 DEPENDS_ON edge with watermark "2026-09-01T10:00:00Z"
    When I ask the fake connector for a full sync
    And the sync run finishes with status "SUCCESS"
    And I GET "/actuator/health"
    Then the health component "connectors" is "UP"
    When I list the connectors
    Then the listed connector "fake" has freshness stale false
    And the listed connector "fake" has a freshness age of less than 60 seconds
    And the listed connector "fake" has a lastSuccessAt

  Scenario: Failures are counted until a success
    Given the fake connector will return one good delta and then fail
    When I ask the fake connector for a full sync
    And the sync run finishes with status "PARTIAL"
    And the fake connector will return one good delta and then fail
    And I ask the fake connector for a full sync
    And the sync run finishes with status "PARTIAL"
    Then the state of connector "fake" has 2 consecutive failures and last run status "PARTIAL"
    And the state of connector "fake" has no lastSuccessAt
    Given the fake connector will return 2 Repository nodes and 1 DEPENDS_ON edge with watermark "2026-09-01T10:00:00Z"
    When I ask the fake connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the state of connector "fake" has 0 consecutive failures and last run status "SUCCESS"
    And the state of connector "fake" has a lastSuccessAt

  Scenario: Staleness does not take the instance out of readiness by default
    Given the connector state for "fake" has lastSuccessAt 3 hours ago
    When I GET "/actuator/health/readiness"
    Then the response status is 200
    And the body field "status" is "UP"
