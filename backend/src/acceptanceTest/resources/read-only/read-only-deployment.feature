Feature: A read-only deployment refuses every write
  With sdlc.read-only set, every write under /api/v1 and every GraphQL mutation is refused,
  deny-by-default: a write endpoint added later is refused until someone deliberately allows it.
  This is a posture for a public instance, not an authorisation model (#48, D5).

  This suite runs the way the dogfood instance does: read-only and with no identity provider, the
  anonymous read-only mode (#118). Nobody signs in, so every request here carries no token. Reads are
  answered for anyone; every write is refused as read-only, so no fact is ever written by nobody.

  Scenario Outline: Read-only refuses every write verb
    When I send a <verb> to "<path>"
    Then the response status is 403
    And the error is "this instance is read-only"

    Examples:
      | verb   | path                        |
      | POST   | /api/v1/nodes/Team          |
      | PUT    | /api/v1/nodes/Team/platform |
      | PATCH  | /api/v1/nodes/Team/platform |
      | DELETE | /api/v1/nodes/Team/platform |
      | POST   | /api/v1/edges               |
      | POST   | /api/v1/repositories        |
      | POST   | /api/v1/service-principals  |

  Scenario: A refused write leaves nothing behind
    When I send a POST to "/api/v1/nodes/Team"
    Then no node of type "Team" exists

  Scenario: Reading is unaffected
    When I send a GET to "/api/v1/nodes/Repository"
    Then the response status is 200

  Scenario: The sync run history can still be read
    # #29, FR5. A read like any other; the nightly prune of old runs (FR6) is not an HTTP request,
    # so this posture does not stop it either.
    When I send a GET to "/api/v1/sync-runs?connector=fake"
    Then the response status is 200

  Scenario: The graph view's neighbourhood is a read, answered for anyone
    # #9. The graph view works on an instance nobody signs in to, so its one new request is a GET
    # under /api/v1 like every other read: answered here, with a 400 for a depth outside 1 to 3.
    When I send a GET to "/api/v1/graph/neighbourhood?nodeId=Repository:github.com/acme/nothing&depth=4"
    Then the response status is 400

  Scenario: A write endpoint added later is refused without anyone remembering to refuse it
    When I send a POST to "/api/v1/a-write-endpoint-nobody-has-built-yet"
    Then the response status is 403
    And the error is "this instance is read-only"

  Scenario: A GraphQL mutation is refused
    When I send the GraphQL document "mutation { deleteRepository(id: \"github.com/acme/payments\") }"
    Then the response status is 403
    And the error is "this instance is read-only"

  Scenario: A GraphQL query is still answered
    When I send the GraphQL document "{ repositories { id } }"
    Then the response status is 200

  Scenario: The impact query is answered, although it is sent as a POST
    # #87. Its input is a structured body rather than a query string, but it only reads, so a
    # read-only instance answers it as it answers a GET: here with 404, the repository not being there.
    When I send the impact query for "github.com/acme/nothing"
    Then the response status is 404

  Scenario: GraphiQL is not served, so nothing invites writes that would be refused
    When I send a GET to "/graphiql"
    Then the response status is 404

  Scenario: The deployment says it is read-only, so a client can tell before it tries to write
    When I send a GET to "/actuator/info"
    Then the response status is 200
    And the deployment info says it is read-only

  Scenario: The deployment says it serves reads to anyone, so a client knows not to sign in
    When I send a GET to "/actuator/info"
    Then the response status is 200
    And the deployment info says it authenticates nobody

  Scenario: The deployment ingest endpoint stays open to the pipeline, behind its own token
    # The one write a read-only instance still takes (#7, docs/DEPLOYMENT.md D6). It is not refused
    # as read-only, and it is not open either: without its bearer token it is refused as unauthorised.
    When I send a POST to "/api/v1/ingest/deployment"
    Then the response status is 401

  Scenario: The seed endpoint stays open to the seed script, behind the same token
    # The dogfood seed (#47) writes this repository's own SDLC to a read-only instance. Like the
    # deployment ingest endpoint, it is allowed through and guards itself.
    When I send a POST to "/api/v1/ingest/seed"
    Then the response status is 401

  Scenario: A refused write still carries its correlation id
    # The refusal happens before any controller, so the id has to be assigned before the guard runs,
    # or the one request an operator most wants to trace is the one without an id (#44).
    When I send a POST to "/api/v1/nodes/Team" with X-Request-Id "refused-1"
    Then the response status is 403
    And the response header X-Request-Id is "refused-1"
