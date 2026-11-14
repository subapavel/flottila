# Agents, ticket classes, evidence

Every agent working here (Claude Code, its subagents, any other tool) follows `CLAUDE.md`. This
file says who does what, with which model, how many at once, and what counts as evidence. The
loop itself is `docs/WORKFLOW.md`.

## Roles

| Role | Definition | Default model · effort | Writes | Owns |
| --- | --- | --- | --- | --- |
| architect | the main session | Opus 5.5 · medium | issues, `agent/`, `docs/`, merges via the merger | specs, tickets, approval of red tests and designs, dispatch, decisions, findings, the spec PR |
| designer | `.claude/agents/designer.md` | Opus 5.5 · xhigh | nothing (read-only) | class-C design drafts: public types, the value model and tape layout, IR shapes, ADR drafts, spec rewrites after a second FAIL |
| test-author | `.claude/agents/test-author.md` | by class | tests and fixtures in its worktree | red tests of class-C tickets, observed red |
| implementer | `.claude/agents/implementer.md` | by class | code (and tests for A/B) in its worktree | one ticket, green, scoped checks run, integration tip merged in |
| merger | `.claude/agents/merger.md` | Opus 5.5 · medium | the integration branch | merging one finished ticket branch at a time; mechanical conflicts only |
| verifier | `.claude/agents/verifier.md` | Sonnet 5.5 · low | `.logs/`, `ratchet/` (regeneration only) | running named checks, reporting observed results |
| scout | `.claude/agents/scout.md` | Haiku 5.5 · medium | nothing | locating code, decisions, prior evidence |
| reviewer-standards | `.claude/agents/reviewer-standards.md` | by class | nothing | the Standards axis of `/code-review` |
| reviewer-spec | `.claude/agents/reviewer-spec.md` | by class | nothing | the Spec axis of `/code-review` |
| gate-reviewer | `.claude/agents/gate-reviewer.md` | Fable 5.2 · high | nothing | milestone gates only |
| grilling-adviser | `.claude/agents/grilling-adviser.md` | Fable 5.2 · high | nothing (read-only) | evaluating the questions and recommendations of every grilling ticket before the owner sees them; its recommendation is the one put to the owner (owner, 2026-11-10) |

The architect never edits `src/` or a test, and never runs Maven itself: the verifier does. It reads hand-backs, the red tests it approves and `git diff --stat`, not whole
patches.

## Ticket classes

Every ticket carries one `class:*` label, set when `/to-tickets` writes it. The class sets the
model and effort of every call on that ticket. Raise a class with a reason in a ticket comment;
never lower it mid-ticket.

| Class | Typical tickets | Red tests by | Implementer | Per-ticket review | Designer |
| --- | --- | --- | --- | --- | --- |
| `class:A` mechanical | docs, CI, fixtures, scripts, a CLI flag, a builtin with oracle cases | implementer (`/tdd`) or the oracle | Sonnet 5.5 · medium | none — automatic gate only | — |
| `class:B` standard | a builtin family, a parser construct, an output mode, a host SPI method, a fix | implementer (`/tdd`) or the oracle | Opus 5.5 · high | none — automatic gate only | — |
| `class:C` hard path | value model and tape, JSON reader, IR and its analyses, execution protocol, ownership and in-place updates, path semantics, code generator, public API, budgets and sandbox | test-author (Opus 5.5 · xhigh) | Opus 5.5 · xhigh | both axes, Opus 5.5 · xhigh, before merge | before the tests, xhigh |
| `class:G` gate | milestone gate rows | — | — | gate-reviewer (Fable 5.2 · high) | — |

Modifier labels:

- `oracle:jqtest` — acceptance is a set of cases from jq's own test files (program, input,
  expected outputs). No test-author; the cases are the red.
- `oracle:differential` — acceptance is a set of cases (filter, input, flags) where our output
  and the reference jq binary's output must be byte-identical.
- `oracle:backend` — acceptance is that the interpreter backend and the bytecode backend give
  identical output on a named set of cases.
- `oracle:json` — acceptance is a set of JSON parsing conformance cases (accept, reject).

An oracle ticket names its cases in the ticket body. The ratchet then keeps them green forever.

Every spec, whatever its tickets' classes, ends with one full two-axis `/code-review` of the
integration branch (`docs/WORKFLOW.md` §5).

## Concurrency

