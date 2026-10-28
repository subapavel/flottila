# Handoff

state: RUNNING — P1-GATE blocked on ISSUE-2 (gate NOT_MET on §13 durable ACK only, 350f71d); continuing with ready rows outside Phase 2
last commit: `Task: P1-09f` (this commit); 88d3522 `Task: P1-GATE` (blocked on ISSUE-2); 350f71d gate commit
branch: claude/stoic-cannon-2byixj, pushed to main as well (local refs/heads/main is stale; origin/main is the truth)
next exact action: P1-09g (shared `from` listener refused at deploy); then FIX-68, FIX-71..75; P2-* wait on the owner's ISSUE-2 answer
then: on an ISSUE-2 answer — option 1/3: finding + §13/GATE changelog, P1-GATE done; option 2: tune and re-measure
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: 22da747 run 40336093434 green (P1-08c); bfa400c run 40315078572 green (P1-08b); 1a8bba8 run 40300828984 green (FIX-15)
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback), ISSUE-2 (durable ACK p99 < 5 ms missed; blocks P1-GATE, owner-only)
open fixes: FIX-16, FIX-18..20, FIX-22..29, FIX-31, FIX-33,34,36..43, FIX-45..52, FIX-54..64, FIX-68, FIX-71..75; CONSIDER-1..3 (owner)
pending at hand-over: D-022, D-025–61 exc. 28/48/53/54, D-062/F-7, D-063, D-066/F-10, D-067..69, F-11, D-070/F-12, F-13 closed, D-071; decided: D-064/65B/67A
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
D-054 live on main (58a0053: Opus architect, row classes, designer agent); start the session with /model claude-opus-5-5, /effort medium
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
