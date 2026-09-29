@sync-runs
Feature: Sync runs an operator can browse, and forget once they are old
  A meter says how often runs fail; the run itself says which one, when, and why (#29, FR5). So
  GET /api/v1/sync-runs lists the recorded runs newest first, filtered by connector, status and when
  they started, and GET /api/v1/sync-runs/{id} returns one run in full.

  Every run leaves a SyncRun node and a PRODUCED edge to everything it wrote, so the history grows
  with every sync for ever unless something prunes it (#29, FR6). A nightly job deletes the runs that
  finished longer ago than observability.sync-run-retention. What those runs produced stays, still
  naming the run in its provenance. A run that is still going is never pruned, however old.

  The runs here are written straight into the graph rather than produced by syncing, so each scenario
  says exactly when they started and how they ended. The graph is emptied before every scenario.

  Scenario: Sync run list and detail
    Given three SyncRuns exist for "fake"
    When I GET "/api/v1/sync-runs?connector=fake&size=2"
    Then the response status is 200
    And the response has 2 sync runs sorted by startedAt descending and totalElements 3
    When I GET the first listed sync run
    Then the response status is 200
    And the sync run has the fields "status", "details" and "error"

  Scenario: The list can be filtered by status, and a long error is cut short
    Given three SyncRuns exist for "fake"
    When I GET "/api/v1/sync-runs?connector=fake&status=PARTIAL"
    Then the response status is 200
    And the listed sync runs are "fake-run-2"
    And the listed sync run "fake-run-2" has an error of at most 200 characters
    When I GET "/api/v1/sync-runs/fake-run-2"
    Then the response status is 200
    And the sync run's error is 500 characters long

  Scenario: The list can be narrowed to when runs started
    Given three SyncRuns exist for "fake"
    When I GET the sync runs for "fake" that started in the last 150 minutes
    Then the response status is 200
    And the listed sync runs are "fake-run-3,fake-run-2"

  Scenario: An unknown sync run is not found
    When I GET "/api/v1/sync-runs/no-such-run"
    Then the response status is 404
    And the body field "error" is "sync run not found"

  Scenario Outline: A malformed filter is refused
    When I GET "/api/v1/sync-runs?<query>"
    Then the response status is 400
    And the body field "error" is "invalid request"

    Examples:
      | query                                             |
      | status=SOMETIMES                                  |
      | size=101                                          |
      | size=0                                            |
      | page=-1                                           |
      | from=yesterday                                    |
      | to=2026-13-01                                     |
      | from=2026-09-02T00:00:00Z&to=2026-09-01T00:00:00Z |

  @events
  Scenario: Old runs are pruned, recent and running ones are kept
    Given a SyncRun "old-run" for "fake" that finished 40 days ago
    And the SyncRun "old-run" PRODUCED Repository "github.com/acme/pruned-producer"
    And a SyncRun "recent-run" for "fake" that finished 1 days ago
    And a SyncRun "stuck-run" for "fake" still RUNNING since 40 days ago
    When the sync run retention job runs
    Then the SyncRun "old-run" is gone
    And the SyncRun "recent-run" still exists
    And the SyncRun "stuck-run" still exists
    And the Repository "github.com/acme/pruned-producer" still names sync run "old-run" in its provenance
    And exactly one "sync.runs.pruned" event is logged
    And its field "deleted" is the number 1
