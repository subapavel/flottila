# Handoff

state: SESSION_END (owner, 2026-10-19) — FIX-6 committed (`Task: FIX-6`, D-052), CI green (run 39337858206 on ab6a86e); next: FIX-15 (row 80, spec in the row)
last commit: `chore(agent): turn economy` (D-053, docs + owner patch); 3fa3585 FIX-6 close-out; ab6a86e `Task: FIX-6` (review PASS); gate MET on 7f3e4e3
branch: main
next exact action: FIX-15 — test-author on the three red cases in BACKLOG row FIX-15 (seam `encrypting(Path, OutputStream)`), implementer, verifier, reviewer
then: P1-08b (worker driver, lease, attempts bound, ghost WARNING test), P1-08c (cancel), P1-09 (admin API); stop before P1-10 (Q-2 waits for P1-10)
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2 (bytes)
CI: ab6a86e run 39337858206 green; fc10914 run 39329800061 green; 2be5c0e run 39329153094 green; 04c73ce run 39327954728 green
baseline: `ci/coverage-baseline.json` is the measured value (D-050), `--write` at every task commit (FIX-6: api 99.0/100, validate 100/90.0, xslt 100/96.6)
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-15 (FIX-14 review); FIX-16 (XSLT run-time `doc()`, a decision row; compose with `CompileConfinement`); FIX-17 (compile caches keyed by hash)
decisions pending review at hand-over: D-022, D-025–D-047 except D-028, D-049–D-052 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033/Q-1, D-043)
roles (D-054): architect Opus 5.5 medium; row class A/B/C/G sets model+effort per call; designer Opus xhigh for C; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; no stress-ng (shell hogs)
owner first: `git apply agent/patches/opus-architect-claude.patch` + commit (model, designer; D-054); re-scout P1-08b/c, P1-09
proposal for the owner (FIX-10 PROGRESS "Found"): a reconcile loop would replace the hand-over flags (`pausing`, `handingOver`, two flags, `again`) — not done
fixtures: NOTES "FIX-10" (hand-over protocol, staged fixtures) and "FIX-12" (drain pattern); contract suite hook `producerEndpoint` + self-test variants (FIX-8)
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps (FIX-6 Saxon reads), components, DLQ, recording, pipeline, core, bench, VM)
