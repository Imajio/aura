# Working on the code

The rules are short and mandatory. They exist so the repository's history can
be read a year from now, not excavated.

## Branches

The trunk is `main`. Nothing is committed to it directly: all work happens on
a short-lived branch and comes back by merge.

| Prefix | For |
|---|---|
| `feat/` | new functionality |
| `fix/` | a defect fix |
| `docs/` | documents only |
| `chore/` | build, dependencies, tooling |
| `refactor/` | a change of form without a change of behaviour |

A branch name is the prefix plus a short subject, hyphen-separated:
`feat/m1-skeleton`, `fix/hook-timeout-deny`.

A branch lives days, not weeks. A long-lived branch is not thoroughness - it
is a merge conflict deferred.

## Commits

Format - [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <what was done, imperative mood, under 72 characters>

<why this was done; what would break without it; what it costs>
```

Types: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `chore`.
Scope is the module's name without a prefix: `core`, `agents`, `policy`, `ipc`,
`hook`, `app`, `sidecar`.

**Scope is dropped when a change belongs to no single module**: a fix to the
whole reactor's build, to the repository's structure, to tooling, to top-level
documents. Inventing a scope for such a commit is worse than leaving it out:
`build(build):` says nothing, and `build(core):` outright lies about the
change's boundaries.

```
feat(agents): normalize Codex exec --json into AgentEvent
fix(policy): escalate writes outside the project to confirmation
```

Three rules that matter more than the format:

1. **One commit, one logical change.** If the message body wants the word
   "and," that is two commits.
2. **Every commit builds and passes its tests.** `git bisect` is useless on a
   history where half the commits don't compile.
3. **The body answers "why," not "what."** What was done is visible in the diff.

Messages are written in English - like everything else in the repository.

## Language

**The application is entirely in English:** names, comments, javadoc, log and
exception text, interface labels, the lines spoken aloud, commit messages,
`README`, `CONTRIBUTING`, `CHANGELOG`. The code is meant to be published.

**Russian is a product option, not the language of the sources.** The user
switches a profile and gets Russian recognition, Russian speech and a Russian
interface; in the code that is localization chosen at runtime, not hard-coded
Russian literals.

The one exception is **test data standing in for the user's Russian speech**:
recognised phrases, project aliases, routing-test utterances. That is the
input and the expected result of a check. Translating it would stop the
Russian option from being tested at all.

Project documents live outside the repository, in `..\docs\`, and are also
kept in English.

## Workflow

```bash
git switch main && git pull --ff-only
git switch -c feat/short-topic

# ... changes, always with tests ...
mvn -q test

git add -A && git commit
```

Merging into the trunk is `--no-ff` only, so the branch stays visible in
history:

```bash
git switch main
git merge --no-ff feat/short-topic
```

Trunk history is not rewritten. `git push --force` to `main` is forbidden;
`git rebase` is allowed only inside your own not-yet-merged branch.

## Tests

A test is written before the implementation. A test that asserts nothing is
worse than no test: it creates the appearance of coverage.

Tests do not touch the network and do not launch real agent CLIs. Process
mechanics are checked against fake agents from the test sources; event parsing
is checked against fixtures in `testdata/fixtures/`.

**Fixtures are captured from live processes and are not to be edited.** If an
adapter disagrees with one, the adapter is what gets fixed.

## What must not be in the repository

- Model weights and compiled-blob caches - gigabytes, live in `models/` and
  `.ov_cache/`, both ignored.
- Voice recordings and the speaker reference - `voice/`, `recordings/`.
  Personal data; it does not leave the machine.
- Secrets, tokens, keys. Never, not even in test data.

## Documents

Decisions are recorded, not retold in chat. A disputed architectural choice is
written up in `../docs/adr/` (the project's documents live alongside the
repository, not inside it), following the pattern of the existing ones:
context, decision, consequences, alternatives considered, evidence.

A behaviour change visible to the user is reflected in `CHANGELOG.md`.
