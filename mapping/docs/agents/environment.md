# Agent environment (cloud container)

Read this before your first build or commit in a worktree.

- **The container can restart at any time** and drops every uncommitted change. Commit and push
  your branch after each green step, not only at hand-back.
- **JDK**: Java 25 at `/opt/temurin-25` (`JAVA_HOME`). Build with `./mvnw -B`.
- **Processes**: stop a process by its pid (`kill <pid>`). `pkill -f` matches the agent's own
  command line and ends the session.
- **Browser**: Chromium and Playwright are pre-installed and pinned; `npm run e2e*` finds them.
  A `playwright install` pulls a second browser the pinned version cannot find.
- **Property tests**: seeded loops in JUnit (jqwik is rejected, ADR-0018).
- **Paths**: run commands from the repo root or with absolute paths. A `cd` into another
  worktree carries into later shell calls.
- **Fetch**: name the branches (`git fetch origin <branch>…`); a bare `git fetch origin` can stall
  here. `git fetch origin <branch>` sometimes creates no remote-tracking ref; then take the
  sha from `git ls-remote origin <branch>`.
- **Commits**: write the message to a file and `git commit -F <file>` with the trailer block of
  `docs/WORKFLOW.md` §11. The block already carries `Signed-off-by`, so `-s` would add a second.
- **Logs**: in a worktree that will be removed, write the hand-back before `git worktree remove`;
  the logs go with the worktree.
