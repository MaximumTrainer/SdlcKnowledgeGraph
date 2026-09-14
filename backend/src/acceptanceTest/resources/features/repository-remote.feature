Feature: Repository identity from its git remote
  A Repository is the anchor of this graph, so the same repository named by two systems must reduce
  to one node. The git remote is what those systems agree on, even though each writes it differently:
  a browser URL, an SSH remote, a bare org/name shorthand. Every form normalises to one canonical key
  before anything is stored, so a later connector has something to match against.

  Scenario Outline: Any remote form normalises to one canonical key
    When I POST a Repository with url "<input>"
    Then the response status is 201
    And the repository key is "github.com/acme/payments"
    And the repository url is "https://github.com/acme/payments"

    Examples:
      | input                                |
      | https://github.com/Acme/Payments.git |
      | https://github.com/acme/payments     |
      | git@github.com:acme/payments.git     |
      | ssh://git@github.com/acme/payments   |
      | github.com/acme/payments             |
      | acme/payments                        |

  Scenario: The parts of the remote are stored, not only the key
    When I POST a Repository with url "git@github.com:Acme/Payments.git"
    Then the response status is 201
    And that repository has host "github.com" and org "acme" and name "payments"

  Scenario: A second registration of the same remote is refused
    Given a Repository exists for "https://github.com/acme/payments"
    When I POST a Repository with url "git@github.com:Acme/Payments.git"
    Then the response status is 409
    And the body field "existingId" is the id of the repository created earlier
    And exactly 1 node of type "Repository" has key "github.com/acme/payments"

  Scenario Outline: Something that is not a git remote is refused
    When I POST a Repository with url "<input>"
    Then the response status is 400
    And the body field "error" is "invalid git remote"

    Examples:
      | input                        |
      | https://example.com/page     |
      | ftp://github.com/acme/things |
      | acme/pay ments               |

  Scenario: A repository is found by its canonical key
    Given a Repository exists for "https://github.com/acme/payments"
    When I GET the repository by key "github.com/acme/payments"
    Then the response status is 200
    And the repository key is "github.com/acme/payments"

  Scenario: Lookup normalises the key it is given
    Given a Repository exists for "https://github.com/acme/payments"
    When I GET the repository by key "Acme/Payments"
    Then the response status is 200
    And the repository key is "github.com/acme/payments"

  Scenario: Looking up a repository that is not in the graph
    When I GET the repository by key "github.com/acme/nothing-here"
    Then the response status is 404

  Scenario: The remote cannot be repointed at a different repository
    Given a Repository exists for "https://github.com/acme/payments"
    When I PUT that repository with url "https://github.com/acme/billing"
    Then the response status is 409
    And the body field "fields" contains "url"
