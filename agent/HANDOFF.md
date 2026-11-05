# Handoff

state: RUNNING core rework (priority 1, docs/CORE-REWORK.md, D-103/D-104); CORE-0..4, 6a, 6b, 15 done; D-110 no size refusal
last commit: `Task: FIX-124` (this commit); aa01415 `Task: CORE-15`; 4ba1919 `Task: CORE-6b`
owner 2026-11-03: D-101/P2-15 lease, D-102/P2-16 events, P2-14, CONSIDER-10; core rework rows CORE-0..12, CORE-GATE
branch: claude/brave-hopper-5vwaju (owner brainstorm commits), not yet on main
next exact action: stepped open-loop run (ack, recording none) → RESULTS; then owner order: CORE-8 (at-least-once half), P2-15 → P2-16 → CORE-7
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse metadata-only at-least-once at load; Q-2 (bytes)
CI: c652f49 run 148 green (tree = 7506325 + docs); fea6c5c runs 145 + 146 (dispatch: build, stack, bench, mutation) green; FIX-101 open
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
WF-1 done (skills, no command hook; hook kept in agent/patches/guard-commands-hook.patch); WF-2/D-092 deferred by the owner
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2, ISSUE-3 answered; ISSUE-4 answered 2026-11-04 (D-106)
open fixes: FIX-16,18..20,22..29,31,33,34,36..43,45..52,54..64, 68,71..75,77..84,86..104,109,112,113; CONSIDER-1..3,5..9 (owner)
pending at hand-over: D-022, D-025–61 exc. 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..24, D-096..97, D-099, D-100
owner at hand-over: F-18 (5 ms target; load regression until FIX-95); D-064/65B/67A/72/73
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; agent logs/diffs in `agent/work/` (FIX-111); no stress-ng
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
notes: agent/NOTES.md by area (delivery modes, steps, components, DLQ, recording, pipeline, core, bench, VM; fixtures "FIX-10", "FIX-12")
