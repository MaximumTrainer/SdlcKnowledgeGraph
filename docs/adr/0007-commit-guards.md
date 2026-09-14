# ADR-0007: Guard the commit against what cannot be undone

## Status

Accepted. Implemented by [#52](../../../issues/52).

## Context

[ADR-0001](0001-lefthook-git-hooks.md) established lefthook and split the gates by cost: `pre-commit`
formats and statically analyses, `pre-push` runs the tests. Both gates judge *how* the code is
written. Neither looks at *what* is entering the history, and three of those failures are not
recoverable by a later commit.

A **credential** committed once has to be rotated, not deleted. Removing the file in a second commit
changes nothing: the secret is in the object store, in everyone's clone, and in any fork. ktlint,
detekt, ESLint and Prettier have no opinion about it.

A **large binary** — a jar, a build output, a screenshot dropped into the repository by accident —
stays in every clone forever, because removing it means rewriting history that other people have
pulled. The cheap moment to refuse it is the only moment: before the commit exists.

An **unresolved conflict marker** compiles in no language but commits perfectly cleanly. The
formatters do not run on every file type, the test suites do not cover documentation or workflows,
and a marker in a file outside both is found by whoever pulls next.

A fourth failure is procedural rather than permanent, but has the same shape. Every change on `main`
is supposed to arrive through a pull request, and the `land-pr` skill says so in prose — "do not push
to `main` directly, even though nothing mechanically prevents it". The repository is on a plan without
branch protection, so that really is prose. The CI run on `main` is a *push* trigger, so a direct push
gets no review and turns a broken build into a broken default branch, which the next person to clone
inherits.

## Decision

Add four guard jobs to the hooks. They are not linters and they run no test; they refuse a commit.

| Guard | Hook | What it refuses |
| --- | --- | --- |
| `protected-branch` | `pre-commit`, `pre-merge-commit`, `pre-push` | A commit or merge made while `main` is checked out, and any push that would write `main` |
| `hygiene` | `pre-commit`, `pre-merge-commit` | Conflict markers, files over 512 KiB, credential files (`.env`, private keys, keystores) |
| `secrets` | `pre-commit`, `pre-merge-commit` | Content matching the secretlint recommended ruleset |
| `lefthook-config` | `pre-commit` | A `lefthook.yml` that no longer parses, which would silently disable every gate |

**secretlint** is the scanner, over gitleaks and trufflehog. Both are better known, and both are a
binary or a second runtime to install and keep current on Windows and Linux; secretlint installs from
npm like commitlint, which is the constraint ADR-0001 settled on. Its recommended preset covers the
credential shapes this project could plausibly leak — GitHub tokens, cloud keys, private keys, basic
auth in URLs, npm tokens.

`hygiene` reads staged content from the index (`git show :path`) rather than the working tree, so a
partially staged file is judged exactly as it will be committed.

Both guards also run over every tracked file through `npm run guards`, which is a CI job. ADR-0001
accepted that hooks are bypassable with `LEFTHOOK=0` and relies on CI re-running the same checks; a
guard that existed only in a hook would be the one gate that bypass disables for good.

## Consequences

`pre-commit` stays fast. The guards are a Node process over the staged files, which is noise next to
the Gradle jobs already in that hook.

The escape hatches are deliberate and named, because a guard with no way out gets disabled wholesale.
`ALLOW_MAIN=1` permits a commit on `main`, `HYGIENE_MAX_BYTES` raises the size limit,
`.hygieneignore` exempts a path from the credential *name* rule, and `.secretlintignore` excludes a
path from the content scan. Each is an explicit decision that shows up in a command line or a diff,
unlike `LEFTHOOK=0`, which turns off everything at once and leaves no trace.

`.hygieneignore` was added by #64. The credential rule matches on the path, and credential-shaped
paths that hold no credential are ordinary - an `.npmrc` setting `engine-strict`, a public
certificate, a keystore used as a test fixture. Until it existed, the only way past that rule was
`LEFTHOOK=0`, which is the blunt instrument this whole section argues against. It forgives the name
only: the listed file is still size-checked, still scanned for conflict markers, and still read by
`secrets`, because an allowlist that exempted content would be the documented way to smuggle a
credential past the gate.

The size limit will eventually refuse something legitimate. That is the intended failure mode: the
limit exists to make a large file a decision rather than an accident, and raising it is one
environment variable.

secretlint used to scan the working tree rather than the index, so a staged secret whose working
copy had already been cleaned up would pass, and a secret present only in the working tree would fail
a commit that never contained it. #62 closed both. On the hook path the staged content is written to
a scratch directory at the same relative paths and scanned there, so what is judged is what will be
committed; findings are reported under their repository path, and the scratch copy is removed on
every exit, findings included, because it holds the credential being refused. The whole-repository
run keeps reading the working tree, where the index and the tree are the same thing.

The ESLint and Prettier jobs still have that limitation. They report on style rather than refuse
something unrecoverable, so the CI run over the committed tree is enough for them.

#61 added `pre-merge-commit`. Git does not run `pre-commit` for a merge commit, so until that hook
existed a merge introduced content no guard had ever seen - and a credential arriving through `git
merge` has to be rotated exactly as one arriving through `git commit` does. Only the three guards run
there; the formatters are deliberately absent, because a merge is not the moment to rewrite files
under someone's feet, and their findings are recoverable anyway.

A conflicted merge arrives by the other road, and always did: git stops without creating a commit,
and the separate `git commit` recording the resolution runs `pre-commit`. That is the opposite of
what #61 assumed, so there is a test pinning it rather than an argument.

The guards cannot replace branch protection. A bypassed `protected-branch` guard still permits a push
to `main`; it only stops the accident, which is what nearly every direct push actually is.

#63 widened what counts as an accident. The guard originally asked only which branch was checked
out, which misses `git push origin HEAD:main` from a feature branch - the command that most directly
moves the default branch without review. On `pre-push` it now reads the refs git names on stdin and
refuses when any of them writes a protected branch, deletions included, whatever the refspec looked
like. lefthook does not hand a job that stdin unless the job sets `use_stdin: true`, which is itself
covered by a test: a guard wired up wrongly refuses nothing, and refusing nothing is how a guard
fails silently. If the
repository moves to a plan with branch protection, the server-side rule should be turned on and this
guard kept, because it fails at the commit rather than after a rejected push.
