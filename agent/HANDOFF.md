# Handoff

state: RUNNING — FIX-10 accepted by CI (run 39315253634 on 8cc25a2); FIX-13 in flight (test green, verifier done, review PASS; closing); then FIX-8, FIX-9 …
last commit: FIX-10 close-out (this one); 8cc25a2 `Task: FIX-10` (nine rounds, R1–R16, D7 rewritten); e4dcf00 FIX-12 close-out; gate MET on 7f3e4e3
branch: main
next exact action: FIX-13 — on the reviewer's final PASS: PROGRESS (fix13-progress-draft.md), BACKLOG row, HANDOFF, commit `Task: FIX-13` (fix13-commit-msg.txt), push, CI
P1-08 leftovers as rows: P1-08b (worker driver, lease, attempts bound, ghost WARNING test; nothing polls in production yet), P1-08c (cancel), Q-2 (bytes)
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2
CI: 8cc25a2 run 39315253634 green (mutation and bench skipped by the path filter); dd5fc7d run 39271119771 green; 6ad3f19 run 39262518817 green
baseline: `ci/coverage-baseline.json` is the measured value (D-050), rewritten by `--write` at FIX-10 (runtime branch 100.0 → 99.2, PROGRESS "Measured")
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-13 in flight (testkit coverage gap), then FIX-8, FIX-9, FIX-2, FIX-5, FIX-6 — owner's order: every fix, then P1-08b, P1-08c, P1-09; stop before P1-10
decisions pending review at hand-over: D-022, D-025–D-047 except D-028, D-049, D-050 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033/Q-1, D-043)
roles (D-048): the session is the architect only; test-author, implementer, reviewer on Opus 5.5, verifier on Sonnet 5.5; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; Surefire `-Dtest=A,B`
packets and specs live in the session scratchpad, not under target/ (P1-08 lost them to a root `mvn clean`); only logs and diffs go to target/
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures, `nextHold` consumed before `reached`) and "FIX-12" (drain pattern) — the hand-over templates
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps, components, DLQ, recording, pipeline, core, bench, build and VM, fixtures)
