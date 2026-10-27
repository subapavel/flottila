# Handoff

state: RUNNING — FIX-17 done; next row P1-09d2b [C]
last commit: `Task: FIX-17` (this commit); 2bbddb1 `Task: P1-09d2a`; dbdb7e8 `Task: FIX-30`
branch: claude/stoic-cannon-2byixj (session branch set by the environment; main = 17c6fbf at start)
next exact action: P1-09d2b [C] (redeploy swap; packet agent/packets/P1-09d2.md approved, design done) — test-author first
then: P1-09d2c, FIX-35, P1-09k (durable step bodies, D-064 owner's decision on F-8), P1-09c, P1-10, P1-GATE, Phase 2
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: 22da747 run 40336093434 green (P1-08c); bfa400c run 40315078572 green (P1-08b); 1a8bba8 run 40300828984 green (FIX-15)
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-16, FIX-18..20, FIX-22..29, FIX-31, FIX-33, FIX-34, FIX-35..42 (FIX-32 folded into d2b)
pending at hand-over: D-022, D-025–D-047 exc. D-028, D-049–D-052, D-055, D-056/F-3, D-057/F-4/F-5, D-058/F-6, D-059, D-060, D-061, D-062/F-7, D-063/F-9 (owner: run-time XSLT reads), D-064/F-8 (decided)
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
D-054 live on main (58a0053: Opus architect, row classes, designer agent); start the session with /model claude-opus-5-5, /effort medium
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
