# Packet BRAND-1 (class B) — rename Flottila to Rail8 and restructure the monorepo

Row: `agent/BACKLOG.md` BRAND-1. Decisions: D-145 (the name), D-148 (the owner's plan and the open
points), **D-149 (layout, coordinates, the three commits — read it whole, it is the specification)**.
Background, read by section when a step needs it: `agent/work/BRAND-2-designer.md` (the hard-wired
paths, with file:line), `agent/work/BRAND-2-advisor.md` (W6-W9 and the risks),
`agent/work/BRAND-1-scout.md` (the persisted and wire names, the golden values, the traps).

Three commits, **A then B then C**, each handed back separately and verified before the next
starts. The architect commits; the implementer never commits and never pushes.

## The tool

Write one script, `agent/tools/rail8_rename.py` (Python 3, standard library only), and do the
mechanical part of every commit with it — never by hand, never by an ad-hoc sed. It is kept in the
repository: BRAND-3 runs it again on `mapping/`, and a parked diff is translated with it.

- Subcommands `moves`, `identifiers`, `wire`; each idempotent (a second run changes nothing) and each
  with `--dry-run` printing what it would do. `--root <dir>` limits it to a subtree (BRAND-3).
  `translate <file>` applies the path map and the identifier map to one text file (a parked diff).
- The maps are data at the top of the file: the path map (old directory -> new), the identifier map
  (ordered, longest first), the wire map (one entry per literal, each with the file it lives in),
  the exclusion list (below). No regular expression broader than it needs to be; no blanket
  `flottila` -> `rail8` over files the script has not classified.
- It uses `git mv` for every move, so history follows. It never touches `.git/`, `target/`, or a
  file that is not tracked.
- It ends by printing every remaining occurrence of the old name (any case) outside the exclusion
  list, grouped by file. After C that list must be empty.

### Exclusions — never rewritten by the script, in any commit

History keeps the old name as written (D-148): `docs/PROGRESS.md`, `docs/DECISIONS.md`,
`docs/archive/**`, `docs/findings/**`, `docs/review/**`, `agent/packets/**`, `agent/archive/**`,
`agent/work/**`, `agent/review/**`, `agent/patches/**`, `agent/wip/**`, `issues/**`,
`bench/RESULTS.md`, the root `PROGRESS.md` (it moves to `docs/archive/` in A, unedited), and in
`agent/BACKLOG.md` every table row whose status cell starts with `done`. Also: the applied
migrations `deploy/postgres/V*.sql` stay **byte-identical** (`git diff -- deploy/postgres` is empty
after every commit); every `github.com/subapavel/flottila` URL stays (the owner renames the
repository; `flottila-mapping` in such a URL stays too); the script itself and this packet.
`agent/HANDOFF.md` and the open rows of BACKLOG are the architect's to word — the script may
translate paths and identifiers in them, nothing else.

## Commit A — directory moves and path strings; no identifier changes

Target, per D-149:

| Old | New |
| --- | --- |
| `flottila-{api,core,runtime,testkit,schemas,cli,sim,archtest}` | `platform/{api,core,runtime,testkit,schemas,cli,sim,archtest}` |
| `flottila-components/{http,mock,sftp}`, `flottila-steps/{validate,xslt}` | `platform/components/...`, `platform/steps/...` |
| root `pom.xml` (the parent) | `platform/pom.xml`, modules listed by their new directory names, plus `../bench` |
| — | a new root `pom.xml`: a thin aggregator with the single module `platform` (`mapping` joins in BRAND-3). It needs a GAV distinct from the parent's; in A give it the artifactId `flottila-all`, B renames both |
| `flottila-bench/{pom.xml,src,...}` | `bench/` beside `run.sh`, `bench.py`, `RESULTS.md`; its parent `relativePath` is `../platform/pom.xml` |
| `packages/demo` | `examples/demo` |
| `prompts/` | `agent/prompts/` |
| `deploy/claude-cloud/` | `agent/cloud/` |
| root `PROGRESS.md` | `docs/archive/PROGRESS-until-2026-11-03.md`, unedited |

Stay: `agent/ issues/ docs/ ci/ bin/ deploy/{compose,quickstart,postgres} docker-compose.yml
HANDOVER.md README.md LICENSE NOTICE AGENTS.md CLAUDE.md .github/ .claude/`.

Then every path that the move breaks, and only those: the 25 `Path.of("..` sites in 21 test files
(each must resolve to the same file as before — check each, do not rewrite `..` blindly:
`BenchRunScriptTest.java:28` stays valid as it is), the module's own `pom.xml` path properties,
`bin/flottila`, `deploy/quickstart/Dockerfile` and its `.dockerignore`,
`deploy/compose/docker-compose.yml`, `docker-compose.yml`, `.github/workflows/*.yml`,
`bench/run.sh`, `bench/*.py`, `ci/coverage_check.py` if its module discovery is by path, the hooks
and settings under `.claude/`, and the same paths in the living documents (`CLAUDE.md`, `AGENTS.md`,
`README.md`, `HANDOVER.md`, `docs/WORKFLOW.md`, `docs/ARCHITECTURE.md`, `docs/CORE-REWORK.md`,
`docs/OPERATIONS-BRIEF.md`, `docs/GATE.md`, `docs/QUICKSTART.md`,
`docs/PROTOTYPE-VS-ARCHITECTURE.md`, `agent/NOTES.md`, `.claude/agents/*.md`, `.claude/skills/**`,
`.claude/agent-memory/**`). Prefer `-pl :artifactId` over a directory in a Maven command where the
command is written down, but only after `mvn -pl :flottila-sim -am -q validate` has shown that this
Maven accepts it; otherwise use the new directory.

