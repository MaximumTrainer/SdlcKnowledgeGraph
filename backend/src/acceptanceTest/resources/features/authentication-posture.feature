Feature: an instance either knows who is calling or accepts no writes
  Authentication is on by default (#118). An instance trusts the identity provider named by
  AUTH_ISSUER_URI, or, with none, runs the anonymous read-only mode: it serves reads to anyone and
  refuses every write, so no new fact can ever be written by nobody. There is no third way. The
  development bypass AUTH_DISABLED is gone, and an instance with no identity provider that would
  accept writes refuses to start rather than serve (#48, FR6).

  Scenario: no anonymous path remains
    Given AUTH_DISABLED=true
    When the backend starts
    Then startup fails with an unknown-setting error naming "AUTH_DISABLED"

  Scenario: the removed setting is refused under its property name too
    Given the property sdlc.auth.disabled=false
    When the backend starts
    Then startup fails with an unknown-setting error naming "AUTH_DISABLED"

  Scenario: an unauthenticated writable instance refuses to start
    Given no identity provider and SDLC_READ_ONLY=false
    When the backend starts
    Then startup fails with a message containing "AUTH_ISSUER_URI"
    And startup fails with a message containing "SDLC_READ_ONLY"

  Scenario: without an identity provider a read-only instance starts
    Given no identity provider and SDLC_READ_ONLY=true
    When the backend starts
    Then startup does not fail on authentication

  Scenario: with an identity provider a writable instance starts
    Given the identity provider "https://id.example.test/realms/sdlc" and SDLC_READ_ONLY=false
    When the backend starts
    Then startup does not fail on authentication
