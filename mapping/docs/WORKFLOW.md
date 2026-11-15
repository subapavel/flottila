# Workflow

How work goes from an idea to `main`. Agreed 2026-10-30 (ADR-0001). It keeps Flottila's rigour
(architect who never codes, red first, evidence words, findings, class-based model choice, gate
reviewer) and replaces its sequential, one-writer loop with Matt Pocock's skill chain (wayfinder,
grilling, specs, tracer-bullet tickets, parallel implementers on an integration branch, two-axis
review). Skills are in `.claude/skills/`; the upstream ones are unchanged (`.claude/skills/UPSTREAM.md`).

## 1. The shape

```
fog ──/wayfinder──▶ decisions (ADRs, GLOSSARY) ──/grill-with-docs──▶ /to-spec ──▶ /to-tickets
                                                                                    │
      main ◀── PR (/pr) ◀── /code-review on the integration branch ◀── /run-spec ◀──┘
                                                                         │
                                       3 implementers in worktrees ──▶ merger ──▶ spec/<n>-<slug>
```

| Stage | Skill | Who | Output |
| --- | --- | --- | --- |
| Chart a big unknown | `/wayfinder` | owner + architect | `wayfinder:map` issue with decision tickets |
| Resolve a decision | `/wayfinder` (work mode), `/grilling`, `/research`, `/prototype` | owner + architect, research AFK | resolution comment, ADR, glossary terms |
| Shape a deliverable | `/grill-with-docs` | owner + architect | shared understanding, glossary, ADRs |
| Write it down | `/to-spec` | architect | `spec` issue with seams and testing decisions |
| Slice it | `/to-tickets` | architect, owner approves | child tickets with blocking edges, `class:*`, `oracle:*` |
| Build it | `/run-spec` | architect + agents, autonomous | integration branch with every ticket merged |
| Review it | `/code-review` (inside `/run-spec`) | reviewer-standards ∥ reviewer-spec | findings fixed by one implementer |
| Ship it | `/pr` (inside `/run-spec`) | architect | PR to `main`, CI green, merged |
| Milestone | `/gate` | gate-reviewer | `gate: MET` or the missing conditions |
| Improve the factory | `/retro`, `/improve-codebase-architecture` | owner + architect | changes to skills, standards, checks |

## 2. Phase 0 — charting before code

Nothing is built until the decisions every ticket stands on are made: the XDM contract, the IR,
the queue representation, the mapping-model format, the module layout and build, the UI
architecture, the oracles and the ratchet. They are charted as one wayfinder map whose draft is
`docs/wayfinder/phase-0-map.md`; `/wayfinder` publishes it to GitHub and works it ticket by ticket.
A grilling or prototype ticket needs the owner (HITL); research tickets run AFK in parallel.
Every grilling round goes draft (designer first round, architect later rounds) → `grilling-adviser`
→ owner; the owner is asked with the adviser's recommendation (`AGENTS.md`, owner's rule
2026-11-10).

Phase 0 ends when the map has no open ticket and its destination holds: ADRs for each decision,
the glossary sharpened, and the first spec (milestone 1, `docs/GATE.md`) ready for `/to-tickets`.

## 3. A spec

1. `/grill-with-docs` with the owner until the frontier of questions is empty. Before each round
   the `grilling-adviser` (Fable 5.2) weighs the draft questions; its recommendations go to the
   owner.
2. `/to-spec` publishes the spec issue. Its **Testing Decisions** name the seams; these are the
   pre-agreed seams `/tdd` requires, so implementers never stop to ask for them.
3. `/to-tickets` drafts tracer-bullet tickets. Each gets blocking edges, exactly one `class:*`
   label and, where an oracle decides acceptance, an `oracle:*` label with its case ids. The owner
   approves the breakdown; then the tickets are published as sub-issues of the spec.
4. Wide refactors follow expand–contract (`/to-tickets`), never one big ticket.

## 4. Building a spec — `/run-spec`

The architect runs `.claude/skills/run-spec/SKILL.md`. In short, per ticket on the frontier:

1. claim it (assign), cut a worktree branch `t/<ticket>-<slug>` from the integration branch;
2. class C: designer draft → architect approves → test-author writes the red → architect reads
   the test files and approves; A/B: the implementer runs `/tdd` at the spec's seams; oracle
   tickets: the named cases are the red;
3. implementer makes it green in its worktree, runs the ticket's scoped checks, merges the
   integration tip into its branch, hands back;
4. verifier runs the **ticket gate** in that worktree (§6);
5. class C only: `/code-review` on the ticket diff (both axes, xhigh) — FAIL goes back to the
   same implementer; a second FAIL means the ticket was wrong: the designer rewrites it;
