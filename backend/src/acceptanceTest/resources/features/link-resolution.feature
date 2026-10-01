Feature: Cloud to repository link resolution
  The same piece of infrastructure is described by a cloud, by the repository whose code defines it
  and by the pipeline that deployed to it, and only together do they say who owns it (#28). The link
  engine reads that evidence with pluggable rules - manual 1.0, tag 0.95, deployment record 0.9,
  infrastructure-as-code reference 0.7, naming convention 0.4 - keeps the strongest proposal for each
  resource and repository, and writes it as an inferred OWNS_RESOURCE at or above the threshold
  (0.5) or as a CANDIDATE_LINK below it, for a person to accept or reject.

  A cloud's tags are recorded on the resource as key=value entries (tags), and the tag rule reads
  the repository from the keys links.rules.tag.keys names: repo, repository, source-repo, git-repo.

  Background:
    Given Repository "github.com/acme/payments" exists with packageNames "payments"
    And Repository "github.com/acme/billing" exists

  Scenario: Highest confidence wins and becomes an inferred OWNS_RESOURCE
    Given CloudResource "aws:arn:aws:lambda:eu-west-1:1:function:payments-api" has tag_repo "github.com/acme/payments" and name "payments-api"
    When I POST /api/v1/links/resolve
    Then within 5 seconds an OWNS_RESOURCE edge from "github.com/acme/payments" to the resource exists with rule "tag", confidence 0.95, inferred true
    And no CANDIDATE_LINK exists for the resource

  Scenario: IaC reference links at 0.7
    Given CloudResource "aws:arn:aws:s3:::acme-payments-logs" with no repo tag
    And IacFile "github.com/acme/payments:infra/main.tf" with resourceRefs containing "acme-payments-logs"
    When I POST /api/v1/links/resolve
    Then within 5 seconds an OWNS_RESOURCE edge from "github.com/acme/payments" to the resource exists with rule "iac", confidence 0.7, inferred true
    And that OWNS_RESOURCE edge has evidence "path" "infra/main.tf"

  Scenario: Naming convention alone is only a candidate
    Given CloudResource "aws:arn:aws:sqs:eu-west-1:1:billing-prod" with name "billing-prod" and no tag or IaC evidence
    When I POST /api/v1/links/resolve
    Then within 5 seconds a CANDIDATE_LINK from the resource to "github.com/acme/billing" exists with rule "naming", confidence 0.4, status "pending"
    And no OWNS_RESOURCE edge exists for the resource

  Scenario: Conflicting strong evidence produces a conflict candidate
    Given CloudResource "azure:/subscriptions/s/resourcegroups/rg/providers/microsoft.web/sites/pay" has tag_repo "github.com/acme/payments"
    And IacFile "github.com/acme/billing:infra/app.bicep" with resourceRefs containing "pay"
    When I POST /api/v1/links/resolve
    Then within 5 seconds an OWNS_RESOURCE edge from "github.com/acme/payments" to the resource exists with rule "tag", confidence 0.95, inferred true
    And a CANDIDATE_LINK from the resource to "github.com/acme/billing" exists with status "conflict"

  Scenario: The resolution runs as a sync run of the link engine
    Given CloudResource "aws:arn:aws:sqs:eu-west-1:1:billing-prod" with name "billing-prod" and no tag or IaC evidence
    When I POST /api/v1/links/resolve
    Then the response status is 202
    And within 5 seconds the sync run it answered with is a FULL run of "link-engine" that ended SUCCESS

  Scenario: Re-run is idempotent
    Given resolution has already run over a tagged Lambda, a referenced bucket and a named queue
    When I POST /api/v1/links/resolve again
    Then the count of OWNS_RESOURCE and CANDIDATE_LINK edges is unchanged

  Scenario: Accepting a candidate makes it manual and supersedes others
    Given a pending CANDIDATE_LINK "c1" from the SQS resource to "github.com/acme/billing"
    When I POST /api/v1/links/candidates/c1/accept as an admin
    Then the response status is 200
    And an OWNS_RESOURCE edge from "github.com/acme/billing" to the resource exists with rule "manual", confidence 1.0, inferred false, acceptedBy the admin subject
    And a later resolve run does not change it

  Scenario: Rejecting a candidate keeps it rejected across runs
    Given a pending CANDIDATE_LINK "c1" from the SQS resource to "github.com/acme/billing"
    When I POST /api/v1/links/candidates/c1/reject as an admin
    Then the response status is 200
    And after a later resolve run the CANDIDATE_LINK "c1" still has status "rejected", rejected by the admin subject
    And GET /api/v1/links/candidates does not list "c1"

  Scenario: A decided candidate cannot be decided again
    Given a pending CANDIDATE_LINK "c1" from the SQS resource to "github.com/acme/billing"
    And candidate "c1" has been rejected
    When I POST /api/v1/links/candidates/c1/accept as an admin
    Then the response status is 409

  Scenario: An unknown candidate is not found
    When I POST /api/v1/links/candidates/no-such-candidate/accept as an admin
    Then the response status is 404

  Scenario: Removed evidence closes the edge
    Given an OWNS_RESOURCE edge with rule "tag" exists for the Lambda
    And the Lambda's tag_repo has been removed
    When I POST /api/v1/links/resolve
    Then within 5 seconds the OWNS_RESOURCE edge has provenance.validTo set

  Scenario: A manual link is stated, refused twice, and closed
    Given CloudResource "aws:arn:aws:s3:::acme-payments-logs" with no repo tag
    When I POST /api/v1/links/manual from "github.com/acme/billing" to the resource
    Then the response status is 201
    And an OWNS_RESOURCE edge from "github.com/acme/billing" to the resource exists with rule "manual", confidence 1.0, inferred false
    When I POST /api/v1/links/manual from "github.com/acme/billing" to the resource
    Then the response status is 409
    When I DELETE the manual link from "github.com/acme/billing" to the resource
    Then the response status is 204
    And the OWNS_RESOURCE edge has provenance.validTo set

  Scenario: A manual link to a missing repository is not found
    Given CloudResource "aws:arn:aws:s3:::acme-payments-logs" with no repo tag
    When I POST /api/v1/links/manual from "github.com/acme/nowhere" to the resource
    Then the response status is 404

  Scenario: Non-curators cannot accept
    When I POST /api/v1/links/candidates/c1/accept as a user without admin or curator role
    Then the response status is 403
