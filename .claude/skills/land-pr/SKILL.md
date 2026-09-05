---
name: land-pr
description: Land finished work on main the way this repository does it - push the branch, open a PR from the template with the red and green commit SHAs, wait for CI, merge, then confirm the CI run on main is green. Use when asked to push, open a PR, merge, or ship completed work.
---

# Land a change on main

Every change on `main` arrived through a pull request; the merge commits in `git log` are the record.
Do not push to `main` directly, even though nothing mechanically prevents it - the CI run on `main`
is a *push* trigger, so a direct push turns a broken build into a broken default branch.

## 1. Gate it first

Run the `verify-gate` skill. `pre-push` runs the backend `check` and the frontend verify chain
anyway; failing there wastes a round trip.

## 2. Push the branch

```bash
git push -u origin <branch>
```

## 3. Open the pull request

`.github/PULL_REQUEST_TEMPLATE.md` asks for the commit SHA of each outside-in step - the red
acceptance commit, the red API commit, the green implementation commit. Fill them in from
`git log --oneline`; a reviewer uses them to check the red-then-green sequence. Be accurate: if the
implementation was written before the tests, say so in the PR rather than implying a sequence the
history does not show.

```bash
gh pr create --fill --title "<what now works>" --body-file <path>
```

## 4. Wait for CI on the PR

```bash
gh pr checks --watch
```

Four jobs: commit messages, backend, frontend, and end-to-end (which needs the first two). Roughly
seven minutes. If a job fails, read the log with `gh run view <id> --log-failed` and fix it on the
branch rather than merging around it.

## 5. Merge

```bash
gh pr merge --merge
```

The history uses merge commits (`Merge pull request #NN from ...`), so keep `--merge` rather than
squashing: the red-then-green commits are the audit trail and squashing destroys them.

## 6. Confirm main is green

Merging starts a second CI run, on `main`. The work is not landed until that one passes:

```bash
gh run list --branch main --limit 1
gh run watch <id>
```

Report the conclusion of that run, not the PR's. If it fails, say so plainly and fix forward.
