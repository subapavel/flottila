# Handoff

state: RUNNING — P1-09d1a done; next row P1-09d1b [C]
last commit: `Task: P1-09d1a` (this commit); 9df3f78 `Task: P1-09d3`; b02c6db `Task: P1-09b`
branch: claude/stoic-cannon-2byixj (session branch set by the environment; main = 17c6fbf at start)
next exact action: P1-09d1b [C] — test-author opus/xhigh from agent/packets/P1-09d1.md (d1b tests + D-060 amendment + carried items)
then: P1-09h (F-6, V6 bodies), P1-09d2 (F-5, effective hash), P1-09c (V7), P1-10 (label the machine honestly), P1-GATE, Phase 2 (P2-02a/b new)
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: 22da747 run 40336093434 green (P1-08c); bfa400c run 40315078572 green (P1-08b); 1a8bba8 run 40300828984 green (FIX-15)
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-16 (XSLT `doc()`), FIX-17..20, FIX-22..26, FIX-27 (sync bodies never evicted), FIX-28 (stale child exec)
pending at hand-over: D-022, D-025–D-047 exc. D-028, D-049–D-052, D-055, D-056/F-3, D-057/F-4/F-5, D-058/F-6, D-059, D-060
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
D-054 live on main (58a0053: Opus architect, row classes, designer agent); start the session with /model claude-opus-5-5, /effort medium
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
