Feature: Agents can tell how current a fact is
  A graph that lags reality is worse than no graph, because an agent will trust it and act wrongly
  (#93). So every source system has a freshness window, published with the ontology; a fact whose
  source has not re-stated it within that window is read back with stale true; the lag of each
  source against its window is part of /actuator/health, without ever taking the instance down;
  and a read may ask what the graph held at an instant rather than now.

  The test profile gives "aws" a window of 6 hours. Every other source has the default of 24 hours.

  Scenario: A fact older than its source window is marked stale
    Given source "aws" has a freshness window of 6 hours
    And a CloudResource ingested from "aws" 9 hours ago
    When the node is read
    Then the provenance block contains stale true

  Scenario: A fact within its source window is not stale
    Given source "aws" has a freshness window of 6 hours
    And a CloudResource ingested from "aws" 2 hours ago
    When the node is read
    Then the provenance block contains stale false

  Scenario: Connector lag degrades health, and only that
    Given the last successful SyncRun for "github" ended 30 hours ago
    And "github" has a freshness window of 24 hours
    When I GET "/actuator/health"
    Then the "freshness" component reports WARN with source "github" lag over 24h
    And the response status is 200
    And the body field "status" is "UP"
    When I GET "/actuator/health/readiness"
    Then the response status is 200
    When I GET "/actuator/health/liveness"
    Then the response status is 200

  Scenario: A source within its window leaves health UP
    Given the last successful SyncRun for "github" ended 2 hours ago
    When I GET "/actuator/health"
    Then the health component "freshness" is "UP"

  Scenario: An as-of read returns the deployment current at that time
    Given Deployment A to production validFrom 01:00 validTo 01:58
    And Deployment B to production validFrom 01:58 validTo null
    When edges for the Environment "production" are read asOf 01:30
    Then only Deployment A is returned
    When edges for the Environment "production" are read asOf 02:30
    Then only Deployment B is returned
    When edges for the Environment "production" are read without asOf
    Then Deployments A and B are returned

  Scenario: A node read as of an instant outside its validity is not found
    Given Deployment A to production validFrom 01:00 validTo 01:58
    When Deployment A is read asOf 01:30
    Then the response status is 200
    When Deployment A is read asOf 02:30
    Then the response status is 404
    When Deployment A is read without asOf
    Then the response status is 200

  Scenario: The GraphQL query root reads as of an instant
    Given Deployment A to production validFrom 01:00 validTo 01:58
    When GraphQL asks for Deployment A asOf 01:30
    Then GraphQL returns the node
    When GraphQL asks for Deployment A asOf 02:30
    Then GraphQL returns no node

  Scenario: A malformed asOf is refused
    When I GET "/api/v1/nodes/Environment/production?asOf=yesterday"
    Then the response status is 400
    And the body field "field" is "asOf"

  Scenario: validTo is set through PUT, and a closed node with edges still cannot be deleted
    Given a Team node exists with name "platform"
    And the Team "platform" was valid from "2026-09-01T00:00:00Z"
    And a Repository node exists with url "https://github.com/acme/payments" and description "payments"
    And that Repository is OWNED_BY that Team
    When I PUT the Team "platform" with validTo "2026-09-15T00:00:00Z"
    Then the response status is 200
    And the provenance field "validTo" is "2026-09-15T00:00:00Z"
    And the provenance field "validFrom" is "2026-09-01T00:00:00Z"
    When the Team "platform" is read asOf "2026-09-10T00:00:00Z"
    Then the response status is 200
    When the Team "platform" is read asOf "2026-09-20T00:00:00Z"
    Then the response status is 404
    When I DELETE the Team node
    Then the response status is 409

  Scenario: A validTo before validFrom is refused
    Given a Team node exists with name "platform"
    And the Team "platform" was valid from "2026-09-01T00:00:00Z"
    When I PUT the Team "platform" with validTo "2026-08-01T00:00:00Z"
    Then the response status is 400
    And an error names the field "provenance.validTo"
