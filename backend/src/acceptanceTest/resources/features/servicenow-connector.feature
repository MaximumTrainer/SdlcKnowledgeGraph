Feature: ServiceNow connector - configuration items, changes and incidents
  The CMDB is where an enterprise has already written down what its services are called and who
  supports them. Reading it is how the graph stops being a developer's view of the estate and starts
  being the same estate operations already argues about.

  What makes it worth connecting is not the inventory. It is that a change request and an incident
  both point at a configuration item, so "was this outage something we approved" becomes a traversal
  rather than a meeting.

  Background:
    Given the ServiceNow connector is registered

  Scenario: A full sync records configuration items with what the CMDB knows about them
    Given ServiceNow has a "cmdb_ci_service" called "Payments Service" with sys_id "s1"
    When I ask the ServiceNow connector for a full sync
    Then the sync run finishes with status "SUCCESS"
    And the graph has ConfigurationItem "servicenow:sn.example.test:s1"
    And that ConfigurationItem has ciClass "cmdb_ci_service" and provenance sourceSystem "servicenow"

  Scenario: A configuration item naming a repository is linked to it
    Given a Repository "github.com/acme/payments" exists
    And ServiceNow has a "cmdb_ci_app" called "payments-api" with sys_id "a1"
    And that configuration item names the repository "https://github.com/Acme/Payments.git"
    When I ask the ServiceNow connector for a full sync
    And the sync run finishes with status "SUCCESS"
    # Not full confidence: it rests on somebody having filled in a custom field correctly, which is a
    # better guess than a name match and still a guess.
    Then "github.com/acme/payments" RELATES_TO_CI "servicenow:sn.example.test:a1" with confidence 0.95

  Scenario: A repository the graph has never seen is not invented
    Given ServiceNow has a "cmdb_ci_app" called "ghost" with sys_id "g1"
    And that configuration item names the repository "https://github.com/acme/never-seen.git"
    When I ask the ServiceNow connector for a full sync
    And the sync run finishes with status "SUCCESS"
    # A CMDB field is not evidence that a repository exists. Creating one from it would fill the graph
    # with repositories nobody can open.
    Then the graph has no Repository "github.com/acme/never-seen"
    And "servicenow:sn.example.test:g1" is related to no repository

  Scenario: A CMDB relationship becomes a dependency
    Given ServiceNow has a "cmdb_ci_service" called "Payments Service" with sys_id "s1"
    And ServiceNow has a "cmdb_ci_app" called "payments-api" with sys_id "a1"
    And ServiceNow relates "s1" to "a1" as "Depends on::Used by"
    When I ask the ServiceNow connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then "servicenow:sn.example.test:s1" DEPENDS_ON "servicenow:sn.example.test:a1" with kind "cmdb"

  Scenario: A relationship to something outside the synced tables is skipped
    Given ServiceNow has a "cmdb_ci_app" called "payments-api" with sys_id "a1"
    And ServiceNow relates "a1" to "unknown-ci" as "Depends on::Used by"
    When I ask the ServiceNow connector for a full sync
    # Skipped rather than failed: a CMDB relates to everything, including tables nobody asked for, and
    # an edge to a node that does not exist is worse than no edge.
    Then the sync run finishes with status "SUCCESS"
    And "servicenow:sn.example.test:a1" depends on nothing

  Scenario: A change and an incident point at the same configuration item
    Given ServiceNow has a "cmdb_ci_app" called "payments-api" with sys_id "a1"
    And ServiceNow has change "CHG0001" with sys_id "c1" affecting "a1"
    And ServiceNow has incident "INC0001" with sys_id "i1" affecting "a1" caused by "c1"
    When I ask the ServiceNow connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then "servicenow:sn.example.test:c1" AFFECTS "servicenow:sn.example.test:a1"
    And "servicenow:sn.example.test:i1" AFFECTS "servicenow:sn.example.test:a1"
    And "servicenow:sn.example.test:i1" CAUSED_BY "servicenow:sn.example.test:c1"

  Scenario: A retired configuration item is closed, not deleted
    Given ServiceNow has a "cmdb_ci_app" called "payments-api" with sys_id "a1"
    And that configuration item is "Retired"
    When I ask the ServiceNow connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the graph has ConfigurationItem "servicenow:sn.example.test:a1"
    And that ConfigurationItem has its validity closed

  Scenario: Every page is read, not just the first
    Given ServiceNow has 250 "cmdb_ci_app" rows
    When I ask the ServiceNow connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the sync run recorded 250 nodes

  Scenario: An incremental sync asks only for what changed
    Given ServiceNow has a "cmdb_ci_app" called "payments-api" with sys_id "a1"
    And a full sync of ServiceNow has already run
    When I ask the ServiceNow connector for an incremental sync
    And the sync run finishes with status "SUCCESS"
    # The whole point of a watermark. Without this the connector re-reads an enterprise CMDB every
    # quarter of an hour, which is the fastest way to have the connector switched off.
    Then ServiceNow was asked for rows updated since the stored watermark
