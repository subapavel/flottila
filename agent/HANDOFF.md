# Handoff

state: RUNNING — P1-10b done (kill -9 ledger); next row P1-10c [B] (50 MB through a stream step, packet agent/packets/P1-10.md §c)
last commit: `Task: P1-10b` (this commit); bb5c3e4 `Task: P1-10a`; 4bf9d4d `Task: P1-09c3b`
branch: claude/stoic-cannon-2byixj (session branch set by the environment; main = 17c6fbf at start)
next exact action: P1-10c [B] — test-author writes StreamHeapIT red test (packet §c)
then: P1-10d native run (fix native PG role password first, packet notes), P1-GATE, Phase 2
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: 22da747 run 40336093434 green (P1-08c); bfa400c run 40315078572 green (P1-08b); 1a8bba8 run 40300828984 green (FIX-15)
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-16, FIX-18..20, FIX-22..29, FIX-31, FIX-33,34,36..43, FIX-45..52, FIX-54..64; CONSIDER-1, CONSIDER-2 (owner: not ready)
pending at hand-over: D-022, D-025–61 exc. D-028/48/53/54, D-062/F-7, D-063, D-066/F-10, D-067..D-069, F-11, D-070/F-12; decided: D-064, D-065 B, D-067 A
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
D-054 live on main (58a0053: Opus architect, row classes, designer agent); start the session with /model claude-opus-5-5, /effort medium
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
