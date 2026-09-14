Feature: Connector SPI and sync runs
  Every system that knows something about this estate reaches the graph the same way: one scheduler,
  one delta writer, one REST surface, one webhook endpoint. What a connector contributes is where the
  facts come from, not how they are written down - so provenance, identity and freshness are
  properties of the mechanism rather than things each connector has to remember.

  A fact that arrives without a run behind it cannot be traced, and a fact that is merely absent from
  the next sync must not silently vanish. Both are asserted here.

  Background:
    Given the fake connector is registered with capabilities FULL, INCREMENTAL and WEBHOOK

  Scenario: A manual full sync records a run and stamps every fact it wrote
    Given the fake connector will return 2 Repository nodes and 1 DEPENDS_ON edge with watermark "2026-09-01T10:00:00Z"
    When I ask the fake connector for a full sync
    Then the response status is 202
    And the body field "syncRunId" is present
    And the sync run finishes with status "SUCCESS"
    And the sync run recorded 2 nodes and 1 edge
    And every node it wrote has provenance sourceSystem "fake" and the run's id
    And every node it wrote has confidence 1.0 and is not inferred
    And the stored watermark for "fake" is "2026-09-01T10:00:00Z"

  Scenario: An incremental sync asks the connector only for what changed since the watermark
    Given the connector state for "fake" has watermark "2026-09-01T10:00:00Z"
    When I ask the fake connector for an incremental sync
    And the sync run finishes with status "SUCCESS"
    Then the fake connector was asked for changes since "2026-09-01T10:00:00Z"

  Scenario: A full sync asks for everything, whatever the watermark says
    Given the connector state for "fake" has watermark "2026-09-01T10:00:00Z"
    When I ask the fake connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the fake connector was asked for changes since nothing

  Scenario: Two runs of the same connector do not overlap
    Given a sync run for "fake" is already RUNNING
    When I ask the fake connector for a full sync
    Then the response status is 409

  Scenario: A tombstone closes a fact's validity rather than deleting it
    Given the fake connector previously produced a Repository "github.com/acme/legacy"
    And the fake connector will return a tombstone for Repository "github.com/acme/legacy"
    When I ask the fake connector for an incremental sync
    And the sync run finishes with status "SUCCESS"
    Then the tombstoned Repository "github.com/acme/legacy" still exists
    And its provenance validTo is set

  Scenario: A page that fails leaves the run partial rather than losing the pages that worked
    Given the fake connector will return one good delta and then fail
    When I ask the fake connector for a full sync
    And the sync run finishes with status "PARTIAL"
    Then the sync run recorded 1 nodes and 0 edge

  Scenario: Connectors are listed with their state
    When I list the connectors
    Then the response status is 200
    And the listed connectors include "fake" with sourceSystem "fake"

  Scenario: An unknown connector is not found
    When I ask for a connector named "nope"
    Then the response status is 404

  Scenario: A webhook with a bad signature is refused without reaching the connector
    When a webhook arrives for the fake connector signed with "the-wrong-secret"
    Then the response status is 401
    And the fake connector was not asked to handle a webhook

  Scenario: A webhook with a good signature is applied as its own run
    When a webhook arrives for the fake connector signed correctly
    Then the response status is 202
    And the sync run finishes with status "SUCCESS"
    And the sync run has mode "WEBHOOK"