6. merger merges the ticket branch into the integration branch; the architect posts the evidence
   comment and closes the ticket. `ratchet/` does not move here: it is regenerated once, in the
   spec gate (§6, ADR-0019);
7. the frontier is recomputed and freed writer slots are refilled at once.

Up to three tickets are in flight at any time (`AGENTS.md`, Concurrency).

## 5. Finishing a spec

1. Verifier: full build and tests on the integration branch (`VERIFIED_PASS` required).
2. `/code-review` against `main`: Standards (`docs/CODING_STANDARDS.md` + the smell baseline) and
   Spec (the spec issue), in parallel, never merged or reranked.
3. One implementer fixes every finding in one pass; the verifier re-runs the full gate.
4. `/pr` writes the PR body (summary, evidence, merge danger); the PR closes the spec.
5. CI green on the PR → merge commit to `main` (history of tickets kept). Never squash, never force-push.
6. `agent/HANDOFF.md` is rewritten in the same PR.

## 6. Checks, ratchet, CI

Decided by ADR-0017 (build, CI, gates) and ADR-0016 (modules). Commands run from the repo root of
the worktree. Logs go to `.logs/<ticket>-<check>.log` and `ui/.logs/<ticket>-<check>.log`, never
to `target/` (`./mvnw clean` deletes it). The milestone-1 skeleton adds
`scripts/gate-ticket.sh <ticket> <modules>` and `scripts/gate-spec.sh <spec>`. The verifier runs
those scripts; until they exist it runs the steps below by hand, in this order.

**Ticket gate** (verifier, per ticket, in its worktree; `<modules>` = the touched module
directories, comma-separated, e.g. `engine/xdm,engine/ir`):

