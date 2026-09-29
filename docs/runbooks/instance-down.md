# Instance down

## What fired

`InstanceDown` (page). The API has not answered Prometheus's scrape for 5 minutes (`up == 0`). While
it is down nothing else can be measured, so the burn-rate alerts say nothing about it; this one does.

## What it means for users

The web interface loads but every read and write fails, or the site does not load at all.

## Check

- Whether the machine is running: `flyctl status --app sdlc-graph-backend`.
- Why it stopped: `flyctl logs --app sdlc-graph-backend`. A container that could not start logs why;
  a missing `NEO4J_URI` or `NEO4J_PASSWORD` stops it on purpose.
- Whether it ran out of memory: an `OOMKilled` or out-of-memory line in the logs.

## Fix

- **Crashed or out of memory**: `flyctl machines restart <id> --app sdlc-graph-backend`, then look at
  memory as in [latency.md](latency.md).
- **Refused to start**: set the missing secret (`fly/bootstrap.sh` lists them) and redeploy by
  re-running the deploy workflow on the latest green `main`.
- **Only the scrape fails**: check that Prometheus still reaches the API's private address.

## Afterwards

If a deploy caused it, the deploy workflow's verification should have caught it; open an issue
saying what it missed.
