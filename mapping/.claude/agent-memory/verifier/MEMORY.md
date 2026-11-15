# verifier memory

Durable facts only (AGENTS.md, Memory).

- The shell does not keep a `cd` outside the repo: run a gate as one plain command by absolute
  path, `/…/verify-<ticket>/scripts/gate-ticket.sh <args>`, no `bash -c`, no `cd`, no redirect. It
  creates `.logs` and writes `.logs/<ticket>-gate.log` (`gate-spec.sh`: `.logs/<spec>-spec-gate.log`)
  under its own worktree (#125).
- `gate-ticket.sh` appends `engine/oracle` itself: never list `oracle` in the modules ("Could not
  find the selected project in the reactor").
- Always gate in a fresh `verify-*` worktree: a stale `jacoco.exec` fails service coverage.
- Spec gate step 5 (JMH) is NOT_RUN until spec 6 brings the benchmark jar.
