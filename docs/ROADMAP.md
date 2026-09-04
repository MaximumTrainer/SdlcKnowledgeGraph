# Roadmap

This project builds an SDLC knowledge graph over code repositories: a queryable model of how a
commit becomes a running service, and what that service depends on.

Two ideas shape the plan. The Harness article on knowledge graphs for AI software delivery argues
for a *minimum viable graph* of fewer than ten entity types aimed at one or two high-impact
questions, kept fresh in near real time, with access control that applies to AI agents exactly as it
applies to people. Mike Lasnoski's article on building an enterprise knowledge graph for the SDLC
argues for a *formal ontology*: declared entity and relationship types, inverse relationships, and
provenance recorded on every node and edge, fed by batch and webhook ingestion from the systems that
already hold the truth.

The two high-impact questions we target first:

1. What depends on this change, and what infrastructure would it touch?
2. Why did this deployment fail, and who owns the thing that broke?

## Milestones

### M0 Foundation

Nothing ships before the gates exist. Outside-in TDD is only a discipline if a failing acceptance
test can actually be written and a hook refuses the commit when it is not.

| Issue | Title |
| --- | --- |
| [#10](../../issues/10) | Backend test harness: jvm-test-suite, Testcontainers Neo4j, Cucumber runner |
| [#11](../../issues/11) | Frontend test harness: Vitest, Vue Test Utils, MSW, Playwright |
| [#12](../../issues/12) | Lint and format toolchain: ktlint, detekt, ESLint, Prettier, actionlint |
| [#13](../../issues/13) | Git-hook gates: lefthook and commitlint with mandatory issue references |
| [#14](../../issues/14) | GitHub Actions CI mirroring the hooks |
| [#15](../../issues/15) | Local stack: compose.yaml and Dockerfiles |
| [#16](../../issues/16) | Issue and PR templates, CODEOWNERS, docs skeleton, ADRs |
| [#17](../../issues/17) | Make Pact contract tests real |

### M1 Minimum viable graph

Nine entity types, no connectors yet. Data is entered by hand or by our own deploy pipeline, which
is enough to answer both target questions end to end.

| Issue | Title |
| --- | --- |
| [#18](../../issues/18) | Ontology registry with provenance, identity keys, inverse edges |
| [#19](../../issues/19) | Registry-driven GraphStore; write BUILT_FROM; 404 on missing link targets |
| [#20](../../issues/20) | Generate GraphQL SDL and TypeScript types from the registry |
| [#4](../../issues/4) | Ontology-driven CRUD for all core node types |
| [#8](../../issues/8) | Link Repository nodes to a real git remote |
| [#5](../../issues/5) | Registry-validated typed relationships via `/api/v1/edges` |
| [#21](../../issues/21) | Impact analysis and blast-radius queries |
| [#9](../../issues/9) | Interactive graph visualiser |
| [#6](../../issues/6) | Package the site as containers |
| [#7](../../issues/7) | Release and deploy pipelines, with self-ingestion of deployments |

### M2 Integrations

Connectors replace hand-entered data, and code gets linked to infrastructure.

| Issue | Title |
| --- | --- |
| [#22](../../issues/22) | Connector SPI, AdapterRegistry, SyncScheduler, SyncRun nodes |
| [#3](../../issues/3) | Secure the API: OIDC resource server, principal kinds, API keys |
| [#2](../../issues/2) | User login via oauth2Login, GitHub first |
| [#23](../../issues/23) | GitHub connector: repos, CODEOWNERS, manifests, IaC index, webhooks |
| [#24](../../issues/24) | ItsmConnector abstraction and ServiceNow CMDB connector |
| [#25](../../issues/25) | AWS connector, the reference cloud implementation |
| [#26](../../issues/26) | Azure connector |
| [#27](../../issues/27) | GCP connector |
| [#28](../../issues/28) | Cloud-to-repo link resolution engine |
| [#29](../../issues/29) | Sync observability |

### M3 Governance and AI

| Issue | Title |
| --- | --- |
| [#30](../../issues/30) | RBAC and policy-as-code with OPA |
| [#31](../../issues/31) | AI-agent query API and MCP server |
| [#32](../../issues/32) | Answer-quality evaluation harness |
| [#33](../../issues/33) | Data lifecycle: history, tombstones, archival, migrations |
| [#34](../../issues/34) | Observability and incident connector |
| [#35](../../issues/35) | Jira Service Management connector |
| [#36](../../issues/36) | Requirements connector |

## How work is done

Every issue is implemented outside-in, and the commit history has to show it:

1. Write the acceptance test first, from the Gherkin in the issue. It fails because the feature does
   not exist. Commit it red.
2. Write the contract or API test next. Commit it red.
3. Write unit tests for the pieces the API needs.
4. Write the code that makes all of it pass. Commit green.

The pull request template asks for the commit SHA of each of those steps. A reviewer who cannot see
a red-then-green sequence should send the PR back.

Gates that enforce this mechanically:

- `commit-msg` requires a conventional commit message carrying an issue reference.
- `pre-commit` formats and lints staged files and runs fast unit tests.
- `pre-push` runs the full backend `check` and the frontend verify chain.
- CI re-runs all of it, plus browser end-to-end tests against the compose stack.

See [TESTING.md](TESTING.md) for the mechanics, [ONTOLOGY.md](ONTOLOGY.md) for the graph model, and
[ADAPTERS.md](ADAPTERS.md) for the connector contract.
