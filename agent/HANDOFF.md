# Handoff

state: RUNNING (owner RESUME 11-14, autonomous to Mon 11-16 08:00 Prague). Container restarted ~03:00, tree survived, services back up
last commit: 9ce63c7 FIX-59/60 (verify -Pit exit 0, 17 modules, 2950 tests, 0F/0E/0S); before it bf29933 FIX-121, a0b3f74 CONSIDER-11 design
branch: claude/relaxed-archimedes-q2nb4c; the owner-expected 4390a9b / brave-hopper never reached origin, so work runs from the pushed HEAD (WORKFLOW 1.1)
next exact action: FIX-71 (a)+(c) under D-104 — first settle with javap whether Vert.x `Future.future(handler)` captures an Error, then the packet
FIX-71 shape (D-104): widen BatchingWriter waitLocked:147 and commit():268 to `RuntimeException | Error`; commit refused windows, never fail them
D-104 conflict declared: FIX-69 acceptance and BatchingWriterWindowRefusalTest:28-32/:178/:236/:251 assert the opposite — wrong expectations
PIT a0b3f74 (agent/work/pit-a0b3f74.log, exit 0): api 179/180, core 1010/1019 = 99% vs the 80% floor; Trail 0 survivors; testkit 74% -> FIX-122
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: c652f49 run 148 green (tree = 7506325 + docs); fea6c5c runs 145 + 146 (dispatch: build, stack, bench, mutation) green; FIX-101 open
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2 answered 2026-10-28 (option 1+2); ISSUE-3 answered 2026-10-30 (option 2)
open fixes: FIX-16,18..20,22..29,31,33,34,36..43,45..52,54..64, 68,71..75,77..84,86..104,109,112,113; CONSIDER-1..3,5..10 (owner)
pending at hand-over: D-022, D-025-61 exc 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..24, D-096/97, D-099..101, F-25
owner at hand-over: F-18 (5 ms target; load regression until FIX-95); D-064/65B/67A/72/73
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; agent logs/diffs in `agent/work/` (FIX-111); no stress-ng
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
