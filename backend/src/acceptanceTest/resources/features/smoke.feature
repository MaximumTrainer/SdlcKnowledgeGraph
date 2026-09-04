Feature: Application health smoke
  Walking skeleton for the acceptance-test harness: the API boots against a real Neo4j
  (Testcontainers) and reports the database as healthy.

  Scenario: Health endpoint reports Neo4j as UP
    Given the application is running
    When I GET "/actuator/health"
    Then the response status is 200
    And the health component "neo4j" is "UP"
