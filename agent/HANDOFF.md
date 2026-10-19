# Handoff

state: RUNNING — FIX-11 done on this commit (D7 fixture; CI on it not yet observed); P1-08 accepted by CI; next ready task FIX-12, then FIX-10
last commit: this one, `Task: FIX-11`; before it 857d5e0 (P1-08 close-out), 9b1b519 `Task: P1-08` (D-049, F-2), 0917f52 `Task: FIX-7`; gate MET on 7f3e4e3
branch: main
next exact action: observe CI on this commit; then FIX-12 (testkit `GeneratedReadStream` drain loop — the row has the fix shape and done-when), then FIX-10
P1-08 leftovers as rows: P1-08b (worker driver, lease, attempts bound, ghost WARNING test; nothing polls in production yet), P1-08c (cancel), Q-2 (bytes)
P1-08 owner points at hand-over (D-049): reply-direction header allow-list; refuse `payload: metadata-only` for at-least-once at load time; Q-2
CI: 9b1b519 push run 39253266191 red (D7 only), dispatch re-run 39253535647 green incl. mutation and bench; 857d5e0 (docs) run 39254598435 green
baseline: `ci/coverage-baseline.json` is the measured value (D-050), rewritten by `--write` at P1-08 (PROGRESS "Measured"); a lowered number must match PROGRESS
open issues: ISSUE-1 (Context7 quota; javap fallback in use, nothing blocked)
open fixes: ready FIX-12 (testkit `GeneratedReadStream` lost resume, blob tests flaky), FIX-10 (lost backpressure); pending FIX-8, FIX-9, FIX-2, FIX-5, FIX-6
decisions pending review at hand-over: D-022, D-025–D-047 except D-028, D-049, D-050 (D-026/D-032 joined, D-029 addendum, D-031/F-1, D-033/Q-1, D-043)
roles (D-048): the session is the architect only; test-author, implementer, reviewer on Opus 5.5, verifier on Sonnet 5.5; gate-reviewer Fable 5.1 (AGENTS.md)
review rule since FIX-7: a PASS on changed production code needs mutants named by the reviewer and run by the verifier (reviewer memory, NOTES.md)
VM: services detached (NOTES.md "Build and VM"); mutant restore with `cp`/`touch`, never `mv`; never `mvn clean` at the reactor root; Surefire `-Dtest=A,B`
packets and specs live in the session scratchpad, not under target/ (P1-08 lost them to a root `mvn clean`); only logs and diffs go to target/
notes: agent/NOTES.md, one section per area (fix main, delivery modes, steps, components, DLQ, recording, pipeline, core, bench, build and VM, fixtures)
