Feature: least privilege on the graph
  The third slice of authentication (#116, ADR-0005). With every caller identified, what each may do
  is read from the OAuth 2 scopes its token carries: graph:read for every GET under /api/v1 and every
  GraphQL query, graph:write for every POST, PUT and DELETE under /api/v1 and every GraphQL mutation.
  A refusal is a 403 that says what the request needed and what the token held.

  The checks come in a fixed order: no valid token is a 401, a client nobody registered is refused as
  an unregistered service principal (rogue-agent holds no graph scopes, and is still told that), and
  only then are the scopes compared.

  The development realm gives dan both scopes, reader graph:read, visitor none, github-connector both,
  and triage-agent graph:read by default with graph:write only when it asks for it.

  Scenario: a read-only agent cannot write
    Given service principal "triage-agent" is registered with ownedBy "team-payments"
    And "triage-agent" holds a token with scope graph:read only
    When it POSTs a Deployment node
    Then the response is 403 with required ["graph:write"] and held ["graph:read"]

  Scenario: a read-only agent still reads
    Given service principal "triage-agent" is registered with ownedBy "team-payments"
    And "triage-agent" holds a token with scope graph:read only
    When it calls GET /api/v1/nodes/Repository
    Then the response is 200

  Scenario: a read-only agent queries GraphQL but cannot mutate through it
    Given service principal "triage-agent" is registered with ownedBy "team-payments"
    And "triage-agent" holds a token with scope graph:read only
    When it sends the GraphQL query "{ repositories { id } }"
    Then the response is 200
    When it sends the GraphQL query "mutation { deleteRepository(id: \"r1\") }"
    Then the response is 403 with required ["graph:write"] and held ["graph:read"]

  Scenario: a read-only user cannot write
    Given "reader" has signed in through the UI
    When the UI creates a Repository
    Then the response is 403 with required ["graph:write"] and held ["graph:read"]

  Scenario: a token with no graph scopes reads nothing
    Given a token with no graph scopes
    When GET /api/v1/nodes/Repository is called
    Then the response is 403 with required ["graph:read"] and held []

  Scenario: the ontology is always discoverable
    Given a token with no graph scopes
    When GET /api/v1/ontology is called
    Then the response is 200

  Scenario: every route is guarded
    When the route-guard test enumerates all mapped routes
    Then each route has a declared scope requirement or is on the explicit public allowlist:
      | method | route                         | why                                                   |
      | GET    | /api/v1/ontology              | the model, not the data in it; any caller may read it |
      | GET    | /api/v1/ontology/nodes/{type} | the model, not the data in it; any caller may read it |
      | POST   | /api/v1/ingest/deployment     | guarded by its own ingest token                       |
      | POST   | /api/v1/ingest/seed           | guarded by its own ingest token                       |
      | POST   | /api/v1/webhooks/{name}       | guarded by the sender's signature                     |
      | *      | /error                        | where the container forwards a request already let in |
      | *      | /api-docs                     | the API's description, not the data in it             |
      | *      | /api-docs.yaml                | the API's description, not the data in it             |
      | *      | /api-docs/swagger-config      | the API's description, not the data in it             |
      | GET    | /swagger-ui.html              | the API's description, not the data in it             |
