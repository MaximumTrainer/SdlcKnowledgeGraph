Feature: GitHub connector - dependency manifests and infrastructure as code
  What a repository depends on, and what infrastructure it claims to own, are both written down in
  files inside the repository. Reading them is how the graph learns the dependency edges nobody would
  maintain by hand, and how the link engine later gets evidence to attach cloud resources to code.

  A manifest says what a repository asks for. It does not say what is deployed, and it does not say
  the dependency is current - so everything here is recorded as read, from the file it was read from,
  and a dependency on something this organisation publishes is marked as the inference it is.

  Background:
    Given the GitHub connector is registered for org "acme"

  Scenario: An npm manifest becomes libraries the repository depends on
    Given GitHub has repository "Payments" with topics "node"
    And the file "package.json" in "Payments" contains:
      """
      {
        "name": "@acme/payments",
        "dependencies": { "express": "^4.18.0" },
        "devDependencies": { "vitest": "3.2.4" }
      }
      """
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the graph has Library "npm:express"
    And "github.com/acme/payments" DEPENDS_ON "npm:express" from "package.json" at version "^4.18.0" with scope "runtime"
    And "github.com/acme/payments" DEPENDS_ON "npm:vitest" from "package.json" at version "3.2.4" with scope "dev"

  Scenario: A dependency on something this organisation publishes resolves to the repository
    Given GitHub has repository "Payments" with topics "node"
    And GitHub also has repository "Billing" with topics "node"
    And the file "package.json" in "Payments" contains:
      """
      { "name": "@acme/payments", "dependencies": { "@acme/billing": "1.2.3" } }
      """
    And the file "package.json" in "Billing" contains:
      """
      { "name": "@acme/billing", "version": "1.2.3" }
      """
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then "github.com/acme/payments" DEPENDS_ON Repository "github.com/acme/billing"
    # A guess from a naming convention, not a fact GitHub reported. Recorded as such so a reviewer can
    # tell it from a dependency read straight out of a manifest.
    And that dependency is marked inferred with confidence 0.9
    And the graph has no Library "npm:@acme/billing"

  Scenario: A Gradle build file becomes maven libraries
    Given GitHub has repository "Payments" with topics "kotlin"
    And the file "build.gradle.kts" in "Payments" contains:
      """
      dependencies {
          implementation("org.springframework.boot:spring-boot-starter-web:3.5.16")
          testImplementation("io.mockk:mockk:1.13.12")
      }
      """
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then "github.com/acme/payments" DEPENDS_ON "maven:org.springframework.boot:spring-boot-starter-web" from "build.gradle.kts" at version "3.5.16" with scope "runtime"
    And "github.com/acme/payments" DEPENDS_ON "maven:io.mockk:mockk" from "build.gradle.kts" at version "1.13.12" with scope "dev"

  Scenario: A Terraform file is indexed with the resources it names
    Given GitHub has repository "Payments" with topics "terraform"
    And the file "infra/main.tf" in "Payments" contains:
      """
      resource "aws_s3_bucket" "receipts" {
        bucket = "acme-payments-receipts"
      }
      """
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the graph has IacFile "github.com/acme/payments:infra/main.tf" in format "terraform"
    And that IacFile names resource "aws_s3_bucket.receipts"
    And that IacFile names resource "acme-payments-receipts"
    And "github.com/acme/payments" CONTAINS_IAC "github.com/acme/payments:infra/main.tf"

  Scenario: A repository with nothing to read produces no dependencies
    Given GitHub has repository "Empty" with topics "docs"
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "SUCCESS"
    Then the graph has Repository "github.com/acme/empty"
    And "github.com/acme/empty" depends on nothing

  Scenario: A manifest nobody can parse costs that repository, not the run
    Given GitHub has repository "Payments" with topics "node"
    And GitHub also has repository "Broken" with topics "node"
    And the file "package.json" in "Payments" contains:
      """
      { "name": "@acme/payments", "dependencies": { "express": "^4.18.0" } }
      """
    And the file "package.json" in "Broken" contains:
      """
      { this is not json at all
      """
    When I ask the GitHub connector for a full sync
    And the sync run finishes with status "PARTIAL"
    # The point of a page per repository: one unreadable manifest must not lose the repositories that
    # were read before it.
    Then the graph has Library "npm:express"
    And the graph has Repository "github.com/acme/broken"
