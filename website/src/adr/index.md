<!-- GENERATED FROM docs/adr/ - DO NOT EDIT. Run `npm --prefix website run generate`. -->


# Architecture decisions

Why things are the way they are, and what was rejected. Superseding a decision means a new
record, not an edit to the old one.

| Number | Decision | Status |
| --- | --- | --- |
| [0001](./0001-lefthook-git-hooks) | Use lefthook for git-hook gates | Accepted |
| [0002](./0002-testcontainers-cucumber-acceptance) | Acceptance tests with Cucumber against a Testcontainers Neo4j | Accepted |
| [0003](./0003-hybrid-ontology-registry) | A hybrid ontology, with a registry as the source of truth | Accepted |
| [0004](./0004-pact-folder-no-broker) | Verify Pact contracts from a folder, without a broker | Accepted |
| [0005](./0005-auth-oidc-github-first) | OIDC-based identity, with GitHub as the first provider | Accepted, not yet implemented |
| [0006](./0006-website-generated-from-sources) | The website is generated from repository sources | Accepted |
| [0007](./0007-commit-guards) | Guard the commit against what cannot be undone | Accepted |
| [0008](./0008-dependency-updates) | Dependency updates arrive continuously, and the toolchain tracks supported majors | Accepted |
| [0009](./0009-branch-protection) | `main` is protected on the server, and the rule applies to everyone | Accepted |
