# Packet BRAND-3 (class B) — the mapping engine comes into the monorepo

Row: `agent/BACKLOG.md` BRAND-3. Decisions: **D-150** (how it comes in — read it whole), **D-151** (its
names, its findings series, what happens to its process files), D-149 (layout and coordinates),
D-148 (history keeps the old name). Facts: `agent/work/BRAND-3-scout.md` (the inventory, with
file:line). The rename tool: `agent/tools/rail8_rename.py` (BRAND-1), run with `--root mapping`.

The architect does step 1 (git only). Steps 2 and 3 are two implementer runs, each handed back,
verified and reviewed on its own, each one commit. The implementer never commits and never pushes.

## Step 0 — the mapping's baseline (measured 2026-11-15, before anything changed)

In the clone, branch `rail8-import` (cf970caf, the mapping exactly as it was, under `mapping/`), Maven 3.9.11, Java 25, as its CI runs it:
- `backend`: `./mvnw -B -ntp -Pbench verify` — BUILD SUCCESS in 3 min, 16 reactor modules, **17 822 tests, 0 failures, 0 errors, 0 skipped**
  (surefire only). Per module: `agent/work/BRAND-3-base-counts.txt` (xdm 11151, structure 412, structure-import 211, ir 91, serializer 19,
  runtime 717, mapping 563, xslt-export 53, api 409, oracle 2910, service 1257, bench 29). Oracles: `DifferentialOracleTest` 323,
  `GoldenCorpusTest` 618, `ObservationKitWellFormedTest` 785, `LiftAgreementTest` 201, `IfWithoutElseAgreementTest` 203,
  `NestedSlotsAgreementTest` 402, `EveryInputDataErrorTest` 94. Log: `agent/work/BRAND-3-base.log`.
- The job's other steps, each exit 0: the six `scripts/test/*-test.sh` (function-families, function-families-refusals, ratchet-mutation,
  ratchet-moved, ratchet-bench, gate-spec-bench), `git diff --exit-code contract/`, `scripts/check-lf-checkout.sh`.
- `ui` (in `ui/`), each exit 0: `npm ci`, `npm run licences` (303 packages, 3 exceptions), `npm run gen:types` with no diff in
  `src/generated`, `typecheck`, `lint` (0 errors, 2 warnings), `test` (674), `test:browser` (254), `e2e` (17), `build`. The browser suites
  ran with the browser this machine has; `playwright install` is never run here.
- Not run: `windows` (`mvnw.cmd`), `stack` (`e2e:stack`), `trailers`.

Every later step is compared with these numbers. The rename tool's stale check reads the tree of a base commit (`WIRE_BASE`): for
`--root mapping` the base is the import merge, and the check must run for that root too — the implementer says how. Carried from the
review of BRAND-1 C: a base is written as a full 40-character SHA, and `wire` says so when the stale check did not run.

## Step 1 — the import (architect)

1. `.github/workflows/ci.yml`, the DCO job: `git rev-list --first-parent "$range"`, with a comment
   naming D-150. Its own commit, before the merge, with D-151 and this packet.
2. On `main`, so that `main` is the merge's first parent (the dry run's parents: e1d3dfd cf970caf):
   `git fetch /home/user/subapavel/flottila-mapping rail8-import` and
   `git merge --allow-unrelated-histories --no-commit FETCH_HEAD`, message written by hand with the
   trailers. After it `mapping/` holds the mapping repository unchanged and nothing builds it yet:
   the platform's build is untouched.
3. The merge commit differs from the two trees it joins by one line, and its message says so:
   `ci/name-allowlist.txt` gains `mapping/** | * | …`, because the imported tree holds the old name
   (3491 findings without the line) and an entry added a commit earlier would be an unused entry.
   Step 2 replaces that line by entries for the mapping's history files only.

Dry run of both in a scratch clone, 2026-11-15 (`agent/work/BRAND-3-dco-dryrun.txt`): the merge has
no conflict; `git rev-list <before>..HEAD` lists 1156 commits, 131 without the trailer;
`git rev-list --first-parent <before>..HEAD` lists one, the merge commit; with the allow-list line
the name guard is clean on 5821 tracked files, 4276 of them under `mapping/`.

Accepted limit of the DCO change (D-150, amended): the commits any merge brings in by a later parent
are not checked, whatever their origin - the job cannot tell an imported repository from a side
branch. The one merge `docs/WORKFLOW.md` prescribes (`origin/main` into the branch after a refused
push) keeps the session's commits on the first parent, and its second parent was checked when it
was pushed. Any other merge commit on `main` is a thing for the reviewer to ask about.

