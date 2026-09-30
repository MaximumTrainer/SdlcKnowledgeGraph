Feature: the source systems a write may name
  Every fact's provenance names the system of record that stated it (#117). The systems the graph
  knows are declared once, in the registry's sources.yaml, and published with the ontology, so a
  connector author can see which name to stamp and which graph:write:<source> scope to ask for.

  Naming a source the registry does not declare is a malformed write, refused with 400 whether or not
  authentication is on. Which principal may name which source is a question of scopes, so with the
  development bypass (this suite) any declared source may be named: the bypass checks no scopes.

  Scenario: the ontology publishes the source systems
    When I GET "/api/v1/ontology"
    Then the response status is 200
    And the ontology declares the source systems:
      | manual               |
      | github               |
      | github-actions       |
      | servicenow           |
      | aws                  |
      | dogfood-seed         |
      | sdlc-knowledge-graph |

  Scenario: a write naming an undeclared source is refused through the bypass too
    When I POST a Team named "platform" with sourceSystem "jira"
    Then the response status is 400
    And the body field "error" is "unknown source system"
    And the body field "sourceSystem" is "jira"
    And the body field "known" contains "manual"
    And the body field "known" contains "aws"

  Scenario: the bypass checks no source scopes
    When I POST a Team named "platform" with sourceSystem "aws"
    Then the response status is 201
    And the created node's provenance names sourceSystem "aws"

  Scenario: a write that names no source is manual
    When I POST a node of type "Team" with name "platform"
    Then the response status is 201
    And the created node's provenance names sourceSystem "manual"
