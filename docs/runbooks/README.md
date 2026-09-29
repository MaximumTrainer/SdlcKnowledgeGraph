# Runbooks

One page per alert, for whoever the alert reaches (#44, FR10). Every alert in `ops/alerts` names its
page in `annotations.runbook_url`, and every page here except this README is named by an alert:
`AlertRunbookTest` fails the build if either side is missing, so a page cannot outlive its alert and
an alert cannot fire with nowhere to send the reader.

Each page has the same sections, in this order:

- **What fired**: the alert, its severity, and the condition in plain words.
- **What it means for users**: what someone using the graph is seeing right now.
- **Check**: the first things to look at, as commands where possible.
- **Fix**: the usual causes and what to do about each.
- **Afterwards**: what to record or follow up once it has cleared.

Commands name the dogfood instance on fly.io (`sdlc-graph-backend`, `sdlc-graph-neo4j`; see
[fly/README.md](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/fly/README.md)).
Elsewhere, use the equivalent for the platform the instance runs on.

To add an alert and its runbook, see [OBSERVABILITY.md](../OBSERVABILITY.md#alerts).
