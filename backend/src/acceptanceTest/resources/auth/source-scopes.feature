Feature: connectors can only assert their own facts
  The fourth slice of authentication (#117, ADR-0005). A node or edge write may name the system of
  record it speaks for, as provenance.sourceSystem; a write that names none is "manual". Writing as
  manual needs graph:write, and writing as any other source needs graph:write:<source> as well, so a
  connector can assert only its own system's facts and a person no system's. The sources a write may
  name are declared in sources.yaml and served with the ontology; any other is refused with 400.

  A refusal is the 403 of #116: required is everything the write needs, held the graph scopes the
  token carries.

  The development realm gives github-connector graph:write:github and graph:write:github-actions,
  and no user and no other client any source scope.

  Background:
    Given service principal "github-connector" is registered with ownedBy "team-platform"

  Scenario: the GitHub connector cannot write AWS facts
    Given "github-connector" holds scopes graph:write and graph:write:github
    When it POSTs a CloudResource with sourceSystem "aws"
    Then the response is 403 with required ["graph:write", "graph:write:aws"] and held ["graph:read", "graph:write", "graph:write:github", "graph:write:github-actions"]

  Scenario: the GitHub connector asserts GitHub's facts
    Given "github-connector" holds scopes graph:write and graph:write:github
    When it POSTs a Repository with sourceSystem "github"
    Then the response is 201
    And the node's provenance has sourceSystem "github" and writtenBy "github-connector"

  Scenario: a connector cannot relate two nodes on another system's behalf
    Given "github-connector" holds scopes graph:write and graph:write:github
    When it relates a Repository to a Team with sourceSystem "servicenow"
    Then the response is 403 with required ["graph:write", "graph:write:servicenow"] and held ["graph:read", "graph:write", "graph:write:github", "graph:write:github-actions"]

  Scenario: a user cannot impersonate a system of record
    Given "dan" holds graph:read and graph:write
    When he POSTs a Repository with sourceSystem "github"
    Then the response is 403 with required ["graph:write", "graph:write:github"] and held ["graph:read", "graph:write"]

  Scenario: a user may name manual, which is what a write naming no source is
    Given "dan" holds graph:read and graph:write
    When he POSTs a Repository with sourceSystem "manual"
    Then the response is 201
    And the node's provenance has sourceSystem "manual" and writtenBy "dan"

  Scenario: an unknown source is refused explanatorily
    Given "dan" holds graph:read and graph:write
    When a write names sourceSystem "jira"
    Then the response is 400 and lists the known sources
