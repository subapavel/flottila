# Handoff

state: RUNNING — Phase 2; GATE run 1 found FIX-105 (done), FIX-106 (F-21, ISSUE-3), FIX-107; GATE after them
last commit: `Task: FIX-105` (this commit); 5505b0f `Task: P2-07`; a39a3ed `Task: P2-02b`; 2c5599e `Task: P2-06`
branch: claude/stoic-cannon-2byixj, pushed to main as well (local refs/heads/main is stale; origin/main is the truth)
next exact action: FIX-106 [C] — red tests in flight (packet agent/packets/FIX-106.md; docs F-21/ISSUE-3/§4/§8 uncommitted, ride with it)
then: FIX-107 [B]; GATE [G] re-run (agent/packets/GATE.md: stack run 1 done, numbers run 2 pending); P2-02b2, P2-02c, P2-07b/c; FIX-68..
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: 5505b0f run 141 green; run 142 dispatch (bench+mutation) on 5505b0f for the gate; ecb721a run 137 hung → FIX-101
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2 answered 2026-10-28 (option 1+2); ISSUE-3 GATE check 6 CPU (F-21), open
open fixes: FIX-16,18..20, FIX-22..29,31, FIX-33,34,36..43, FIX-45..52, FIX-54..64, FIX-68, FIX-71..75, FIX-77..84, FIX-86..104, FIX-106..108; CONSIDER-1..3,5..8 (owner)
pending at hand-over: D-022, D-025–61 exc. 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..21
owner at hand-over: F-18 (5 ms target; load regression until FIX-95); D-064/65B/67A/72/73
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
