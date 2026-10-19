# Handoff

state: RED MAIN until CI confirms FIX-7 — fix committed (review PASS, VM green); "fix main" is the only ready task until its CI run is green (WORKFLOW §3)
last commit: FIX-7 on main (this one), trailer `Task: FIX-7` (D-050); before it 32a89c0 (diagnosis), f798a76 (AGENTS, D-048); Phase 0 gate MET on 7f3e4e3
branch: main
next exact action: observe CI on the FIX-7 commit (push + two dispatched reruns); green with one runtime number → FIX-7 `done` in the P1-08 commit, then P1-08
if red: FIX-7 reopens — runner's jacoco.xml (build-reports artifact), NOTES.md "Fix main — FIX-7"; never widen the band or edit the baseline by hand
P1-08 — delivery modes: per NOTES.md "Next task — P1-08"; acceptance spec to the test-author first, packet to the implementer, verifier before the commit
P1-08 red tests: runtime `flow/DeliveryModesTest` (one case per mode, fake clock for the save timeout) and an IT for "no 202 without a committed ingress row"
P1-08 decisions: error-to-status table (D-049, ARCHITECTURE §4); cancel semantics for a consumer stopped mid-delivery (D-046 left it open)
CI before FIX-7: run 38 (push, b2cdbf4) green; 39, 40, 41 (b2cdbf4 dispatch, f798a76, 32a89c0) red at the no-decrease gate; mutation, bench skipped behind it
baseline: `ci/coverage-baseline.json` is the measured value (D-050); a lowered number in its diff must match the PROGRESS measurement — the reviewer checks it
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes (pending): FIX-8 (http contract port), FIX-9 (empty `.part` on a refused key), FIX-10 (double resume after attach), FIX-2, FIX-5, FIX-6, Q-1
decisions pending review at hand-over: D-022, D-025–D-047 except D-028, D-050 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033/Q-1, D-043)
roles (D-048): the session is the architect only; test-author, implementer, reviewer on Opus 5.5, verifier on Sonnet 5.5; gate-reviewer Fable 5.1 (AGENTS.md)
VM: start services detached (NOTES.md "Build and VM"); after a mutant run restore the source with `cp`/`touch`, never `mv`, or `mvn clean -pl <module>`
notes: agent/NOTES.md, one section per area (fix main, P1-08, steps, components, DLQ, recording, pipeline, core, bench, build and VM, fixtures)
