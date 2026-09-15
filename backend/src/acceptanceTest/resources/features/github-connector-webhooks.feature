Feature: GitHub connector - webhooks
  A schedule tells the graph what was true a quarter of an hour ago. A webhook tells it what is true
  now, which is the difference between a graph somebody consults and a graph somebody trusts.

  Two things have to hold before any of that is worth having. An event nobody can prove came from
  GitHub must never reach the connector at all, and the same event delivered twice - which GitHub
  does, by design, whenever it is unsure - must not be applied twice.

  Background:
    Given the GitHub connector is registered for org "acme"

  Scenario: A signed repository event records the repository
    Given GitHub has repository "Payments" with topics "java"
    When a GitHub "repository" event for "Payments" arrives, correctly signed
    Then the webhook is accepted
    And the webhook run finishes with status "SUCCESS"
    And the graph has Repository "github.com/acme/payments"

  Scenario: An event nobody signed is refused
    Given GitHub has repository "Payments" with topics "java"
    When a GitHub "repository" event for "Payments" arrives with no signature
    Then the webhook is refused as unverified
    And no Repository "github.com/acme/payments" was recorded

  Scenario: An event signed with the wrong secret is refused
    Given GitHub has repository "Payments" with topics "java"
    When a GitHub "repository" event for "Payments" arrives signed with "not-the-secret"
    Then the webhook is refused as unverified
    And no Repository "github.com/acme/payments" was recorded

  Scenario: The same delivery twice is applied once
    Given GitHub has repository "Payments" with topics "java"
    When a GitHub "repository" event for "Payments" arrives, correctly signed
    And the webhook run finishes with status "SUCCESS"
    And GitHub redelivers that same event
    # GitHub redelivers whenever it is unsure the first attempt landed. Applying it again would write
    # the same facts under a second run, so "what did that run change" stops having one answer.
    Then the webhook is accepted
    And both deliveries name the same sync run
    And exactly 1 sync run names that delivery

  Scenario: An archived repository event closes the repository
    Given GitHub also has archived repository "Legacy"
    When a GitHub "repository" event for "Legacy" arrives, correctly signed
    And the webhook run finishes with status "SUCCESS"
    Then the graph has Repository "github.com/acme/legacy"
    And that Repository has its validity closed

  Scenario: A push that changes CODEOWNERS re-reads who owns the repository
    Given GitHub has repository "Payments" with topics "java"
    And GitHub has CODEOWNERS for "Payments" containing "* @acme/platform-team"
    When a GitHub "push" event for "Payments" touching "CODEOWNERS" arrives, correctly signed
    And the webhook run finishes with status "SUCCESS"
    Then the graph has Team "github.com/acme/platform-team"
    And "github.com/acme/payments" is OWNED_BY "github.com/acme/platform-team"

  Scenario: A push that changes nothing the graph reads is accepted and ignored
    Given GitHub has repository "Payments" with topics "java"
    When a GitHub "push" event for "Payments" touching "README.md" arrives, correctly signed
    # Accepted rather than refused: the event was genuine, it just says nothing this graph records.
    # Refusing it would have GitHub retrying something that will never mean anything.
    Then the webhook is accepted with nothing to do

  Scenario: An event type the connector does not handle is accepted and ignored
    Given GitHub has repository "Payments" with topics "java"
    When a GitHub "star" event for "Payments" arrives, correctly signed
    Then the webhook is accepted with nothing to do
