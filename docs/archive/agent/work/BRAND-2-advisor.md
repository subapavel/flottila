# BRAND-2 — the opponent's hand-back on the designer's draft (2026-11-15, read-only)

**Verdict: SOUND WITH CHANGES.** The layout and coordinates hold. The order of work, the import and
the checks around them have holes that turn `main` red or leave the tree half-renamed.

Verified on disk: 25 `Path.of("..` sites in 21 files; `coverage_check.py:81-84` skips unknown
modules; BuildHygieneTest walks 4 levels below `..` (:21,33); the `mvn` script's `.mvn` lookup
(:124-142); mapping `pom.xml:192,203,307-311`; the platform has no `.mvn/`, no `mvnw`, no
`META-INF/services`, no `module-info.java`, no `allowEmptyShould`, no committed package lock.
Unchecked: git history of either repo, Rules.java, `coverage-baseline.json`, the runner's Maven,
`-pl :artifactId`.
Wrong in the draft: 16 `<relativePath>..</relativePath>` lines, not 15. `BenchRunScriptTest.java:28`
is `Path.of("../bench/run.sh")`, which stays valid once bench sits at `bench/`.

## Weak points
- **W1, half-renamed after BRAND-3.** The mapping is `io.flottila.mapping` / `flottila-mapping-parent`
  today. BRAND-3 runs after BRAND-1 and its acceptance has no rename. Change: BRAND-3 is two commits,
  a pure import then a rename inside `mapping/`; the grep becomes a permanent CI check with an
  allow-list file.
- **W2, DCO red on the import push.** `ci.yml:32-46` checks `Signed-off-by` on every commit of
  `git rev-list before..sha`, merges included. A non-squash import puts the mapping's commits in that
  range (measured by the architect: 131 of 1154 carry no sign-off). Change: `--first-parent` in the
  DCO loop as a stated CI change; write the merge message by hand with trailers.
- **W3, mapping workflows.** `working-directory` does not reach `uses:` inputs: its `ci.yml:138,157,
  174,192`, `nightly.yml:51` and `bench.yml:52` are workspace-relative. Its trailers job (Ticket,
  Co-authored-by, one Signed-off-by) would fail every platform commit. Change: port job by job,
  rewrite each `path:`, scope or drop the trailers job.
- **W4.** The mapping has no BACKLOG and no DECISIONS: work items are GitHub Issues, decisions are
  `docs/adr/ADR-nnnn`. No FIX-/D- collision. The real loss: `#39`, `#67` in imported commits and ADRs
  will link to this repo's issues, and open tickets do not arrive with git. Change: open issues become
  MAP- rows before import; one README line maps `#N` to the old repo; archiving the old repo is an
  owner action.
- **W5, nested `mapping/CLAUDE.md`.** It prescribes worktrees, an integration branch, a merger agent,
  GitHub Issues and "no backlog file". Change: trim it to the technical rules (red first with oracle
  and ratchet, licences, glossary, ADRs) plus "process = root"; archive the rest with `git mv`.
- **W6, `--write` in commit A.** `--check-baseline` fails on missing or extra keys and on ±2.0 drift.
  If A renames keys with values untouched, CI does the key-by-key comparison itself. Change: the
  packet forbids `--write` in A and B.
- **W7, red-set comparison sees failures only.** A module dropped from `<modules>`, or tests no longer
  discovered, gives fewer tests and the same red set. Change: compare per-module run and skipped
  totals before and after each commit.
- **W8, bench.** `flottila-bench/pom.xml:10` breaks at `bench/`. Change: `<module>../bench</module>`
  in `platform/pom.xml` with relativePath `../platform`; the root aggregates `platform` and `mapping`
  only, so the platform reactor equals today's.
- **W9, migrations.** Only comments in V1, V13, V14, V16 and V18 carry the name. Write no new
  migration; the script excludes `deploy/postgres`; the verifier asserts an empty
  `git diff -- deploy/postgres`.

## Missed alternatives
- Import by committing a `git mv` of everything into `mapping/` on the mapping's history, then
  `git merge --allow-unrelated-histories`: same SHAs, `--follow` crosses an ordinary rename.
- The rename as one committed script (path map, identifier map, exclusions): it produces A and B,
  re-runs on `mapping/` in BRAND-3 and on any parked diff.
- BuildHygiene by exclusion (walk the repo root, exclude `mapping/` by name with the reason): a new
  area such as `jq/` is covered by default.

## Risks
- `io.rail8` presumes `rail8.io`; D-145 says no domain is registered. Maven Central wants namespace
  proof (from memory). Write the ISSUE now; the coordinates stay `draft`.
- Golden values copied from a failing assertion's "actual" prove nothing. Recompute them
  independently and put the command in the commit body.
- A gate command with the old JFR event name returns nothing, not an error. A developer stack's
  journal, spool and volumes are orphaned by commit C: add a HANDOVER line.
- After BRAND-3: the root `verify -Pit` grows for every platform task; the mapping's enforcer wants
  Maven `[3.9.11,4)`; `coverage_check.py` globs `mapping/` and fails on unknown modules.
- A session branch based on pre-rename `main` adds files at dead old paths, uncompiled and green.
  Add a HANDOFF freeze line; W1's guard catches it.
- `.claude/agent-memory/*/MEMORY.md` hold old paths. §12 and the Rules.java constants need the
  finding first, not only a changelog entry.

## Answers to the open questions
1. Accept the aggregator and `platform`, with bench per W8.
2. Rename all wire names now, in its own small commit. Keep: applied migrations byte-identical;
   `github.com/subapavel/flottila` URLs until the owner renames the repository; history files; the
   version digits (`-v1`, `1\n`, `2\n`).
3. Three commits: A moves, B identifiers, C persisted and wire names.
4. BuildHygiene is not weakened if exclusion-based with the reason stated.
5. See W2 to W5. Confirm the mapping's HANDOFF shows nothing in flight on an integration branch.
6. Do not wait for P2-10b. A path-only translation fails after B because context lines hold
   `package io.flottila…`; use the full script on a worktree at the pre-rename commit.
7. The mapping's own rule already allows the four licences (its ADR-0014).
8. Accept the support moves. `deploy/postgres` stays. The mapping's NOTICE is merged into the root
   NOTICE at import.
