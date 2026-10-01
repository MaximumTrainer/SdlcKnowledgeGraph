Feature: Data lifecycle
  A fact that changes keeps what it said before, a fact the source stops reporting is retired rather
  than deleted, closed facts past their retention can be archived, and the graph's data follows its
  ontology from one version to the next (#33).

  Each change of a node's properties keeps the values it replaced as a version, so a read as of an
  instant (#93) answers with the values that held then. Restating the same values adds nothing.
  A connector's rules for what it stops reporting are its own: the "fake-itsm" connector of the test
  profile waits seven days before retiring what a full sync no longer mentions. The archive of the
  test profile is enabled and purges, with the default retention of 365 days, and the migrations it
  reads are a fixture: V1_4_0__rename_ci_legacy_name moves a ConfigurationItem's legacy name into
  ciName.

  Scenario: A property change keeps the old value readable as of an earlier instant
    Given Repository "github.com/acme/payments" was stated with description "v1" at "2026-01-01T00:00:00Z"
    When Repository "github.com/acme/payments" is stated with description "v2" at "2026-02-01T00:00:00Z"
    Then the Repository "github.com/acme/payments" reads with description "v2"
    And the Repository "github.com/acme/payments" reads as of "2026-01-15T00:00:00Z" with description "v1"
    And the history of Repository "github.com/acme/payments" holds 1 earlier version, ending at "2026-02-01T00:00:00Z"

  Scenario: Stating the same values again adds no version
    Given Repository "github.com/acme/payments" was stated with description "v1" at "2026-01-01T00:00:00Z"
    When Repository "github.com/acme/payments" is stated with description "v1" at "2026-02-01T00:00:00Z"
    Then the history of Repository "github.com/acme/payments" holds 0 earlier versions

  Scenario: What a successful full sync stops reporting is retired only after the grace period
    Given the lifecycle status shows connector "fake-itsm" with grace period "P7D"
    And a full sync of "fake-itsm" recorded Teams "a1" and "a2"
    And "fake-itsm" last stated Team "a1" 8 days ago and Team "a2" 2 days ago
    When a full sync of "fake-itsm" succeeds without mentioning them
    Then Team "a1" is retired with reason "missing-from-sync"
    And Team "a2" is still current

  Scenario: What a partial run did not report is not retired
    Given a full sync of "fake-itsm" recorded Teams "a1" and "a2"
    And "fake-itsm" last stated Team "a1" 8 days ago and Team "a2" 2 days ago
    When a full sync of "fake-itsm" ends PARTIAL without mentioning them
    Then Team "a1" is still current

  Scenario: A retired node comes back, and its history keeps the retirement
    Given a full sync of "fake-itsm" recorded Teams "a1" and "a2"
    And "fake-itsm" last stated Team "a1" 8 days ago and Team "a2" 2 days ago
    And a full sync of "fake-itsm" succeeds without mentioning them
    When a full sync of "fake-itsm" reports Team "a1" again
    Then Team "a1" is current again, with resurrectedAt set
    And the history of Team "a1" lists a retired version

  Scenario: A tombstone the source reports retires the node and closes its current edges
    Given the fake connector recorded Repository "github.com/acme/legacy" OWNED_BY Team "platform"
    When the fake connector reports a tombstone for Repository "github.com/acme/legacy"
    Then Repository "github.com/acme/legacy" is retired with reason "source-deleted"
    And the OWNED_BY edge from Repository "github.com/acme/legacy" is closed

  Scenario: The archive dry run counts closed facts past their retention and changes nothing
    Given 10 CloudResources retired 400 days ago and 5 retired 10 days ago
    When an admin asks for an archive dry run
    Then the response status is 200
    And the archive would take 10 nodes
    And all 15 CloudResources are still in the graph

  Scenario: The archive writes old closed facts to a file and purges them
    Given 10 CloudResources retired 400 days ago and 5 retired 10 days ago
    When an admin runs the archive
    Then the response status is 200
    And the archive file it names holds 10 nodes
    And the 10 old CloudResources are gone and the 5 recent ones are still retired
    And a sync run of "lifecycle-archive" recorded 10 nodes

  Scenario: The lifecycle status is a read
    When I GET "/api/v1/lifecycle"
    Then the response status is 200
    And the body field "registryVersion" is "1.10.0"

  Scenario: A pending ontology migration is listed, applied by an admin and recorded
    Given the graph is on ontology version "1.3.0"
    And a ConfigurationItem "servicenow:sn.example.test:a1" holds the legacy name "Payments Service"
    When I GET "/api/v1/lifecycle/migrations"
    Then the migration "V1_4_0__rename_ci_legacy_name" is pending
    When an admin applies the pending migrations
    Then the response status is 200
    And the ConfigurationItem "servicenow:sn.example.test:a1" has ciName "Payments Service" and no name
    And the graph is on ontology version "1.10.0" with "V1_4_0__rename_ci_legacy_name" applied and checksummed

  Scenario: An applied migration whose file has changed since is refused
    Given the graph is on ontology version "1.3.0"
    And migration "1.4.0" was recorded as applied with a checksum of another file
    When an admin applies the pending migrations
    Then the response status is 409
    And the body names "V1_4_0__rename_ci_legacy_name" and "checksum"
