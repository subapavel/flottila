# Handoff

state: RUNNING — main green on 0917f52 (FIX-7 done: three CI runs, one runtime number); P1-08 is the next ready task
last commit: FIX-7 close-out (this one, docs only); before it 0917f52 `Task: FIX-7` (D-050), 32a89c0, f798a76 (AGENTS, D-048); Phase 0 gate MET on 7f3e4e3
branch: main
next exact action: P1-08 — delivery modes: acceptance spec (NOTES.md "Next task — P1-08") to the test-author, packet to the implementer, verifier, reviewer
P1-08 red tests: runtime `flow/DeliveryModesTest` (one case per mode, fake clock for the save timeout) and an IT for "no 202 without a committed ingress row"
P1-08 decisions: error-to-status table (D-049, ARCHITECTURE §4); cancel semantics for a consumer stopped mid-delivery (D-046 left it open)
CI: 0917f52 — runs 39239278080 (push), 39239298540, 39239301784 (dispatch) green incl. mutation, bench; runner coverage per module == committed baseline
baseline: `ci/coverage-baseline.json` is the measured value (D-050); a lowered number in its diff must match the PROGRESS measurement — the reviewer checks it
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes (pending): FIX-8 (http contract port), FIX-9 (empty `.part` on a refused key), FIX-10 (double resume after attach), FIX-2, FIX-5, FIX-6, Q-1
decisions pending review at hand-over: D-022, D-025–D-047 except D-028, D-050 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033/Q-1, D-043)
roles (D-048): the session is the architect only; test-author, implementer, reviewer on Opus 5.5, verifier on Sonnet 5.5; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: start services detached (NOTES.md "Build and VM"); after a mutant run restore the source with `cp`/`touch`, never `mv`, or `mvn clean -pl <module>`
notes: agent/NOTES.md, one section per area (fix main, P1-08, steps, components, DLQ, recording, pipeline, core, bench, build and VM, fixtures)
