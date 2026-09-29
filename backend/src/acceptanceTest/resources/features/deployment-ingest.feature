Feature: Deployment self-ingestion
  "Why did the deployment fail" has no answer in a graph that never hears about deployments. The
  pipeline that deploys this project is the graph's first producer of them: after every deploy it
  posts what it deployed, from which commit, to where, and whether it worked (#7).

  Every fact carries provenance from github-actions and the run that reported it, so an answer can
  always say where it came from.

  Background:
    Given the deployment ingest token is "test-ingest-token"

  Scenario: A deployment is recorded with its lineage
    When the pipeline reports artifact "ghcr.io/maximumtrainer/sdlc-graph-backend@sha256:abc" from commit "c1" deployed to "staging" with status "SUCCESS"
    Then the ingest is accepted and created is true
    And Artifact "ghcr.io/maximumtrainer/sdlc-graph-backend@sha256:abc" is BUILT_FROM "github.com/maximumtrainer/sdlcknowledgegraph" at commit "c1"
    And a Deployment of that artifact is TO_ENVIRONMENT "staging"
    And the repository's deployments list 1 deployment with status "SUCCESS" from "github-actions"

  Scenario: Posting the same deployment twice is idempotent
    Given the pipeline reported artifact "ghcr.io/maximumtrainer/sdlc-graph-backend@sha256:abc" from commit "c1" deployed to "staging" with status "SUCCESS"
    When the pipeline reports exactly the same deployment again
    Then the ingest is accepted and created is false
    And there is exactly 1 Deployment node and 1 BUILT_FROM edge

  Scenario: Environment aliases resolve to one node
    When the pipeline reports artifact "ghcr.io/maximumtrainer/sdlc-graph-backend@sha256:abc" from commit "c1" deployed to "prod" with status "SUCCESS"
    And the pipeline reports artifact "ghcr.io/maximumtrainer/sdlc-graph-backend@sha256:def" from commit "c2" deployed to "production" with status "SUCCESS"
    Then there is exactly one Environment node, with key "production"

  Scenario: A failed deployment is recorded
    When the pipeline reports a deployment with status "FAILED" from run "https://github.com/maximumtrainer/sdlcknowledgegraph/actions/runs/42"
    Then the ingest is accepted and created is true
    And the Deployment has status "FAILED" and provenance sourceId "https://github.com/maximumtrainer/sdlcknowledgegraph/actions/runs/42"

  Scenario: A report without the token is refused
    When the pipeline reports a deployment without an Authorization header
    Then the response status is 401
    And there is no Deployment node

  Scenario: A report with the wrong token is refused
    When the pipeline reports a deployment with the token "not-the-token"
    Then the response status is 401
    And there is no Deployment node

  Scenario: A report with no artifacts is refused, saying why
    When the pipeline reports a deployment with no artifacts
    Then the response status is 400
    And the errors say "artifacts must not be empty"
