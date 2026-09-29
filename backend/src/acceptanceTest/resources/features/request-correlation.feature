@correlation
Feature: Request correlation
  A request that fails has to be followable from the edge through the API to the store, so every
  request carries an id: the client's X-Request-Id when it is safe to write into a log, a fresh one
  otherwise. It is echoed in the response and on every log line written while answering (#44).

  Scenario: A client's correlation id is echoed and used
    When I GET "/api/v1/ontology" with X-Request-Id "abc-123"
    Then the response header X-Request-Id is "abc-123"
    And every log line written while answering carries requestId "abc-123"

  Scenario: A request without a correlation id is given one
    When I GET "/api/v1/ontology" with no X-Request-Id
    Then the response header X-Request-Id is a safe correlation id
    And every log line written while answering carries that id

  Scenario Outline: A correlation id that is not safe to log is replaced, not trusted
    When I GET "/api/v1/ontology" with X-Request-Id "<hostile>"
    Then the response header X-Request-Id is a safe correlation id
    And it is not "<hostile>"

    Examples:
      | hostile                                                                   |
      | a level=ERROR forged=true                                                 |
      | {'level':'ERROR'}                                                         |
      | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa |

