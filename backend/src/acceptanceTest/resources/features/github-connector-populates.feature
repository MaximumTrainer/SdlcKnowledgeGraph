Feature: GitHub connector populates the graph
  Every scenario the agent-systems white paper describes - scoping a cross-repository change,
  computing a blast radius, verifying an incident - depends on the graph being fed by a system of
  record rather than by hand. GitHub populates Repository, Team, Pipeline and DEPENDS_ON in one
  connector (#86), writing through the same registry-driven store and provenance as every other.

  What is reported is recorded as reported, at full confidence. What is worked out from a manifest is
  recorded as the inference it is. And a run that finds nothing new says so, rather than claiming to
  have written the estate again.

  Background:
    Given the GitHub connector is registered for org "acme"

  # The issue's three scenarios come first, in its words. "exists in the graph" rather than
  # "exists" for the team, because "a Team ... exists" already sets one up by hand elsewhere.
  Scenario: repositories and owners are upserted
    Given a GitHub organisation "acme" with repositories "payments" and "ledger"
    And "payments" has CODEOWNERS "@acme/team-payments"
    When the GitHub connector runs
    Then a Repository "github.com/acme/payments" exists with sourceSystem "github"
    And a Team "team-payments" exists in the graph
    And an OWNED_BY edge exists from the repository to the team

  Scenario: manifest dependencies become inferred edges with evidence
    Given a GitHub organisation "acme" with repositories "payments" and "ledger"
    And "payments" declares "@acme/ledger-client" in package.json
    And Repository "github.com/acme/ledger" publishes package "@acme/ledger-client"
    When the GitHub connector runs
    Then a DEPENDS_ON edge exists from "payments" to "ledger" with kind "library"
    And the edge has manifest "package.json" and inferred true and confidence less than 1.0

  Scenario: a re-run is idempotent
    Given a GitHub organisation "acme" with repositories "payments" and "ledger"
    And "payments" has CODEOWNERS "@acme/team-payments"
    And "payments" declares "@acme/ledger-client" in package.json
    And Repository "github.com/acme/ledger" publishes package "@acme/ledger-client"
    And "payments" has the workflow ".github/workflows/ci.yml"
    And the connector has run once
    When it runs again with no changes upstream
    Then the SyncRun reports zero written and the node count is unchanged

  Scenario: every workflow file becomes a pipeline of its repository
    Given a GitHub organisation "acme" with repositories "payments" and "ledger"
    And "payments" has the workflow ".github/workflows/ci.yml"
    And "payments" has the workflow ".github/workflows/release.yaml"
    # Only files directly under .github/workflows are workflows GitHub runs; a YAML file anywhere else
    # is not a pipeline, whatever its name.
    And "payments" has the workflow ".github/workflows/templates/shared.yml"
    And "payments" has the workflow "docs/workflows/notes.yml"
    When the GitHub connector runs
    Then a Pipeline "github-actions:github.com/acme/payments:.github/workflows/ci.yml" exists with sourceSystem "github"
    And "payments" HAS_PIPELINE ".github/workflows/ci.yml"
    And "payments" HAS_PIPELINE ".github/workflows/release.yaml"
    And "payments" has 2 pipelines

  Scenario: a dependency resolves to the repository that publishes it, whatever its name
    # No configured prefix covers "ledger-client". What makes it ours is that a repository in the
    # organisation publishes it, not that its name follows a convention.
    Given a GitHub organisation "acme" with repositories "payments" and "ledger"
    And "payments" declares "ledger-client" in package.json
    And Repository "github.com/acme/ledger" publishes package "ledger-client"
    When the GitHub connector runs
    Then a DEPENDS_ON edge exists from "payments" to "ledger" with kind "library"
    And the graph has no Library "npm:ledger-client"

  Scenario: every fact carries where it came from and when GitHub saw it
    Given a GitHub organisation "acme" with repositories "payments" and "ledger"
    And "payments" has CODEOWNERS "@acme/team-payments"
    And "payments" declares "@acme/ledger-client" in package.json
    And Repository "github.com/acme/ledger" publishes package "@acme/ledger-client"
    And "payments" has the workflow ".github/workflows/ci.yml"
    When the GitHub connector runs
    Then "payments" and every edge out of it carry sourceSystem "github", a sourceId and observedAt "2026-09-01T10:00:00Z"
    And every OWNED_BY and HAS_PIPELINE edge out of "payments" has confidence 1.0 and is not inferred

  Scenario: a fork is recorded with what it was forked from and is never a dependency target
    Given a GitHub organisation "acme" with repositories "payments" and "ledger"
    And a fork "ledger-fork" of "upstream/ledger"
    And "payments" declares "@acme/ledger-client" in package.json
    And Repository "github.com/acme/ledger" publishes package "@acme/ledger-client"
    # The fork publishes the same name, because a fork carries its upstream's manifest. Resolving to
    # it would point the dependency at a copy nobody ships.
    And Repository "github.com/acme/ledger-fork" publishes package "@acme/ledger-client"
    When the GitHub connector runs
    Then Repository "github.com/acme/ledger-fork" has forkOf "github.com/upstream/ledger"
    And a DEPENDS_ON edge exists from "payments" to "ledger" with kind "library"
    And no DEPENDS_ON edge points at "github.com/acme/ledger-fork"

  Scenario: the run records what it wrote, what it left unchanged, what failed, and which connector version ran
    Given a GitHub organisation "acme" with repositories "payments" and "ledger"
    And "payments" declares "express" in package.json
    And the package.json of "ledger" cannot be parsed
    When the GitHub connector runs and the run finishes "PARTIAL"
    Then the SyncRun reports 1 failed
    And the SyncRun reports more than 0 written
    And the SyncRun records the version the connector reports

  Scenario: an owner that is a user account rather than an organisation is read too
    # GitHub lists a user's repositories under /users rather than /orgs, and answers 404 for the
    # user asked about as an organisation. Pointing the connector at a person's account, as the
    # project's own dogfood instance does, has to work all the same.
    Given "acme" is a user account rather than an organisation, with repositories "payments" and "ledger"
    When the GitHub connector runs
    Then a Repository "github.com/acme/payments" exists with sourceSystem "github"
    And a Repository "github.com/acme/ledger" exists with sourceSystem "github"
