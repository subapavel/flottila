# Handoff

state: RED MAIN — "fix main" is the only ready task (WORKFLOW §3); FIX-7 in BACKLOG, diagnosis in agent/NOTES.md "Fix main — FIX-7"
last commit: f798a76 on main, trailer `Task: AGENTS` (D-048); before it b2cdbf4 `Task: P1-07` (D-017, D-047); Phase 0 gate MET on 7f3e4e3
branch: main
next exact action: FIX-7 — runtime branch 97.8 % on the runner vs 100.0 % VM baseline: name the 2–3 branches from the runner's jacoco.xml, fix their tests.
after FIX-7: P1-08 — delivery modes. Spec and red tests per agent/NOTES.md "Next task — P1-08"; acceptance spec to the test-author first.
P1-08 red tests: runtime `flow/DeliveryModesTest` (one case per mode, fake clock for the save timeout) and an IT for "no 202 without a committed ingress row".
P1-08 decisions: error-to-status table (D-049, ARCHITECTURE §4); cancel semantics for a consumer stopped mid-delivery (D-046 left it open).
CI: run 38 (push, b2cdbf4) green; runs 39 (dispatch, b2cdbf4) and 40 (push, f798a76) red at the no-decrease gate; mutation, bench skipped. Record in FIX-7.
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: FIX-7 ready; FIX-2, FIX-5, FIX-6, Q-1 pending (BACKLOG Fixes section); `Retry` ignores `NackException.retryable` (NOTES.md, Steps)
decisions pending review at hand-over: D-022, D-025–D-047 except D-028 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033 with Q-1 pending, D-043 api types)
roles since D-048: the session is the architect only; test-author, implementer, verifier, reviewer on Opus/Sonnet 5.5; gate-reviewer on Fable 5.1 (AGENTS.md)
notes: agent/NOTES.md, one section per area (fix main first, then P1-08, steps, components, DLQ, recording, pipeline, core, bench, build and VM, fixtures)
