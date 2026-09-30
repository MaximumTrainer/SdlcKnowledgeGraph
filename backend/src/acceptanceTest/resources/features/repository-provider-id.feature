Feature: Repository identity survives a rename
  A Repository is keyed on its remote, `host/org/name`, which is the right identity to show and the
  wrong one to hold on to: a GitHub repository keeps its numeric id across renames and transfers
  between organisations, and a system that authenticates as a GitHub App addresses repositories by
  that id (#88). The provider id is an alias beside the key, unique where present, and a write or a
  lookup that supplies it finds the node that already holds it - so a rename moves the node rather
  than leaving every edge on an orphan.

  Scenario: Creating with a provider id
    When I POST a Repository with url "https://github.com/acme/payments", provider "github" and providerId "123456"
    Then the response status is 201
    And GET /api/v1/repositories/by-provider/github/123456 returns that node

  Scenario: A rename resolves to the same node
    Given a Repository with providerId "123456" and url "https://github.com/acme/payments"
    When I PUT that Repository with url "https://github.com/acme-platform/payments-service" and providerId "123456"
    Then the response status is 200
    And exactly one Repository node with providerId "123456" exists
    And its key is "github.com/acme-platform/payments-service"
    And its provenance lists previousKeys containing "github.com/acme/payments"

  Scenario: The old URL still finds it
    Given a Repository with providerId "123456" and url "https://github.com/acme/payments"
    And that Repository has been renamed to "https://github.com/acme-platform/payments-service"
    When I GET /api/v1/repositories?url=https://github.com/acme/payments
    Then the response returns the renamed node
    When I GET /api/v1/repositories?url=https://github.com/acme-platform/payments-service
    Then the response returns the renamed node
    When I GET the repository by key "acme/payments"
    Then the response status is 200
    And the repository key is "github.com/acme-platform/payments-service"

  Scenario: A rename keeps the edges on the node
    Given a Repository with providerId "123456" and url "https://github.com/acme/payments"
    And that Repository is owned by Team "billing"
    And that Repository has been renamed to "https://github.com/acme-platform/payments-service"
    When I GET the edges of "Repository" "github.com/acme-platform/payments-service" with direction "out"
    Then one edge is listed with displayName "OWNED_BY" and other key "billing"

  Scenario: Provider ids are unique
    Given a Repository with providerId "123456" and url "https://github.com/acme/payments"
    When I POST a Repository with url "https://github.com/other/thing" and providerId "123456"
    Then the response status is 409
    And the body field "existingId" is "Repository:github.com/acme/payments"

  Scenario: Without a provider id a changed url is still refused
    Given a Repository exists for "https://github.com/acme/payments"
    When I PUT that repository with url "https://github.com/acme-platform/payments-service"
    Then the response status is 409
    And the body field "error" is "identity properties are immutable"

  Scenario: orgRepo is derived, not accepted
    When I POST a Repository with url "https://github.com/acme/payments" and orgRepo "acme/payments"
    Then the response status is 400
    And the errors contain "orgRepo is derived from url"

  Scenario: orgRepo is still emitted for compatibility
    Given a Repository with providerId "123456" and url "https://github.com/acme/payments"
    When I GET /api/v1/repositories/by-provider/github/123456
    Then the body field "orgRepo" is "acme/payments"

  Scenario: Impact takes a provider id in place of a repository key
    Given a Repository with providerId "123456" and url "https://github.com/acme/payments"
    And that Repository is owned by Team "billing"
    And an Artifact from "github.com/acme/payments" deployed to Environment "production" with tier "production"
    When I POST /api/v1/impact with providerId "123456"
    Then the response status is 200
    And the impact is about "Repository:github.com/acme/payments"
    And the first hit is the "production" deployment

  Scenario: Impact still answers for a renamed repository under its old key
    Given a Repository with providerId "123456" and url "https://github.com/acme/payments"
    And an Artifact from "github.com/acme/payments" deployed to Environment "production" with tier "production"
    And that Repository has been renamed to "https://github.com/acme-platform/payments-service"
    When I POST /api/v1/impact with repositoryKey "github.com/acme/payments"
    Then the response status is 200
    And the impact is about "Repository:github.com/acme-platform/payments-service"
