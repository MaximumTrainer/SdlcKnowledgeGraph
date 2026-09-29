Feature: Seeding a read-only instance
  The dogfood instance is read-only, so nothing the public sends can change it (#48, D5). Until the
  GitHub connector (#23) can read this repository for itself, a seed script writes what it knows
  about it: the repository, the teams in CODEOWNERS, the workflows, and the repositories it depends
  on (#47, FR10). It writes through its own endpoint, behind the ingest token, and may write only
  those facts.

  Every fact is credited to "dogfood-seed", so a seeded fact can be told from a connector's later.

  Background:
    Given the seed ingest token is "test-ingest-token"

  Scenario: A seed records the repository, its owners and its pipelines
    When the seed posts repository "https://github.com/MaximumTrainer/SdlcKnowledgeGraph" owned by team "maximumtrainer" with pipeline ".github/workflows/ci.yml"
    Then the seed is accepted and created is true
    And Repository "github.com/maximumtrainer/sdlcknowledgegraph" has provenance sourceSystem "dogfood-seed"
    And Repository "github.com/maximumtrainer/sdlcknowledgegraph" is OWNED_BY Team "maximumtrainer"
    And Repository "github.com/maximumtrainer/sdlcknowledgegraph" HAS_PIPELINE ".github/workflows/ci.yml"

  Scenario: Seeding again leaves the node and edge counts unchanged
    Given the seed posted repository "https://github.com/MaximumTrainer/SdlcKnowledgeGraph" owned by team "maximumtrainer" with pipeline ".github/workflows/ci.yml"
    When the seed posts it again with the pipeline's last run status "failure"
    Then the seed is accepted and created is true
    And there are 3 seeded nodes and 2 seeded edges

  Scenario: A dependency on another repository is recorded with the manifest it came from
    When the seed posts repository "github.com/acme/web" depending on "github.com/acme/ui-kit" read from "frontend/package.json"
    Then the seed is accepted and created is true
    And Repository "github.com/acme/web" DEPENDS_ON "github.com/acme/ui-kit" with kind "library" and manifest "frontend/package.json"

  Scenario: A seed without the token is refused
    When the seed posts without an Authorization header
    Then the response status is 401
    And there is no Team node

  Scenario: A seed that breaks the ontology is refused, saying why
    When the seed posts a Repository with no defaultBranch
    Then the response status is 400
    And the errors say "nodes[0].props.defaultBranch"

  Scenario: A seed may write only what seeding is for
    When the seed posts a node of type "Deployment"
    Then the response status is 400
    And the errors say "Deployment cannot be seeded"
