---
name: gate
description: Verify a milestone gate on one commit of main — run every docs/GATE.md check through the verifier, collect the evidence, and have the gate-reviewer (Fable 5.2) return gate MET or NOT_MET. Use when the owner asks whether a milestone is done.
disable-model-invocation: true
---

You are the architect. The gate is a `class:G` ticket; its body links `docs/GATE.md`.

1. Pin the gate commit: the current tip of `main` unless the owner names one. Every check runs on
   that commit; a later commit is a new gate run.
2. For each check in `docs/GATE.md`, have the verifier run its command on that commit (output to
   `.logs/gate-<n>.log`), or point at the CI run id of that commit for CI-measured numbers.
   Run `java scripts/ratchet.java check` before the spec gate's step 3 `clean`, and run check 9
   on a fresh worktree of the gate commit into `.logs/gate-9.log` (F-41).
   Numbers go to `bench/RESULTS.md` with machine, date, configuration and commit.
3. Write `HANDOVER.md` as `docs/GATE.md` asks, from evidence only, on a branch `gate/<milestone>`.
   The gate run's `bench/RESULTS.md` section goes on that branch too, because a commit that adds
   it is a later commit (F-40).
4. Run the `gate-reviewer` agent in the foreground with the gate commit, the ticket, and the paths
   of the logs, `bench/RESULTS.md` and `HANDOVER.md`.
5. `gate: MET` — PR the branch to `main` (CI green, merge commit), comment the verdict on the
   gate ticket, close it, set `agent/HANDOFF.md` state `GATE_MET`.
   `gate: NOT_MET` — comment the verdict; each failed condition becomes a ticket under the right
   spec (or a new spec through `/to-spec`), the gate ticket blocked by them.
