Feature: A deployment says what it is running
  An operator, or the pipeline that just deployed it, has to be able to ask a running instance which
  commit it was built from, which ontology it serves and whether it accepts writes. Without that,
  "is the deployment current and safe?" has no answer a machine can give (docs/DEPLOYMENT.md, D2).

  Scenario: The info endpoint names the build, the ontology and the posture
    When I GET "/actuator/info"
    Then the response status is 200
    And deployment.commit is a 40-character hex string
    And deployment.ontologyVersion equals the version the ontology endpoint serves
    And deployment.version is not blank
    And deployment.profile is "test"
    And deployment.readOnly is false
