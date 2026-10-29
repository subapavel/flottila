# Handoff

state: RUNNING — Phase 2; P2-03b2 done (`flottila test` feed cases, D-080 amended); P2-04 next
last commit: `Task: P2-03b2` (this commit); da7ed3c `Task: P2-03b1`; 799f5f0 `Task: P2-03a`; 4a6039b `Task: FIX-85`
branch: claude/stoic-cannon-2byixj, pushed to main as well (local refs/heads/main is stale; origin/main is the truth)
next exact action: P2-04 [B] — read its row + D-076; write the packet (`secret://` from env vars, resolved before consumer/producer)
then: P2-08, P2-02L, P2-02D; FIX-68,71,73..75,77..84,86..91 between; later (owner): FIX-72, FIX-76, CONSIDER-3,5; CONSIDER-4
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: 799f5f0 (P2-03a) run 129 green; da7ed3c (P2-03b1) run 130 green; the P2-03b2 commit not yet checked
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback); ISSUE-2 answered by the owner 2026-10-28 (option 1+2)
open fixes: FIX-16,18..20, FIX-22..29,31, FIX-33,34,36..43, FIX-45..52, FIX-54..64, FIX-68, FIX-71..75, FIX-77..84, FIX-86..91; CONSIDER-1..3,5 (owner)
pending at hand-over: D-022, D-025–61 exc. 28/48/53/54, D-062/F-7, D-063, D-066..69/F-10, F-11, D-070/F-12, D-071/74..80, F-15..17; owner: D-064/65B/67A/72/73
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
D-054 live on main (58a0053: Opus architect, row classes, designer agent); start the session with /model claude-opus-5-5, /effort medium
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
