# Handoff

state: SESSION_END (owner, 2026-10-19) — FIX-6 committed (`Task: FIX-6`, D-052), CI pending (close-out records it); next: FIX-15 (row 80, spec in the row)
last commit: `Task: FIX-6` (this one; review PASS after four FAILs); fc10914 FIX-5 close-out; 2be5c0e `Task: FIX-5`; gate MET on 7f3e4e3
branch: main
next exact action: FIX-15 — test-author on the three red cases in BACKLOG row FIX-15 (seam `encrypting(Path, OutputStream)`), implementer, verifier, reviewer
then: P1-08b (worker driver, lease, attempts bound, ghost WARNING test), P1-08c (cancel), P1-09 (admin API); stop before P1-10 (Q-2 waits for P1-10)
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: FIX-6 run pending; fc10914 run 39329800061 green; 2be5c0e run 39329153094 green; 04c73ce run 39327954728 green
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-15 (FIX-14 review); FIX-16 (XSLT run-time `doc()`, a decision row; compose with `CompileConfinement`); FIX-17 (compile caches keyed by hash)
decisions pending review at hand-over: D-022, D-025–D-047 except D-028, D-049–D-052 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033/Q-1, D-043)
roles (D-048): the session is the architect only; test-author, implementer, reviewer on Opus 5.5, verifier on Sonnet 5.5; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
scratchpad: gone with the session — facts for P1-08b/c and P1-09 must be re-scouted; FIX-6 logs in target/ (fix6-*-r4.log) until the next build
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
