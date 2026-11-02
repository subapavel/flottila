---
name: diagnosing-bugs
description: Diagnosis loop for a FIX row, a hard bug or a performance regression in Flottila.
disable-model-invocation: true
---

<!-- Adapted from mattpocock/skills `diagnosing-bugs` (MIT, see ../LICENSE-mattpocock-skills).
     Read by path: implementer.md and test-author.md point here for FIX rows. -->

A discipline for hard bugs. Skip a phase only with the reason written in the handback.

**Who does what.** The test-author writes the regression test (Phase 5 steps 1–2) from the
architect's spec; the implementer receives it in the packet, already red and approved. As the
implementer, the packet's red test *is* your Phase 1 loop and your regression test: you add no
test in its place, and a deeper seam you find goes into the handback for the test-author. You run
autonomously: where the original asks the user, write it into the handback instead and carry on
with your own ranking.

**Redact** every secret in what you show (`<REDACTED>`); `secret://` references stay references.

## Phase 1: Build a feedback loop

**This is the skill.** With a **tight** pass/fail signal that goes **red** on *this* bug, the cause
will be found; without one, reading code will not find it. Spend disproportionate effort here.

Ways to build one, roughly in this order:

1. A failing JUnit test at the seam that reaches the bug (unit, contract suite, `-Pit`
   Testcontainers), run as `mvn -B -ntp -q -pl <module> -Dtest=<Class>#<method> … >
   agent/work/<task>-loop.log 2>&1`.
2. The CLI (`flottila validate|test`) on a fixture package, diffing output against a known-good one.
3. An HTTP request against a node of the compose stack (NOTES "Build and VM").
4. Replay of a captured message: the trail already holds every body and step — replay it.
5. A throwaway harness in `agent/work/` (one component, mocks, a single call).
6. A jqwik property loop for "sometimes wrong output".
7. `git bisect run` over a harness when the bug appeared between two known commits — in a scratch
   tree (`git worktree add --detach agent/work/bisect <commit>`), never in the shared tree; remove it with
   `git worktree remove --force agent/work/bisect && git worktree prune` when done.
8. A differential loop: the same input through two commits or two configurations.

**Tighten** it: faster (one module, one test, no reactor root), sharper (assert the exact
symptom, not "no exception"), deterministic (fake clock, seeded RNG, injected ids — never sleeps).

Non-deterministic bugs: raise the reproduction rate (loop 100×, parallelise, narrow the window).
Stress and timing tricks live only in the throwaway loop; the committed regression test uses the
fake clock and latches (`CLAUDE.md`, Tests).

**Completion criterion.** One command, already run and shown with its output, that is
red-capable (drives the real code path and asserts the reported symptom), deterministic, fast
(seconds) and agent-runnable. No such command: stop, hand back partial with what you tried and
what access would build the loop. Reading code to build a theory before this command exists is
the exact failure this skill prevents.

## Phase 2: Reproduce and minimise

Run the loop; confirm it fails with the **reported** symptom, not a neighbour. Then cut inputs,
steps, config and data one at a time, re-running after each cut. Done when every remaining element
is load-bearing: removing any one turns the loop green. The implementer minimises in a throwaway
copy under `agent/work/`, never in the packet's red test.

## Phase 3: Hypothesise

Write **3–5 ranked, falsifiable hypotheses** before testing any: "If X is the cause, changing Y
makes the bug disappear / changing Z makes it worse." A hypothesis without a prediction is a vibe:
sharpen or drop it. Put the ranked list in the handback (the architect may re-rank it).

## Phase 4: Instrument

Each probe maps to one prediction; change one variable at a time. Debugger or a targeted log at a
boundary that separates hypotheses; never "log everything and grep". Tag every debug line with a
unique prefix (`[DEBUG-a4f2]`) so cleanup is one grep.

**Perf branch:** measure first — JFR, the bench harness, `EXPLAIN ANALYZE` — then bisect. A number
enters the repo only through `bench/RESULTS.md` with machine, date, configuration and commit.

## Phase 5: Fix and regression test

Steps 1–2 belong to the test-author; the implementer starts at step 3 with the packet's test.

The regression test comes before the fix, at a **correct seam**: one that exercises the real bug
pattern as it occurs at the call site. If only a shallow seam exists, that is the finding — say so
in the handback; the architect decides on a finding (`docs/findings/`).

1. Turn the minimised repro into a failing test at that seam; observe it red, for the right reason.
2. If you forced red by mutating code or a fixture, `diff` against a pristine copy (restore with
   `cp`, never `mv`).
3. Fix. Observe green. Re-run the Phase 1 loop on the original, unminimised scenario.

## Phase 6: Cleanup — required before hand-back

- [ ] Phase 1 loop green on the original scenario
- [ ] The packet's regression test green (or the missing seam named in the handback)
- [ ] `grep -rn "\[DEBUG-"` over the touched modules returns nothing
- [ ] Throwaway harnesses under `agent/work/` only, nothing committed
- [ ] The hypothesis that proved correct is stated in the handback, for the commit body
