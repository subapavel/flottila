# Handoff

state: RUNNING — FIX-5 closed (run 39329153094 green on 2be5c0e); WIP FIX-6 (test-author on fix6-spec.md); then FIX-15, P1-08b/c, P1-09
last commit: this close-out; 2be5c0e `Task: FIX-5` (test-only, review PASS); 04c73ce FIX-2 close-out; gate MET on 7f3e4e3
branch: main
next exact action: WIP FIX-6 — test-author on fix6-spec.md (red = `resolve` missing, outside includes compiling; test edits uncommitted), then implementer
P1-08 leftovers as rows: P1-08b (worker driver, lease, attempts bound, ghost WARNING test; nothing polls in production yet), P1-08c (cancel), Q-2 (bytes)
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2
CI: 2be5c0e run 39329153094 green; 04c73ce run 39327954728 green; b536615 run 39327535168 green; 8b722af run 39325196427 green
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-5: unchanged, test-only)
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-6, then FIX-15 (FIX-14 review: unchecked failures, cleanup in finally); then P1-08b, P1-08c, P1-09; stop before P1-10
decisions pending review at hand-over: D-022, D-025–D-047 except D-028, D-049, D-050, D-051 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033/Q-1, D-043)
roles (D-048): the session is the architect only; test-author, implementer, reviewer on Opus 5.5, verifier on Sonnet 5.5; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
scratchpad (session-only): spec fix6 (final) + packet fix6 + D-052 draft (fix6-d052.md), facts p108b/p108c/p109 — re-scout if lost; logs in target/
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps, components, DLQ, recording, pipeline, core, bench, build and VM, fixtures)
