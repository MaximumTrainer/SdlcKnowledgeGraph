Feature: Identity survives real-world data
  Derived identity is the graph's strongest property, and a real estate bends it in four places
  (#98): a monorepo provides several services from different directories, an artifact is published
  without a digest, teams spell their environments differently, and two nodes turn out to be the
  same thing. A merge folds one node into another the way a rename moves one (#88): every edge moves
  to the node that stays, the key it leaves is recorded in previousKeys and still resolves, and the
  node it leaves is retired with the reason "merged" (#33) rather than deleted.

  Merging is destructive, so it needs graph:admin as well as graph:write. The main suite's test
  principal holds both.

  Scenario: a monorepo provides two services
    Given Repository "acme/platform" PROVIDES Service "billing" with path "services/billing"
    And Repository "acme/platform" PROVIDES Service "notify" with path "services/notify"
    When the edges of Repository "github.com/acme/platform" are listed
    Then both PROVIDES edges are returned with their paths:
      | service | path             |
      | billing | services/billing |
      | notify  | services/notify  |

  Scenario: a digest-less artifact is folded into its digest-keyed node
    Given Artifact "payments:1.4.2" with identityQuality version-only
    And that Artifact is BUILT_FROM Repository "acme/payments"
    When an Artifact "ghcr.io/acme/payments@sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730" is observed with name "payments" and version "1.4.2"
    Then the version-only node is merged into the digest node
    And resolve_node "payments:1.4.2" returns the digest node
    And the digest node is BUILT_FROM Repository "github.com/acme/payments"
    And the digest node has identityQuality "digest"

  Scenario: an artifact that differs in more than its digest is not folded
    Given Artifact "payments:1.4.2" with identityQuality version-only and commitSha "9fceb02d0ae598e95dc970b74767f19372d61af8"
    When an Artifact "ghcr.io/acme/payments@sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730" is observed with name "payments", version "1.4.2" and commitSha "1111111111111111111111111111111111111111"
    Then Artifact "payments:1.4.2" is still current

  Scenario: merge refuses conflicting identities
    Given two Repository nodes with different host values
    When one is merged into the other
    Then the response is 409 with the conflicting fields "host"

  Scenario: a merge moves the edges, records the old key and keeps it resolving
    Given the Repository at "acme/payments" is OWNED_BY Team "billing"
    And a Repository exists at "acme/payments-service"
    When Repository "github.com/acme/payments" is merged into "github.com/acme/payments-service"
    Then the response status is 200
    And Repository "github.com/acme/payments-service" is OWNED_BY Team "billing"
    And Repository "github.com/acme/payments" has no edges left
    And Repository "github.com/acme/payments-service" lists previousKeys containing "github.com/acme/payments"
    And resolving Repository "github.com/acme/payments" returns "github.com/acme/payments-service"
    And the history of Repository "github.com/acme/payments" shows it retired as "merged" into "Repository:github.com/acme/payments-service" by the acting principal

  Scenario: a dry run previews a merge and changes nothing
    Given the Repository at "acme/payments" is OWNED_BY Team "billing"
    And a Repository exists at "acme/payments-service"
    When Repository "github.com/acme/payments" is merged into "github.com/acme/payments-service" as a dry run
    Then the response status is 200
    And the merge preview moves 1 edge and adds previousKey "github.com/acme/payments"
    And Repository "github.com/acme/payments" is still current
    And Repository "github.com/acme/payments-service" has no edges left

  Scenario Outline: a merge that makes no sense is refused
    Given a Repository exists at "acme/payments"
    And a Repository exists at "acme/retired"
    And Repository "github.com/acme/retired" has been closed
    And a Team node exists with name "billing"
    When Repository "github.com/acme/payments" is merged into "<into>"
    Then the response status is <status>
    And the body field "error" is "<error>"

    Examples:
      | into                                | status | error                     |
      | github.com/acme/payments            | 400    | invalid merge             |
      | Team:billing                        | 400    | invalid merge             |
      | github.com/acme/retired             | 409    | merge into a retired node |
      | github.com/acme/nothing             | 404    | node not found            |

  Scenario: environment aliases are served with the ontology
    When I GET the ontology
    Then environment "production" is served with aliases "prod", "prd" and "live"

  Scenario: an environment alias still names its canonical environment
    When I POST an Environment named "prd" of type "production"
    Then the response status is 201
    And the body field "key" is "production"
