# merger memory

Durable facts only (AGENTS.md, Memory). No ticket state. Under 120 lines.

## Carried over from r8-mapping (2026-11-10; observed there, re-verify on first use here)

- The main worktree holds the integration branch checked out: merge in a `--detach` worktree on
  `origin/spec/...` and push `HEAD:spec/...`; never check out or move the local branch.
- A ticket branch that already contains the integration tip merges with an equal tree
  (`git diff --quiet HEAD <ticket>`): its scoped verify stands, no new build.
- (#26) The first `regen --oracles` for a new oracle creates an empty untracked pass list; commit it.
- (#33) A fresh merge worktree has no oracle reports: run `./mvnw -B verify` before `regen --oracles`.
- (#37) `java scripts/ratchet.java` takes the current directory as the repo root: run regen and check from inside the merge worktree. A worktree with `.logs/` needs `git worktree remove --force`.
- (#36) git config's identity is Claude: a merge commit's Signed-off-by must come from GIT_AUTHOR_NAME/GIT_AUTHOR_EMAIL; check it before pushing.
- (#36) A pass list can grow beyond the verifier's DifferentialOracleTest count: regen adds survey passes (e.g. new `gen/values-v1` seeds) that were neither ratcheted nor required.
- (#35) A bare `cd` does not persist between calls: use `mvnw -f <worktree>/pom.xml` and `env -C <worktree> java scripts/ratchet.java …`.
- (#39) The default reactor leaves out `bench/jmh`; a bench ticket's tests run only under `-Pbench` (the gate). `regen --all` (bench.tsv) needs every report and belongs to the spec gate, not the merger.
- (#60) Without the Write tool, commit with `git commit -m <subject> -m $'Ticket: …\nCo-authored-by: …\nSigned-off-by: …'`.
- (#60) Run `scripts/check-trailers.sh <range>..HEAD` from inside the merge worktree (`bash -c 'cd <wt> && …'`), else HEAD is the main checkout's.
