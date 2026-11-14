# Workflow

How work goes from an idea to `main`. Taken from r8-mapping on 2026-11-10 (ADR-0001), where
it has carried several specs to `main`. It keeps Rail8's rigour (architect who never codes, red
first, evidence words, findings, class-based model choice, gate reviewer) on Matt Pocock's skill
chain (wayfinder, grilling, specs, tracer-bullet tickets, parallel implementers on an integration
branch, two-axis review). Skills are in `.claude/skills/`; the upstream ones are unchanged
(`.claude/skills/UPSTREAM.md`).

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

Nothing is built until the decisions every ticket stands on are made: the value model and tape
layout, the IR and its analyses, the execution protocol, ownership for in-place updates, number
semantics, the JSON reader and writer, which backend comes first, the public API and sandbox, the
module layout and build, the oracles and the ratchet. Two of them need a measurement first (the
spikes of founding architecture §13: deep recursion, tape cost for tiny reads). They are charted
as one wayfinder map whose draft is `docs/wayfinder/phase-0-map.md`; `/wayfinder` publishes it to
GitHub and works it ticket by ticket. A grilling ticket needs the owner (HITL); research tickets
and the two spikes run AFK in parallel, and the owner reads their result.

Phase 0 ends when the map has no open ticket and its destination holds: ADRs for each decision,
the glossary sharpened, `docs/GATE.md` final, and the first spec ready for `/grill-with-docs`
(ADR-0023).

## 3. A spec

1. `/grill-with-docs` with the owner until the frontier of questions is empty.
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
   comment and closes the ticket. The verifier then runs `scripts/after-merge.sh <ticket>`
   (verify with the compile-mode oracle, then `regen --oracles`; ADR-0020); coverage, mutation and bench move only in the spec gate (§6);
7. the frontier is recomputed and freed writer slots are refilled at once.

Up to three tickets are in flight at any time (`AGENTS.md`, Concurrency).

## 5. Finishing a spec

1. Verifier: full build and tests on the integration branch (`VERIFIED_PASS` required).
2. `/code-review` against `main`: Standards (`docs/CODING_STANDARDS.md` + the smell baseline) and
   Spec (the spec issue), in parallel, never merged or reranked.
3. One implementer fixes every finding in one pass; the verifier re-runs the full gate.
4. `/pr` writes the PR body (summary, evidence, merge danger); the PR closes the spec.
5. CI green on the PR → merge commit to `main` (history of tickets kept). Never squash, never force-push.
   `scripts/merge-spec.sh <pr>` writes and checks the merge message (F-28) and prints the merge
   command; `--execute` runs it. Never merge from the web UI: its default message fails `trailers`.
6. `agent/HANDOFF.md` is rewritten in the same PR.

## 6. Checks, ratchet, CI

The ticket gate, the spec gate, CI and the scripts are in ADR-0014; the oracles, the ratchet and
the floors are in ADR-0020. Read them there. Logs go to `.logs/<ticket>-<check>.log`, never to `target/`
(`./mvnw clean` deletes it). Until the gate scripts exist, the verifier runs ADR-0014's steps by hand,
in its order.

**Ratchet** (`ratchet/`, format in ADR-0020): oracle pass lists, coverage and mutation per module,
benchmark baselines. Ticket gates check against the integration branch's copy. The verifier
regenerates the oracle pass lists after every merge and everything in the spec gate, and commits
them. A drop is a failure; a rise is recorded at regeneration. Writers never touch it.

**Floors** (`quality/floors.tsv`, ADR-0020): JaCoCo lines ≥ 90 % and branches ≥ 80 % on
`query-core` and `jq-core`, lines ≥ 80 % on `jq-cli`; PIT ≥ 80 % on the class-C modules, killed
over all generated mutants. PIT runs only in the spec gate, never in the ticket gate (F-27).

## 7. Decisions, findings, owner questions

| Kind | Example | Where |
| --- | --- | --- |
| reversible, local | an internal shape, a threshold, a library minor version | a line in the ticket's evidence comment |
| hard to reverse, surprising, real trade-off | tape layout, IR shape, the emit protocol, a public type | ADR in `docs/adr/` (`/domain-modeling` rules), draft by the designer |
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

## 10. Turn economy (lessons from R8 and r8-mapping)

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
- Conventional Commits, scope = module without prefix: `feat(jq-core): …`, `test(oracle): …`,
  `fix(query-core): …`, `chore(ci): …`, `docs: …`.
- Body: what and why, then the evidence line. Trailers in order: `Ticket: #<n>`,
  `Co-authored-by: Claude <noreply@anthropic.com>`, `Signed-off-by: <owner>` (DCO; identity from
  the environment's git variables, never written into the repo). Write the trailer block in the
  message (`git commit -F <file>`); `-s` would add a second `Signed-off-by`.

## 12. After a run

`/retro` on the session: navigation, missing automated checks, standards the reviewers should
enforce, steering that does nothing, expensive tool calls. Mechanical lessons become checks
(build rule, enforcer rule, CI job); judgement lessons go to `docs/CODING_STANDARDS.md`;
`CLAUDE.md` stays a page of pointers. Every few specs: `/improve-codebase-architecture`.