| # | Command | Log | When |
| --- | --- | --- | --- |
| 1 | `./mvnw -B -pl <modules>,engine/oracle -am verify` | `.logs/<t>-build.log` | always: tests and JaCoCo floors of touched and upstream modules, the whole golden corpus and the differential oracle (ADR-0018); `service` adds ArchUnit and `@QuarkusTest` |
| 1b | `./mvnw -B -pl <class-C modules among <modules>> -am test-compile org.pitest:pitest-maven:mutationCoverage`, then `scripts/pit-summary.sh ratchet/mutation.tsv <modules>` | `.logs/<t>-pit.log` | a class-C engine module is touched (#67): a score below its `ratchet/` floor is `EXECUTED_FAIL`; none touched prints `NOT_RUN`. The last lines of the script are `module killed/generated score% floor N% ok|DROP`, one per module |
| 2 | `./mvnw -B -q -DskipTests verify` | `.logs/<t>-compile-all.log` | always: the whole reactor compiles; Spotless, enforcer, licences |
| 2b | `scripts/check-lf-checkout.sh` | `.logs/<t>-lf-checkout.log` | always: every tracked file checks out byte-identical on Windows (`.gitattributes`, #49) |
| 2c | `scripts/check-trailers.sh <integration-ref>..HEAD` | `.logs/<t>-trailers.log` | always: every commit the ticket adds carries the §11 trailers, merges of the integration tip included (spec-3 retro) |
| 3 | `git diff --exit-code contract/` | — | the service or `contract/` changed |
| 4 | in `ui/`: `npm run licences`, `typecheck`, `lint`, `test`, `test:browser`, `e2e`; `npm run gen:types && git diff --exit-code src/generated` | `ui/.logs/<t>-<check>.log` | `ui/` or `contract/` changed |
| 5 | `java scripts/ratchet.java check` | `.logs/<t>-ratchet.log` | always: no listed case failing or missing, no coverage below `ratchet/` of the integration branch; unbuilt modules print `NOT_RUN` (ADR-0019) |

Budget on the VM (4 vCPU), three gates in parallel: an engine ticket about 110 s for step 1 (it
builds the whole engine because of `engine/oracle`), a service ticket about 80 s alone; step 2 about
24 s alone (measured on the spec-1 code, `bench/RESULTS.md`, F-13; the earlier 30 s, 60 s and 15 s
came from #22 on a scratch reactor, F-5). Note of 2026-11-10 (owner, #181): cutting this time is a
process spec after milestone 1, not part of speed work. The verifier runs at
most one gate that builds `service` at a time.

**Spec gate** (verifier, integration branch, one at a time):

1. `./mvnw -B clean verify -Pbench` (all tests, oracles, JaCoCo floors, bench compiles) and
   `git diff --exit-code contract/`;
   1b. PIT on the class-C modules the spec touched:
   `./mvnw -B -pl <class-C modules> -am test-compile org.pitest:pitest-maven:mutationCoverage`
   (`pit.skip` skips the modules without a mutation floor, F-5) (floor 80 %,
   ADR-0019) → `.logs/<spec>-pit.log`; `gate-spec.sh` ends with one line per module
   (`module killed/generated score% floor N% ok|DROP`) and the corpus and differential case counts (#67); above 10 minutes it narrows to the modules class-C tickets
   touched;
2. in `ui/`: `npm ci`, every step of ticket-gate row 4, `npm run build`;
3. the stack: `./mvnw -B -Pui -pl service -am clean package -DskipTests`, then `npm run e2e:stack`;
4. `java scripts/ratchet.java regen --all`: every report must be present, a drop fails, the
   verifier alone commits `ratchet/` to the integration branch;
5. the JMH scenarios (ADR-0021):
   `java -jar bench/jmh/target/benchmarks.jar -foe true -f 3 -wi 3 -i 5 -w 1s -r 1s -prof gc -rf json -rff .logs/<spec>-jmh.json`
   (about 5–6 minutes; large throughput in forks with a 1 GB heap, and `<mapping>/large/heap`
   in its own fork with `-Xmx128m`; one invocation suffices, `-f` keeps the fork's `jvmArgsAppend` (#185); ADR-0021 note of
   2026-11-10), plus the `ui/tree-20128/scroll` Playwright scenario.
   - The verifier names the machine: `MACHINE_LABEL=claude-cloud-4vcpu scripts/gate-spec.sh <spec>`.
     Only with that label, and `nproc` = 4, does step 5 judge
     (`java scripts/ratchet.java bench .logs/<spec>-jmh.json`) and step 4 write `bench.tsv`.
     Without it, step 5 measures and prints, the judgement is `NOT_RUN`, and nothing is written to
     `ratchet/` or `bench/RESULTS.md`.
   - Results go to `bench/RESULTS.md` with machine label `claude-cloud-4vcpu`, the date and the
     commit, written by the architect from the verifier's output. The score is the median of the
     per-fork means.
   - A scenario worse than `ratchet/bench.tsv` by more than its tolerance (20 % for time and
     throughput, 10 % for B/op), or a missed target, is measured twice more: the judgement prints
     the benchmarks as one include regex, the gate re-runs only them into `.logs/<spec>-jmh-2.json`
     and `.logs/<spec>-jmh-3.json` and judges the median of the three (ADR-0021, Notes of
     2026-11-11). The gate fails if the median is still worse, if a GATE check 7 target is missed,
     or if a `bench.tsv` row has no result. A regression the median still shows goes to the owner
     as a finding, because it can come from the host (F-36).
   - `regen --all` reads the final results too, so the verifier runs this step before
     step 4. A baseline moves only on a gain beyond the tolerance (ADR-0019).

Run order: 1, 1b, 2, 5, 4, 3. Regen reads the reports of 1 and 1b, and step 3's `clean`
(mandatory with `-Pui`, ADR-0017) deletes them. The spec-1 review found this (#21).

**CI** (GitHub Actions, `.github/workflows/ci.yml`, every PR and push to `main`; required on
`main`):

- `trailers` (PRs and `t/**` pushes, #67): `scripts/check-trailers.sh` over the PR or push range, plus the
  script tests that need no JDK;
- `backend`: `./mvnw -B -Pbench verify`, then `git diff --exit-code contract/`, plus the script
  tests that need JDK 25 (`function-families*-test.sh`, #69);
- `ui`: `npm ci`, `npx playwright install --with-deps chromium`, generated types with an empty
  diff, `typecheck`, `lint`, `test`, `test:browser`, `e2e`, `build` → artefact;
- `stack` (after both): `-Pui` jar, `npm run e2e:stack`.
- `nightly.yml`: PIT on `main` over the class-C modules `xdm`, `ir`, `runtime`, `serializer`,
  `mapping`, `xslt-export`, `api`, `udf-api`, `udf`, failing below 80 % (ADR-0019); it commits nothing. `bench.yml`: manual JMH,
  informative only; benchmark regression is a spec-gate check on the VM, never on CI.
- Runners use Temurin 25 and Node 22 and fetch Maven artefacts through the Google mirror of
  Central with the Maven cache (ADR-0017).

**Ratchet** (`ratchet/`, ADR-0019, format in `ratchet/README.md`): oracle pass lists, coverage
and mutation per module, benchmark baselines. Ticket gates check against the integration branch's
copy, which equals `main`'s during a spec; the spec gate regenerates it and the verifier commits
it. A drop is a failure; a rise is recorded at regeneration. Writers never touch it.

**Floors** (fixed in the build, ADR-0019): JaCoCo lines ≥ 90 % and branches ≥ 80 % on `xdm`, `ir`,
`runtime`, `serializer`, `mapping`, `xslt-export`, `api`, `udf-api`, `udf`; lines ≥ 80 % on `structure`,
`structure-import`, `service`; PIT ≥ 80 % on the nine class-C modules; Vitest per-glob lines
≥ 90 % on `ui/src/model/**` and `ui/src/studio/store/**` (`npm run test` = `vitest run --coverage`).

## 7. Decisions, findings, owner questions

| Kind | Example | Where |
| --- | --- | --- |
| reversible, local | an internal shape, a threshold, a library minor version | a line in the ticket's evidence comment |
| hard to reverse, surprising, real trade-off | IR shape, model format, queue representation | ADR in `docs/adr/` (`/domain-modeling` rules), draft by the designer |
| contradiction with an ADR or the architecture | the IR cannot express X as decided | finding: `docs/findings/F-<n>.md`, smallest change keeping the intent, ADR amended or superseded in the same merge, PR body says "deviation" |
| owner only | money, credentials, licences, a gate missed twice, history rewrite | issue labelled `ready-for-human`; the ticket waits, the run continues with the next frontier ticket |

## 8. Autonomous runs

`/run-spec` runs without asking the owner. A blocked ticket (owner answer, credential, network,
a tool failure that survives one retry and one alternative route) gets a comment and the
`ready-for-human` label, and the run takes the next frontier ticket. The run ends on
`SPEC_DONE` (PR merged), `IDLE_NO_FRONTIER` (everything left is blocked) or a quota stop; the
reason is the first line of `agent/HANDOFF.md`.

The cloud VM can be reclaimed at any time. Pushed branches, issues and `agent/HANDOFF.md` are the
recovery point: push the integration branch after every merge and every ticket branch at every
hand-back.

## 9. Files

| File | Purpose | Written by |
| --- | --- | --- |
| `agent/HANDOFF.md` | ≤ 20 lines of ≤ 160 chars: state, current spec and integration branch, tickets in flight with their worktree branches, next exact action, open owner issues | architect |
| `agent/NOTES.md` | module notes (commands, quirks, shapes) by section, read on demand | architect |
| `bench/RESULTS.md` | every measured number with machine, date, configuration, commit | architect, from verifier output |
| `ratchet/` | what passes, per oracle and module | verifier only |
| `docs/adr/` | binding decisions | architect (designer drafts) |
| `docs/findings/` | contradictions and their resolution | architect |
| `.claude/agent-memory/<name>/MEMORY.md` | durable lessons per agent | that agent |

GitHub Issues replace the backlog and the progress journal: the spec, its tickets and their
evidence comments are the record. Nothing that matters lives only in the conversation.

## 10. Turn economy (lessons from Flottila, D-053/D-054 there)

- The architect is the most expensive context; deep design goes to a fresh designer, not into it.
- Wait for a single agent in the foreground; background only with two or more in flight.
- A resumed agent keeps its context: a fix, a second review round or a partial hand-back resumes
  the same agent by name (`SendMessage`), never a fresh one.
- A fix packet names file, line, evidence and the sentence to write; nothing else.
- A Stop-hook reminder mid-run is declined in one line; it never triggers a commit of an
  unreviewed tree. `.logs/.batch-in-review` silences it while a review is running.
- Self-check lists in the writers mirror the reviewers' memory, so authors catch what reviewers
  would.

## 11. Commits and branches

- `main` ← PR per spec. `spec/<issue>-<slug>` ← merger. `t/<ticket>-<slug>` ← one implementer.
  Throwaway: `research/<name>`, `prototype/<name>` (never merged).
- Conventional Commits, scope = module without prefix: `feat(engine): …`, `test(model): …`,
  `fix(ui): …`, `chore(ci): …`, `docs: …`.
- Body: what and why, then the evidence line. Trailers in order: `Ticket: #<n>`,
  `Co-authored-by: Claude <noreply@anthropic.com>`, `Signed-off-by: <owner>` (DCO; identity from
  the environment's git variables, never written into the repo). Write the trailer block in the
  message (`git commit -F <file>`); `-s` would add a second `Signed-off-by`.

## 12. After a run

`/retro` on the session: navigation, missing automated checks, standards the reviewers should
enforce, steering that does nothing, expensive tool calls. Mechanical lessons become checks
(lint rule, ArchUnit rule, CI job); judgement lessons go to `docs/CODING_STANDARDS.md`;
`CLAUDE.md` stays a page of pointers. Every few specs: `/improve-codebase-architecture`.