- At most **three writers** at once (implementers and test-authors), each in its own git
  worktree (`isolation: "worktree"`) on its own branch cut from the integration branch.
- At most **one merger** at a time: merges into the integration branch are serialised.
- Readers (scout, verifier, reviewers, designer) run beside the writers, at most two at once.
- `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS=4` (`.claude/settings.json`). The VM has 4 vCPU; a
  fourth Maven build in parallel makes every build slower, not the batch faster (measured in
  r8-mapping: a scoped build of 4 s alone took 9–10 s with three in parallel). The build
  skeleton ticket re-measures this here and records it in `bench/RESULTS.md`.
- Worktrees share `~/.m2/repository` (file locking in `.mvn/maven.config`). JMH runs alone:
  no other build while a benchmark is measuring.
- No nested delegation: subagents do not start subagents, except `/code-review` started by the
  architect, which starts its two reviewers.
- When the architect has nothing else to do while one agent runs, it waits in the foreground
  (`run_in_background: false`). Background only with two or more agents in flight.

## Hand-offs

**Design draft (designer → architect, class C)** — at most 40 lines: proposal (types, formats,
signatures), proposed test names, rejected alternatives with one line each, what is expensive to
reverse (→ ADR draft), conflicts with ADRs or the glossary, open questions.

**Grilling advice (grilling-adviser → architect, every `wayfinder:grilling` ticket)** — the
designer (first round) or the architect (later rounds) writes the questions and recommendations;
the adviser evaluates that draft and never writes the round itself. At most 40 lines: the
frontier questions with choices, the cost of each choice and its recommendation;
disagreements with the draft; later-round questions; missing facts. The architect asks each
round with the adviser's recommendation and names any disagreement with the draft.

**Acceptance spec (architect → test-author, class C)** — ticket id, worktree branch, allowed test
paths, behaviours one line each with test names, fixtures, what counts as red.

**Ticket packet (architect → implementer)** — ticket id and URL, worktree branch and its base
(integration branch tip), allowed paths, the red that defines done (test names, oracle case ids),
the ADRs and glossary terms that govern it, checks to run, constraints. Pointers, not copies:
the implementer reads the ticket, the ADRs and the code itself.

**Hand-back (any agent → architect)** — at most 40 lines: changed paths, one line per path group,
commands run with evidence word and log path, remaining uncertainty, proposed next action. A
failure quotes the failing test and at most 10 lines of its section. A turn cap returns partial
work, marked partial. No agent other than the architect comments on, labels or closes issues.

## Command rules

Every agent that runs commands follows these; packets do not restate them. They keep the worktree
guard and the owner's no-approval-prompt rule from stopping a run.

- Run one plain command per shell call: no `&&` or `;` chains, no heredocs, no chmod chains. Use
  `git -C <dir> <subcommand>` for git, with one git call per shell call. The one exception is a
  gate the architect names, run as a single `bash -c`.
- Read files only with Read, Grep and Glob (not `cat`, `head`, `tail`, `sed -n` or `less`); read a
  log's tail with Read and an offset. Change files with Write or Edit. Write a commit message to a
  scratch file with Write, then `git commit -F <file>`.
- Script in bash files run as `bash <script>`, or in the build tool; python is out, in any form.
- Run long checks (mutant sweeps, gates, `gh pr checks --watch`) in the foreground and hand back
  after they finish. A hand-back that says "waiting for" a running check is not a hand-back.

## Evidence vocabulary

Every check reported anywhere (hand-back, issue comment, PR body) carries one of:

- `VERIFIED_PASS` — ran, observed green, log path given;
- `EXECUTED_FAIL` — ran, observed red, log path and failing section given;
- `NOT_RUN` — not executed, with the reason;
- `ENVIRONMENT_BLOCKED` — could not run here, with the exact error.

A claim without observed output behind it is not evidence. A skeleton is not the feature. A
number not in `bench/RESULTS.md` does not exist.

## Memory

Implementer, test-author, merger, reviewers and designer read
`.claude/agent-memory/<name>/MEMORY.md` first (committed; durable facts only — build quirks, verified
APIs, review findings that came back twice; never ticket state; under 120 lines). They write it
through the hand-back: a last `Worth recording:` block, one line per fact. The architect appends
those lines at the record step; no subagent edits `.claude/`. When a review
FAIL repeats a lesson, the lesson goes into the reviewer's memory and the writers' self-check
lists in the same commit.
