# Agent environment (cloud container)

Read this before your first build or commit in a worktree.

- **The container can restart at any time** and drops every uncommitted change. Commit and push
  your branch after each green step, not only at hand-back.
- **JDK**: Java 25 at `/opt/temurin-25` (`JAVA_HOME`). Build with `./mvnw -B`.
- **Maven plugins offline**: the setup script fetches every plugin a gate or CI step invokes, from the
  jqj checkout, with
  `./mvnw -q -B -Pbench,compile-oracle -Dmaven.test.failure.ignore=true dependency:go-offline verify javadoc:help`
  and then `./mvnw -q -B -Pbench clean` (#54). Run the same two lines when `./mvnw -o` reports a plugin
  that "has not been downloaded". Javadoc offline: `./mvnw -o -q -B -pl jq-core -am compile javadoc:javadoc`
  (`javadoc:javadoc -pl jq-core` alone needs `query-core` installed in the local repository; it is not).
- **Processes**: stop a process by its pid (`kill <pid>`). `pkill -f` matches the agent's own
  command line and ends the session.
- **Property tests**: seeded loops in JUnit, one parameterised case per seed (no jqwik: from 1.10.1 it tells AI agents not to use it).
- **Paths**: run commands from the repo root or with absolute paths. A `cd` into another
  worktree carries into later shell calls.
- **Fetch**: name the branches (`git fetch origin <branch>…`); a bare `git fetch origin` can stall
  here. `git fetch origin <branch>` sometimes creates no remote-tracking ref; then take the
  sha from `git ls-remote origin <branch>`.
- **Commits**: write the message to a file and `git commit -F <file>` with the trailer block of
  `docs/WORKFLOW.md` §11. The block already carries `Signed-off-by`, so `-s` would add a second.
- **Commit-msg hook**: `git config core.hooksPath scripts/hooks` enables `scripts/hooks/commit-msg`.
  It checks every commit message, merge commits included, with the rules of
  `scripts/check-trailers.sh` and refuses the commit with that script's text. The path is relative,
  so each worktree uses its own `scripts/hooks`.
- **Logs**: in a worktree that will be removed, write the hand-back before `git worktree remove`;
  the logs go with the worktree.
- **Reference jq**: the differential oracle runs the real jq binary at `/usr/local/bin/jq-ref`
  (installed by `deploy/claude-cloud/setup.sh`, version pinned there). The `jq` from apt is a
  different version; never use it as the oracle.
