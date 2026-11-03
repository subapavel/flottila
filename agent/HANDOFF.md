# Handoff

state: RUNNING (owner RESUME 2026-11-02, "until STOP"); P2-07c done (review PASS r2); next FIX-109
last commit: `Task: P2-07c` (this commit); 9764a45 `Task: P2-07b2`; e1955ab `Task: P2-07b1`; b046743 `Task: GATE` (GATE_MET)
branch: claude/dazzling-gauss-9x7tiw, pushed to main as well
next exact action: FIX-109 [B] (interrupt edges) spec → red tests → implementer; then P2-09..13 (P2-13 before FIX-95a/b); FIX-112..114
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: c652f49 run 148 green (tree = 7506325 + docs); fea6c5c runs 145 + 146 (dispatch: build, stack, bench, mutation) green; FIX-101 open
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
WF-1 done (skills, no command hook; hook kept in agent/patches/guard-commands-hook.patch); WF-2/D-092 deferred by the owner
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2 answered 2026-10-28 (option 1+2); ISSUE-3 answered 2026-10-30 (option 2)
open fixes: FIX-16,18..20,22..29,31,33,34,36..43,45..52,54..64, 68,71..75,77..84,86..104,109,112,113; CONSIDER-1..3,5..9 (owner)
pending at hand-over: D-022, D-025–61 exc. 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..88, F-15..21, D-096, D-097/F-23
owner at hand-over: F-18 (5 ms target; load regression until FIX-95); D-064/65B/67A/72/73
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; agent logs/diffs in `agent/work/` (FIX-111); no stress-ng
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
