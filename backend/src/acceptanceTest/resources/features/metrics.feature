@metrics
Feature: Metrics a Prometheus server can scrape
  The API reports what it is doing as meters (#44, FR7): what it was built from, the graph writes it
  made and refused, the store operations that failed, and how long each request took, in buckets the
  service objectives are measured against. The actuator publishes them at /actuator/prometheus, which
  the web interface does not proxy, so only something inside the deployment can scrape them.

  Scenario: The scrape says what the instance is running
    When I scrape the metrics
    Then the metric "sdlc_build_info" has the value 1 with label "ontology_version" set to "1.0.0"

  Scenario: Request latency is recorded in buckets that include the latency objective
    Given I GET "/api/v1/ontology"
    When I scrape the metrics
    Then the scrape has a "http_server_requests_seconds_bucket" series with label "le" set to "0.5"

  Scenario: Node writes are counted by type and outcome
    When the Team "metrics-platform" is registered
    And I POST "/api/v1/nodes/Team" with props {"name": ""} and X-Request-Id "metrics-rejected-1"
    Then "sdlc_node_writes_total" with type "Team" and outcome "created" went up by 1
    And "sdlc_node_writes_total" with type "Team" and outcome "rejected" went up by 1

  Scenario: Edge writes are counted by type and outcome
    Given the Team "metrics-owners" is registered
    And the Repository "github.com/acme/metrics-web" is registered
    When I POST the edge:
      """
      {"type": "OWNED_BY",
       "fromId": "Repository:github.com/acme/metrics-web",
       "toId": "Team:metrics-owners",
       "props": {"pathPatterns": ["*"]}}
      """
    Then the response status is 201
    And "sdlc_edge_writes_total" with type "OWNED_BY" and outcome "created" went up by 1

  @events
  Scenario: A store failure is counted and logged with the request's id
    Given the graph store fails on its next "findNode"
    When I GET "/api/v1/nodes/Team/anyone" with X-Request-Id "store-failed-1"
    Then the response status is 500
    And exactly one "graph.store.failed" event is logged
    And it carries requestId "store-failed-1"
    And its field "operation" is "findNode"
    And "sdlc_graph_store_errors_total" with operation "findNode" went up by 1
