# merger memory

Durable facts only (AGENTS.md, Memory).

- The main worktree holds the integration branch checked out: merge in a `--detach` worktree on
  `origin/spec/...` and push `HEAD:spec/...`; never check out or move the local branch (#91).
- A ticket branch that already contains the integration tip merges with an equal tree
  (`git diff --quiet HEAD <ticket>`): its scoped verify stands, no new build (#91).
- Run ui checks without a `cd`: `npm --prefix <worktree>/ui run <script> > <log> 2>&1` (spec #104).
- After merging, run `npm run test` before pushing even when git reports no conflict: a ticket's test
  can count fake-API corpus entries the tip added (spec #104).
