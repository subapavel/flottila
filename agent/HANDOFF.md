# Handoff

state: RUNNING (owner RESUME, 2026-10-26) — P1-08b done (review PASS r2); next P1-08c [C], then P1-09 [B]; stop before P1-10 (Q-2)
last commit: `Task: P1-08b` (this commit); 1a8bba8 `Task: FIX-15`; 17c6fbf HANDOFF
branch: claude/stoic-cannon-2byixj (session branch set by the environment; main = 17c6fbf at start)
next exact action: P1-08c [C] — designer draft (stop deadline, RequestScope.cancel, 503 vs kept 202, contract "cancel" case), then spec
then: P1-09 (admin API, incl. the held-item release route of D-055); stop before P1-10 (Q-2 waits for P1-10)
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: ab6a86e run 39337858206 green; fc10914 run 39329800061 green; 2be5c0e run 39329153094 green; 04c73ce run 39327954728 green
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-16 (XSLT `doc()`), FIX-17 (cache keys), FIX-18 (`discard` unchecked close), FIX-19 (worker byte budget), FIX-20 (own re-claim)
decisions pending review at hand-over: D-022, D-025–D-047 except D-028, D-049–D-052, D-055 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033/Q-1, D-043)
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
D-054 live on main (58a0053: Opus architect, row classes, designer agent); start the session with /model claude-opus-5-5, /effort medium
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
