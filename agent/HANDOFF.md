# Handoff

state: RUNNING — GATE blocked on ISSUE-3 only (check 6 CPU per step, F-21); HANDOVER.md committed; Phase 2 rows continue
last commit: `Task: GATE` (this commit, docs); 7506325 `Task: P2-02b2`; fea6c5c `Task: FIX-107` (gate commit)
branch: claude/stoic-cannon-2byixj, pushed to main as well (local main synced to origin/main 7506325)
next exact action: FIX-110 [B] (off-node validate/test of a package with dependencies: no consumers started, deployed --env works)
then: P2-02c, P2-07b, P2-07c, FIX-109; FIX-108 if ISSUE-3 says option 2; owner answer to ISSUE-3 → GATE.md wording + GATE_MET
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: 7506325 run 147 in progress at commit; fea6c5c runs 145 + 146 (dispatch: build, stack, bench, mutation) green; FIX-101 open
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2 answered 2026-10-28 (option 1+2); ISSUE-3 GATE check 6 CPU (F-21), open
open fixes: FIX-16,18..20,22..29,31,33,34,36..43,45..52,54..64, 68,71..75,77..84,86..104,108..110; CONSIDER-1..3,5..8 (owner)
pending at hand-over: D-022, D-025–61 exc. 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..21
owner at hand-over: F-18 (5 ms target; load regression until FIX-95); D-064/65B/67A/72/73
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
