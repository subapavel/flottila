# Handoff

state: RUNNING — FIX-9 done (CI green); WIP FIX-14: red approved (target/fix14-red.log), implementer running; then FIX-2, FIX-5, FIX-6, P1-08b/c, P1-09
last commit: FIX-9 close-out (this one); 29959d3 `Task: FIX-9` (review PASS, M1/M3 killed, M2 equivalent); 7e85056 FIX-8 close-out; gate MET on 7f3e4e3
branch: main
next exact action: FIX-14 — implementer round 2 (validate once; red in the tree, uncommitted), then verifier (fix14-verifier-brief.md) and reviewer, commit
P1-08 leftovers as rows: P1-08b (worker driver, lease, attempts bound, ghost WARNING test; nothing polls in production yet), P1-08c (cancel), Q-2 (bytes)
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2
CI: 29959d3 run 39322295491 green; 7e85056 run 39320983157 green; be94a6f run 39320191435 green; 27949bb run 39316404961 green
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-9: runtime line 99.7 → 99.2, FIX-14 restores it)
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-14 (opened by the FIX-9 review, taken next: the same defect one throw later), FIX-2, FIX-5, FIX-6; then P1-08b, P1-08c, P1-09; stop before P1-10
decisions pending review at hand-over: D-022, D-025–D-047 except D-028, D-049, D-050, D-051 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033/Q-1, D-043)
roles (D-048): the session is the architect only; test-author, implementer, reviewer on Opus 5.5, verifier on Sonnet 5.5; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
scratchpad (session-only): specs fix14 (next), fix2/fix5/fix6 (final), facts p108b/p108c/p109 — re-scout if lost; logs, diffs in target/
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps, components, DLQ, recording, pipeline, core, bench, build and VM, fixtures)
