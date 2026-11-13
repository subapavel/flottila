# Handoff

owner 2026-11-06: no benchmarks until the dedicated test machine (next week, or rented per run); work the external review (docs/review/)
state: RUNNING core rework; CORE-0..4, 5a, 5a2, 6a/b, 7, 9, 12, 14a/b, 15, 17, P2-15a..c, P2-16a/b, FIX-136..146 (not 138b), 156, 169, 172b/c, 173, 175, 176
last commit: `Task: CORE-5a2` (this); 299fc5a D-141; 901155c `Task: FIX-175`; 4f85a68 `Task: FIX-176`; a0a0409 `Task: CORE-5a`
owner 2026-11-03: D-101/P2-15 lease, D-102/P2-16 events, P2-14, CONSIDER-10; core rework rows CORE-0..12, CORE-GATE
branch: claude/brave-hopper-5vwaju (owner brainstorm commits), not yet on main
next exact action: CORE-5b red spec (packet CORE-5 '5b advisor' B1-B8, Q1-Q5 + D-141 A1, A8, epoch check); flakes FIX-183/184/185 known
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse metadata-only at-least-once at load; Q-2 (bytes)
CI: c652f49 run 148 green (tree = 7506325 + docs); fea6c5c runs 145 + 146 (dispatch: build, stack, bench, mutation) green; FIX-101 open
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-5 (managed PG vs ha, FIX-136); ISSUE-2..4 answered
open fixes: FIX-16,18..20,22..29,31,33,34,36..43,45..52,54..64, 68,71..75,77..84,86..104,109,112,113; CONSIDER-1..3,5..9 (owner)
pending at hand-over: D-022, D-025–61 exc. 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..24, D-096..97, D-099, D-100
owner at hand-over: F-18 (5 ms target; load regression until FIX-95); D-064/65B/67A/72/73
roles (D-054): architect Opus 5.5 medium; row class sets model+effort; designer Opus xhigh (C); gate-reviewer + architecture advisor Fable 5.2 (D-131, D-137)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; agent logs/diffs in `agent/work/` (FIX-111); no stress-ng
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
notes: agent/NOTES.md by area (delivery modes, steps, components, DLQ, recording, pipeline, core, bench, VM; fixtures "FIX-10", "FIX-12")
