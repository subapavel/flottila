# Handoff

state: RUNNING — Phase 2; FIX-105..107 done (GATE run 1 findings); GATE re-run next; check 6 blocked on ISSUE-3 (F-21)
last commit: `Task: FIX-107` (this commit); 741fe09 `Task: FIX-106`; ef6fc10 `Task: FIX-105`; 5505b0f `Task: P2-07`
branch: claude/stoic-cannon-2byixj, pushed to main as well (local refs/heads/main is stale; origin/main is the truth)
next exact action: GATE [G] re-run on this commit — packet agent/packets/GATE.md: stack run (checks 1–8, kill round), numbers run 2, CI dispatch
then: HANDOVER.md + gate-reviewer; GATE blocked on ISSUE-3 if only check 6 fails; then P2-02b2, P2-02c, P2-07b/c, FIX-109; FIX-68..
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: 741fe09 pending at commit; ef6fc10 run 143 green; 5505b0f runs 141 + 142 (dispatch: bench, mutation 67 min) green; FIX-101 open
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2 answered 2026-10-28 (option 1+2); ISSUE-3 GATE check 6 CPU (F-21), open
open fixes: FIX-16,18..20,22..29,31,33,34,36..43,45..52,54..64, 68,71..75,77..84,86..104,108,109; CONSIDER-1..3,5..8 (owner)
pending at hand-over: D-022, D-025–61 exc. 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..21
owner at hand-over: F-18 (5 ms target; load regression until FIX-95); D-064/65B/67A/72/73
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
