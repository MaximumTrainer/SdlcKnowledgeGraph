Feature: deployments appear in the graph as they happen
  Incident verification turns on one question: did anything change, and when? That needs Artifact,
  Deployment and Environment nodes written by the pipeline that performed the deployment, with
  observedAt accurate to the minute and BUILT_FROM's commitSha set (#90). GitHub Actions and the
  GitHub Deployments API are that pipeline's record: the github-actions connector reads the workflow
  runs, the packages they published and the deployments they made, from a webhook as each run
  completes or from a polling cursor that catches what a webhook missed.

  Every fact is the source's: sourceSystem github-actions, the run's id as sourceId, and the run's
  completion as observedAt. The keys agree with what the deployment ingest (#7) writes, so a
  deployment reported both ways lands on the same Artifact, Environment and Pipeline nodes.

  Background:
    Given the GitHub organisation "acme" has repository "payments" for the CI connector

  # The issue's three scenarios come first, in its words.
  Scenario: a production deployment is recorded with its commit
    Given workflow run 4711 on "acme/payments" built image "ghcr.io/acme/payments@sha256:abc"
    And the run deployed that image to environment "prod"
    When the CI connector processes run 4711
    Then an Artifact "ghcr.io/acme/payments@sha256:abc" exists with BUILT_FROM commitSha "3c9a1f2"
    And a Deployment exists TO_ENVIRONMENT "production"
    And the Deployment provenance has sourceSystem "github-actions" and sourceId "4711"

  Scenario: the previous deployment is closed
    Given a current Deployment of "payments" to "production" with validTo null
    When a newer deployment of "payments" to "production" is processed
    Then the earlier Deployment has a non-null validTo equal to the newer deployedAt
    And the earlier Deployment was retired as "superseded"

  Scenario: an artifact without a digest is marked lower confidence
    Given a run that published "payments:1.4.2" with no digest
    When the connector processes it
    Then the Artifact key is "payments:1.4.2" and confidence is 0.8

  Scenario: every fact the run wrote carries the run and its completion
    Given workflow run 4711 on "acme/payments" built image "ghcr.io/acme/payments@sha256:abc"
    And the run deployed that image to environment "prod"
    When the CI connector processes run 4711
    Then the Artifact, the Deployment and the edges between them carry sourceSystem "github-actions", sourceId "4711" and the run's completion as observedAt

  Scenario: a deployment seen by webhook and then by the polling cursor is written once
    Given workflow run 4711 on "acme/payments" built image "ghcr.io/acme/payments@sha256:abc"
    And the run deployed that image to environment "prod"
    And the CI connector has processed run 4711
    When the CI connector polls with nothing new upstream
    Then the CI connector's run reports zero written
    And there is exactly 1 Deployment and 1 Artifact in the graph

  Scenario: a webhook that is not signed with the connector's secret is refused
    Given workflow run 4711 on "acme/payments" built image "ghcr.io/acme/payments@sha256:abc"
    And the run deployed that image to environment "prod"
    When run 4711's completion arrives signed with "not-the-secret"
    Then the CI webhook is refused with 401
    And there is exactly 0 Deployment and 0 Artifact in the graph
