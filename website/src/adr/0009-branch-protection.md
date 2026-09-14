<!-- GENERATED FROM docs/adr/0009-branch-protection.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0009: `main` is protected on the server, and the rule applies to everyone

## Status

Accepted. Implemented by [#67](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/67).

Supersedes three passages that rest on branch protection being unavailable:
[ADR-0001](/adr/0001-lefthook-git-hooks) on local hooks being the only gate available, and two in
[ADR-0007](/adr/0007-commit-guards) — that requiring a passing check before merge "really is prose",
and that the server-side rule "should be turned on" if the repository ever moves to a plan with it.

Both keep their original text. They were right when they were written; this records what changed.

## Context

ADR-0001 was decided when the repository was private on a plan without branch protection. Its
reasoning was explicit: "a server-side rule cannot require a passing check before merge. Local git
hooks plus a CI status check are the gates available." ADR-0007 repeated it, and named the trigger
for revisiting: "If the repository moves to a plan with branch protection, the server-side rule
should be turned on and this guard kept."

The repository is now public, so branch protection and rulesets are available at no cost. The
trigger condition has been met. Until this ADR, `main` was still unprotected —

```
$ gh api repos/MaximumTrainer/SdlcKnowledgeGraph/branches/main/protection
{"message":"Branch not protected","status":"404"}
```

— while the published website told readers the project could not require a passing check before a
merge. That had stopped being true.

The local guards cannot close this on their own, and never claimed to. `LEFTHOOK=0` disables every
hook, and a hook is a request rather than an enforcement: it runs on the machine of whoever chooses
to run it.

## Decision

`main` requires a pull request, requires the CI checks to pass, requires the branch to be up to date
before merging, and refuses force pushes and deletion.

**The rule applies to the owner as well** (`enforce_admins: true`). A rule the maintainer can step
over silently is a convention with extra steps: the direct push leaves nothing behind saying a rule
was skipped. Turning the protection off is also possible in a genuine emergency, and it is the
better escape hatch precisely because it is visible — it is a change to the repository's settings,
not an invisible exception to them. That is the same argument ADR-0007 makes for named escape
hatches over `LEFTHOOK=0`.

**No approving review is required** (`required_approving_review_count: 0`). This is the part that
would otherwise lock the repository solid. GitHub does not let anyone approve their own pull request,
so a single-maintainer repository that required one approval and enforced the rule on admins could
never merge anything again. #67 warned about exactly this: a repository that "locks its only
maintainer out of an emergency fix has swapped one problem for another."

What is being bought here is not review by a second person — there is no second person. It is that
every change to `main` arrives as a pull request with a green CI run against it, and that this cannot
be skipped by anyone, including by accident.

**`protected-branch` stays.** The server-side rule rejects a push after the work is done; the guard
refuses the commit before it is. Those are different moments, and the cheaper failure is the earlier
one. #63 widened it to read the refs being pushed, so it now refuses `git push origin HEAD:main`
rather than only a commit made while standing on `main`.

## Consequences

Required status checks have to name checks that actually run, or a merge waits forever for a report
that never arrives. Eight are required:

| Check | From |
| --- | --- |
| `Commit messages` | commitlint over the branch's commits |
| `Guards (hygiene, secrets, hook config)` | the whole-repository guard run |
| `Root scripts (guard unit tests) (ubuntu-latest)` | [#65](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/65) |
| `Root scripts (guard unit tests) (windows-latest)` | [#65](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/65) |
| `Backend (unit, integration, acceptance, contract)` | all four Gradle suites |
| `Frontend (lint, typecheck, unit, build)` | the frontend verify chain |
| `Website (generate, drift, build)` | the generator, its drift check and the site tests |
| `End-to-end (compose + Playwright)` | the browser tests against the compose stack |

The two `Root scripts` contexts were added in a second pass rather than with the rest. They did not
exist on `main` when the rule was first applied, and requiring a check that never reports deadlocks
every merge — including the one that would introduce it. The general rule follows from that: a job
has to run on `main` at least once before it can be required. Adding a
CI job means adding its context here, and **removing or renaming one means removing it from the
required list first** — a required check whose job no longer exists blocks every merge, and the
symptom (a pull request stuck on "Expected — waiting for status to be reported") does not obviously
point at its cause.

Requiring the branch to be up to date means a merge invalidates every other open pull request until
each is updated. With a handful of pull requests in flight that is a small cost; it is the mechanism
that stops two independently-green branches from combining into a broken `main`.

The website no longer tells readers the project cannot require a passing check before merge.