Rules for A:
- `ci/coverage-baseline.json`: rename the keys to the new module paths, **values untouched**.
  Never run `coverage_check.py --write` (opponent W6). `python3 ci/coverage_check.py
  --check-baseline` must pass exactly as `.github/workflows/ci.yml` runs it.
- `BuildHygieneTest` (platform/archtest): it walks from the repository root; it excludes `mapping/`
  by name with the reason in a comment (the mapping service is Quarkus and is not the pipeline;
  D-149). Its other expectations are unchanged. This is an edit to a test another agent wrote: the
  reason goes in the hand-back and the commit message.
- No Java package, class, artifactId (except the new aggregator), property key, env key or string
  literal changes in A. If a test fails for a reason other than a path, stop and report it.
- `git log --follow` must cross the move for a moved class: show it for one file of
  `platform/runtime` and for `platform/pom.xml`.

## Commit B — identifiers

- Maven: groupId `io.flottila` -> `io.rail8`; the parent `rail8-parent`, the aggregator `rail8`;
  every `flottila-<x>` artifactId -> `rail8-<x>`; project names (`Flottila :: api` -> `Rail8 ::
  api`); build properties `flottila.*` -> `rail8.*` (coverage, mutation, `flottila.it.postgres`);
  PIT `targetClasses`/`targetTests`; the manifest's Main class; jar names in scripts, Dockerfile,
  compose, workflows.
- Java: package root `io.flottila` -> `io.rail8` (`git mv` of `src/*/java/io/flottila` and of
  resource directories under `io/flottila`), imports, fully qualified names in strings and in
  `META-INF` files, the package constants in the ArchUnit `Rules` (the root changes, no rule
  changes). Class names that contain `Flottila` -> `Rail8` (the scout lists them).
- Environment keys `FLOTTILA_*` -> `RAIL8_*`; the CLI `bin/flottila` -> `bin/rail8` and every
  message prefix `flottila: ` -> `rail8: `; thread, timer and temp-file name prefixes; log and error
  texts; the product name `Flottila` -> `Rail8` in user-visible text. No alias for an old key or
  the old CLI name is kept (D-148).
- The living documents listed under A, plus `LICENSE`/`NOTICE` where they name the product, the
  schemas' descriptions, `examples/demo`, the compose files' service names, network aliases and
  image names.
- **Not in B:** every literal on the scout's wire list. B leaves each of them exactly as it is,
  even where that leaves `flottila` inside a renamed class. The script's `identifiers` map must
  exclude them by file and literal, and its final report after B must show only those.

## Commit C — persisted and wire names

One entry in the wire map per literal on the scout's list, each renamed `flottila` -> `rail8`
with its version digits kept (`flottila-journal-v1` -> `rail8-journal-v1`, `flottila-flow-hash 2\n`
-> `rail8-flow-hash 2\n`, `io.flottila.Step` -> `io.rail8.Step`, `X-Flottila-Actor` ->
`X-Rail8-Actor`, `flottila_work` -> `rail8_work`, …), including the compose volumes, the bucket,
the database, role and default paths in `deploy/`, `bench/` and the tests' fixtures.

Golden values: for every test on the scout's golden list, recompute the expected value
**independently of the code under test** — `sha256sum`/`openssl`/a few lines of Python over the
input the test documents — and put the exact command and its output in the hand-back, one per
value. Never copy the "actual" of a failing assertion into the expectation. Where an expectation
cannot be recomputed without the production code, say so and stop for that test.

No migration is added or edited (the applied ones carry the name in comments only).

## What every hand-back holds (at most 40 lines)

1. What the script did (counts: files moved, files rewritten, replacements per map entry class).
2. **Every edit made by hand**, file:line with one reason each, and the same as a diff in
   `agent/work/BRAND-1-<A|B|C>-hand.diff`. The whole diff goes to `agent/work/BRAND-1-<A|B|C>.diff`
   (`git diff -M --stat` at its top).
3. The script's final report of remaining occurrences.
4. The checks you ran, each with its evidence word, exit code and log path under `agent/work/`.
   Use `mvn -B -ntp -q` and send output to a file. Never start Maven while another Maven runs.
   Whole-module runs only (`-Dtest=` on the runtime module runs out of heap).
5. Anything that did not fit the packet, as a question — do not decide it.

## Acceptance (the verifier measures it; the baseline is `agent/work/BASE-1-red-set.txt`)

After each commit: the whole reactor (`clean verify -Pit`, in the measurement mode that goes on
past a failing test) shows no failing test that is not in the BASE-1 red set under its new name,
the same number of tests run per module as BASE-1 and none skipped; ArchUnit ran; the Python
guards' own tests pass; `coverage_check.py --check-baseline` passes; `git diff -- deploy/postgres`
is empty. After C: the script's report of remaining occurrences is empty, and the quickstart image
builds and the compose stack test passes under the new names.
