Feature: GitHub connector - repositories, teams and ownership
  The first real connector on the SPI. GitHub is where Repository and Team facts originate, so this
  is where the graph stops being hand-maintained and starts reflecting something.

  What matters is not that the HTTP works. It is that the same repository seen twice is one node,
  that ownership comes from CODEOWNERS rather than from a guess, and that a repository going away is
  recorded as having gone away rather than vanishing.

  Background:
    Given the GitHub connector is registered for org "acme"

  Scenario: A full sync creates repositories with their topics and provenance
    Given GitHub has repository "Payments" with topics "java" and "payments"
    When I ask the GitHub connector for a full sync
    Then the sync run finishes with status "SUCCESS"
    And the graph has Repository "github.com/acme/payments"
    And that Repository has topics "java" and "payments"
    And that Repository has provenance sourceSystem "github" and confidence 1.0

  Scenario: CODEOWNERS becomes team ownership
    Given GitHub has repository "Payments" with topics "java"
    And GitHub has CODEOWNERS for "Payments" containing "* @acme/platform-team"
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the graph has Team "github.com/acme/platform-team"
    And "github.com/acme/payments" is OWNED_BY "github.com/acme/platform-team"

  Scenario: A repository with no CODEOWNERS gets no ownership edge
    Given GitHub has repository "Orphan" with topics "go"
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then "github.com/acme/orphan" is owned by nobody

  Scenario: An individual owner is not mistaken for a team
    Given GitHub has repository "Payments" with topics "java"
    And GitHub has CODEOWNERS for "Payments" containing "* @some-person @acme/platform-team"
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the graph has Team "github.com/acme/platform-team"
    And the graph has no Team "github.com/acme/some-person"

  Scenario: An archived repository is closed, not deleted
    Given GitHub has repository "Payments" with topics "java"
    And GitHub also has archived repository "Legacy"
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the graph has Repository "github.com/acme/legacy"
    And that Repository has its validity closed

  Scenario: The same repository synced twice is still one node
    Given GitHub has repository "Payments" with topics "java"
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    And I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then exactly 1 node of type "Repository" has key "github.com/acme/payments"

  Scenario: Every page of repositories is read, not just the first
    Given GitHub has 150 repositories across two pages
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the sync run recorded 150 nodes

  Scenario: A full sync records where it got to, so the next one can be incremental
    Given GitHub has repository "Payments" with topics "java"
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the stored watermark for "github" is set
