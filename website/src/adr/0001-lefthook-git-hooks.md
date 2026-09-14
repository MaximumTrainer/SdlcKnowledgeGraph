<!-- GENERATED FROM docs/adr/0001-lefthook-git-hooks.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0001: Use lefthook for git-hook gates

## Status

Accepted.

## Context

Every issue in this project is to be implemented outside-in, with a failing acceptance test
committed before the code. That discipline needs enforcement, otherwise it decays into a convention
that is followed when convenient.

The repository is private on a plan without branch protection, so a server-side rule cannot require
a passing check before merge. Local git hooks plus a CI status check are the gates available.

> **Note, added later.** That premise no longer holds. The repository went public, branch protection
> became available, and `main` is now protected on the server - see
> [ADR-0009](/adr/0009-branch-protection). The hooks are kept: a server-side rule rejects a push after
> the work is done, while a hook refuses the commit before it is. The paragraph above is left as it
> was written, because it was true then and it is why the hooks exist at all.

The repository is polyglot: a Gradle and Kotlin backend, an npm and Vue frontend, and a Playwright
end-to-end project. Development happens on Windows, and CI runs on Linux. A hook runner has to work
in both without a second implementation.

Three candidates were considered.

**Husky** is the npm default. Its hooks are shell scripts, so a Windows developer needs Git Bash on
the path for anything non-trivial. It has no built-in support for running jobs in parallel or for
filtering by staged file, so it is normally paired with lint-staged, adding a second tool and a
second config file. Driving Gradle from it means hand-written glue.

**pre-commit**, the Python tool, is mature and has a large hook ecosystem, but it requires a Python
installation and manages its own virtual environments. For a team whose toolchain is JVM and Node,
that is a third runtime to install and keep working, and the Kotlin hooks in its ecosystem are
community-maintained.

**lefthook** is a single Go binary, installable from npm so it arrives with `npm install` like any
other dev dependency. One committed YAML file configures every hook. It supports parallel jobs,
piped job groups, per-job file globs, `{staged_files}` substitution, and `stage_fixed` to re-stage
files that a formatter rewrote. It runs natively on Windows without a shell.

## Decision

Use lefthook, installed via the root `package.json` `prepare` script, configured in `lefthook.yml`.

Use commitlint with `@commitlint/config-conventional` for the `commit-msg` hook, extended with a
rule that requires an issue reference in every commit message.

Invoke Gradle through `scripts/gradle.mjs`, which selects `gradlew.bat` or `gradlew` based on the
platform, so the same configuration works everywhere.

Split the gates by cost:

- `pre-commit` runs formatters, linters and fast unit tests on staged files only.
- `pre-push` runs the full backend `check` and the frontend verify chain.

## Consequences

Hooks arrive automatically for anyone who runs `npm install`, and there is one config file to read
rather than several.

The mandatory issue reference means every commit is traceable to a requirement, and the pull request
template can ask for the SHA of the red acceptance test and the green implementation as evidence.

Hooks are bypassable with `LEFTHOOK=0`. That is deliberate, for the case where the toolchain itself
is broken, and it is why CI re-runs the same checks. A bypassed hook is caught by the CI job.

`pre-push` is slow, because it starts Docker containers for the integration and acceptance suites.
That is the intended trade: the fast gate on every commit, the expensive one only when sharing work.

Adding lefthook adds a root `package.json` to a repository that previously had none, which is also
where commitlint and its configuration live.
