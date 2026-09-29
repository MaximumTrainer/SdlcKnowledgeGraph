Feature: The API refuses to start without a database to talk to
  A container started without NEO4J_URI would otherwise come up pointing at a default host that does
  not exist, pass its liveness probe and fail every request. Refusing to start, with a message that
  names the missing setting, turns that into a failed deploy the platform reports (#6, FR4).

  Scenario: The docker profile needs NEO4J_URI
    When the application starts with the "docker" profile and NEO4J_URI unset
    Then startup fails with a message containing "NEO4J_URI must be set"

  Scenario: The docker profile starts when NEO4J_URI is set
    When the application starts with the "docker" profile and NEO4J_URI set
    Then startup does not fail on the database URI