The 131 (`agent/work/BRAND-3-unsigned.txt`): 30 are dated after 2026-11-03, when the mapping's
trailer check came (#67), 18 of them GitHub's pull-request merge commits; the move commit is signed.

### Rulings after the review of step 1 (they bind steps 2 and 3)

1. Step 2: an edit to a test the implementer did not write (a moved path, a renamed literal)
   carries its reason in the commit body, not only in the hand-back (CLAUDE.md, Tests).
2. Step 3: no session edits the root `CLAUDE.md`. The four licences (MIT-0, EDL-1.0, ISC, 0BSD)
   and the pointer to `mapping/CLAUDE.md` go to an issue with a patch for the owner under
   `agent/patches/` (licences are the owner's, CLAUDE.md "Working rules"); until the owner applies
   it, the mapping's ADR-0014 is the rule inside `mapping/` and the root list is unchanged.
3. Step 3: no script test is deleted. A test of a script that is no longer used is listed in the
   hand-back with the reason and stays in the tree until the architect rules on it by name.
4. Step 3's two writers work one after the other, never at once (`docs/WORKFLOW.md` §1, point 4).

## Step 2 — the mapping builds in the root reactor under the Rail8 names (implementer, one commit)

**Names (D-151).** Extend the tool's maps for `--root mapping`; do the mechanical part with the tool.
- Identifiers: groupId `io.flottila.mapping` -> `io.rail8.mapping`; `flottila-mapping-<x>` ->
  `rail8-mapping-<x>` (16 artifact ids; the bare repository name in a URL or a sentence about the
  old repository stays); the package root `io.flottila.mapping` -> `io.rail8.mapping` with
  `git mv` of the directories and `module-info.java`; property keys `flottila.mapping.*` ->
  `rail8.mapping.*` and `flottila.corpus` -> `rail8.corpus`; the npm name; "Flottila Mapping" ->
  "Rail8 Mapping"; the JMH class pattern in `scripts/ratchet.java:518,523` and `ratchet/README.md`.
- Persisted and wire names: the `$id` URNs `urn:flottila:mapping-model:1` and
  `urn:flottila:function-catalogue:1`, the generated XSLT's namespace `urn:flottila:mapping:po`,
  the error code `flottila:TIMEOUT`, the OpenAPI title, and the test and corpus namespaces
  `urn:flottila:corpus:*` and `urn:flottila:test:*` — each `flottila` -> `rail8`, version digits
  kept, by textual substitution in inputs, models and expected outputs alike (the same string is
  replaced on both sides of every golden case, so no expectation is derived from the code).
- Pinned values (64-hex constants in `FingerprintPinTest`, `Fixtures`, `MappingModelWriterTest`;
  `pinned-71`; `ratchet/oracle/*.txt`): for each, say in the hand-back what the value is a digest
  OF. Where it is a digest of bytes you can name (a file, a canonical text), recompute it with
  `sha256sum`/Python and show the command — first reproducing today's value from today's bytes.
  Where it can only come from the engine, do NOT regenerate it: stop for that pin and list it; the
  architect decides after the differential oracle has passed on the renamed corpus.
- History is not rewritten: `mapping/docs/adr/**`, `mapping/docs/findings/**`,
  `mapping/bench/RESULTS.md`, `mapping/agent/**`, `mapping/docs/archive/**`. An ADR that fixes a
  name this step changes gets one dated line under its status — "Amended 2026-11-15 (D-151): the
  name reads `rail8`" — and nothing else.

**Build.**
- The root `pom.xml` lists `mapping` beside `platform`. The mapping keeps its own parent
  (`rail8-mapping-parent`), aggregated and not inherited; no platform module depends on it.
- One `.mvn/` at the repository root: `git mv` the mapping's `.mvn/` (config and wrapper) and
  `mvnw`, `mvnw.cmd` to the root. Every root-relative path in the mapping's poms goes through ONE
  property defined in `mapping/pom.xml` (for example `mapping.root` =
  `${maven.multiModuleProjectDirectory}/mapping`): the 13 references the scout lists (corpus,
  contract, scripts, `ui/dist`) and the licence filter file, which moves to
  `mapping/licence-filters.txt`. A build started from the root, from `mapping/` and with
  `-f mapping/pom.xml` must resolve the same paths — show all three with `validate`.
- Root-finding code: `service/.../Fixtures.java:204-212` (walks up to `mvnw` + `corpus/`),
  `scripts/gate-spec.sh:29`, `ui/src/import-boundaries.test.ts:74` and anything else that assumes
  the Maven root is the mapping's root. Each is an edit to another agent's test or script for a path
  the move changed: file:line and reason in the hand-back.
- The platform's guards must not start reading the mapping: `ci/coverage_check.py` (how it finds
  modules), `BuildHygieneTest` (already skips `mapping/`), the ArchUnit classpath. Show that
  `python3 ci/coverage_check.py --check-baseline` still passes after a root build, without
  `--write`.

**Checks (the implementer; logs under `agent/work/`, one Maven at a time).** From the root:
`./mvnw -B -ntp -q -f mapping/pom.xml -Pbench verify` -> `agent/work/BRAND-3-s2-mapping.log` —
per-module counts equal to `BRAND-3-base-counts.txt`, both oracles pass with the baseline's totals;
`mvn -B -ntp -q validate` from the root (both areas resolve); the tool's dry run on
`--root mapping`: 0 changes, remaining 0 outside history and the kept names. The platform's whole
reactor is the verifier's run.

## Step 3 — one CI, one set of rules, one notice (implementer for the files, architect for the root documents; one commit)

- Workflows: `mapping/.github/workflows/{ci,bench,nightly}.yml` become
  `.github/workflows/mapping-ci.yml`, `mapping-bench.yml`, `mapping-nightly.yml`, job by job
  (`backend`, `windows`, `ui`, `stack`; working directories, cache keys, artifact paths and `-f
  mapping/pom.xml` rewritten; `paths:` filters so they run for `mapping/**`, the root pom, `.mvn/**`
  and their own file). The `trailers` job is dropped — the root's DCO job applies; the script tests
  it ran that test scripts still in use move to `backend`, the others are listed in the hand-back.
  The platform's `ci.yml` builds `-f platform/pom.xml`, so neither workflow builds the other's
  area. `mapping/.github/` is removed. Workflows cannot be run here: say NOT_RUN and show
  `python3 -c "import yaml…"` parses each file.
- `mapping/CLAUDE.md`: the old file is archived by `git mv` to
  `mapping/docs/archive/CLAUDE-until-2026-11-15.md`; the new one holds only the technical rules
  (the scout marks them: red first with the oracle and the ratchet, the glossary, the ADRs, the
  licence list, the code rules), says in its first lines that the process is the root's
  (`CLAUDE.md`, `AGENTS.md`, `docs/WORKFLOW.md`), and is at most 40 lines.
- Archived by `git mv` under `mapping/docs/archive/`: `AGENTS.md`, `agent/HANDOFF.md`, `.claude/`
  (as `dot-claude/` — agents, hooks, settings, skills, agent memory). Removed: `.mcp.json` (the
  root's is the same server), `LICENSE` if byte-identical to the root's (and any pom that packaged
  it pointed at the root's), `deploy/claude-cloud/setup.sh` after what it installs that the root's
  `agent/cloud/setup.sh` lacks is listed in the hand-back (the architect edits the root script).
- `NOTICE`: the mapping's third-party lines join the root `NOTICE`; `mapping/NOTICE` is removed.
- `mapping/README.md` says once, near the top: the engine was imported from
  `github.com/subapavel/flottila-mapping` with its history on 2026-11-15; `#N` in a commit, an ADR
  or a finding of the mapping means that repository's issue or pull request; `F-n` up to F-53 in a
  mapping document means `mapping/docs/findings/F-n.md`; the root's series continues at F-54 (D-151).
- The name guard (BRAND-4): `ci/name-allowlist.txt` gains the mapping's history entries, each with
  its reason; `python3 ci/name_guard.py` is green.
- The architect writes: the issue and the owner's patch for the root `CLAUDE.md` (ruling 2 after
  step 1's review: the licence list and the pointer to `mapping/CLAUDE.md`); ARCHITECTURE §12 (the `mapping/` row is no longer "planned") with
  its changelog row; `MAP-<n>` rows in the backlog from `agent/work/BRAND-3-scout.md` block 6;
  `agent/NOTES.md`; the root `README.md` layout.

## Acceptance (the verifier)

After step 2 and again after step 3: the mapping's suite with per-module counts equal to
`BRAND-3-base-counts.txt` and the oracles at the baseline's totals, the job's script steps and the `ui` commands as in step 0; the platform's whole reactor
(`mvn -B -ntp -f platform/pom.xml clean verify -Pit -Dmaven.test.failure.ignore=true`) with counts
equal to `agent/work/BASE-1-counts.txt` and no failing test outside the known red set; a root
`mvn -B -ntp -q validate`; `coverage_check.py --check-baseline`; the guards' own tests;
`git diff -- deploy/postgres` empty; after step 3 the name guard green.

## Hand-back (at most 40 lines per step)

As in `agent/packets/BRAND-1.md`: what the tool did (counts), every hand edit with file:line and
reason (`agent/work/BRAND-3-s<n>-hand.diff`; the whole diff in `agent/work/BRAND-3-s<n>.diff`), the
tool's final report, the pins (what each is a digest of; recomputed or stopped), the checks with
evidence word, exit code and log path, and your questions — do not decide what the packet leaves
open.
