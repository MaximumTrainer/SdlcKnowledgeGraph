@metrics
Feature: Sync runs an operator can watch
  A graph is only trusted while it is fresh, and a connector that quietly stopped syncing looks the
  same as one that has nothing new to say (#29, FR1). So every run reports what it did as meters on
  /actuator/prometheus: how many ran and how they ended, how long they took, what they wrote, what
  went wrong, and how long it has been since each connector last succeeded. Webhooks are counted by
  what became of them, including the ones refused for a bad signature.

  Counters only go up and the application is shared between scenarios, so each claim is about how
  far a series moved during the scenario rather than what it reads.

  Background:
    Given the fake connector is registered with capabilities FULL, INCREMENTAL and WEBHOOK

  @events
  Scenario: A completed run is counted with what it wrote, and logged
    Given the fake connector will return 2 Repository nodes and 1 DEPENDS_ON edge with watermark "2026-09-01T10:00:00Z"
    When I ask the fake connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the series 'sdlc_sync_runs_total{connector="fake",sourceSystem="fake",mode="FULL",status="SUCCESS"}' went up by 1
    And the series 'sdlc_sync_duration_seconds_count{connector="fake",mode="FULL"}' went up by 1
    And the series 'sdlc_sync_nodes_upserted_total{connector="fake"}' went up by 2
    And the series 'sdlc_sync_edges_upserted_total{connector="fake"}' went up by 1
    And the series 'sdlc_sync_pages_total{connector="fake"}' went up by 1
    And the series 'sdlc_sync_freshness_seconds{connector="fake"}' is less than 60
    And exactly one "sync.started" event is logged
    And its field "mode" is "FULL"
    And exactly one "sync.page" event is logged
    And its field "connector" is "fake"
    And exactly one "sync.finished" event is logged
    And its field "status" is "SUCCESS"

  Scenario: Run durations are recorded in buckets an operator can alert on
    Given the fake connector will return 2 Repository nodes and 1 DEPENDS_ON edge with watermark "2026-09-01T10:00:00Z"
    When I ask the fake connector for a full sync
    And the sync run finishes with status "SUCCESS"
    And I scrape the metrics
    Then the scrape has a "sdlc_sync_duration_seconds_bucket" series with label "le" set to "1.0"
    And the scrape has a "sdlc_sync_duration_seconds_bucket" series with label "le" set to "600.0"

  Scenario: A page that fails is counted as an error and the run as partial
    Given the fake connector will return one good delta and then fail
    When I ask the fake connector for a full sync
    And the sync run finishes with status "PARTIAL"
    Then the series 'sdlc_sync_errors_total{connector="fake",kind="page"}' went up by 1
    And the series 'sdlc_sync_runs_total{connector="fake",mode="FULL",status="PARTIAL"}' went up by 1
    And the series 'sdlc_sync_nodes_upserted_total{connector="fake"}' went up by 1

  Scenario: A run in flight shows as in progress until it ends
    Given a sync run for "fake" is already RUNNING
    Then the series 'sdlc_sync_in_progress{connector="fake"}' is 1
    When the fake connector is idle again
    Then the series 'sdlc_sync_in_progress{connector="fake"}' is 0

  Scenario: A webhook with a bad signature is counted as rejected
    When a webhook arrives for the fake connector signed with "the-wrong-secret"
    Then the response status is 401
    And the series 'sdlc_webhook_events_total{connector="fake",result="rejected"}' went up by 1
    And the series 'sdlc_sync_errors_total{connector="fake",kind="webhook_signature"}' went up by 1

  Scenario: A webhook that is applied is counted, and so is the run it became
    When a webhook arrives for the fake connector signed correctly
    Then the response status is 202
    And the series 'sdlc_webhook_events_total{connector="fake",result="applied"}' went up by 1
    And the series 'sdlc_sync_runs_total{connector="fake",mode="WEBHOOK",status="SUCCESS"}' went up by 1

  Scenario: A webhook the connector finds nothing in is counted as ignored
    Given the fake connector will find nothing in the next webhook
    When a webhook arrives for the fake connector signed correctly
    Then the response status is 204
    And the series 'sdlc_webhook_events_total{connector="fake",result="ignored"}' went up by 1
    And the series 'sdlc_webhook_events_total{connector="fake",result="applied"}' went up by 0
