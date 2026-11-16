# MAP-10 - mutation testing measured on the two engine modules that skip it (class A, a measurement)

Row: `grep -n "^| MAP-10 " agent/BACKLOG.md`. D-164: whether `engine/structure` and `engine/structure-import` join the
nightly mutation run is a floor and goes to the owner with these numbers. This row only measures. No committed file
under `mapping/` changes: no pom, no workflow, no threshold, no ratchet row.

## Read first

- `agent/work/MAP-9-scout.md` :20-30 - how the mapping runs PIT (one call per module, `pit.skip`, the threshold).
- `agent/work/MAP-9-numbers.md` :10-11, :82 - the command MAP-9 used, and why its wall times are not usable: each
  call waited for the shared Maven lock, and `-am` mutated the upstream modules again.

## The verifier's run (one Maven at a time: every command behind `flock /tmp/rail8-maven.lock`)

1. Upstream once, so that no upstream module is mutated in the measured call: in `mapping/`,
   `./mvnw -B -ntp -DskipTests install` to `agent/work/MAP-10-install.log` (it writes only to `~/.m2`). If the
   mapping's build refuses `install` (an enforcer rule, a skip), report the line and use the fallback of step 2.
2. Per module (`engine/structure`, then `engine/structure-import`), in `mapping/`:
   `./mvnw -B -ntp -pl <module> test-compile org.pitest:pitest-maven:mutationCoverage -Dpit.skip=false`
   to `agent/work/MAP-10-pit-<name>.log`. It can outlast a 30-minute command: start it detached with its output in
   the file and read the file's tail. Fallback if step 1 was refused: the same call with `-am`, and then the
   module's own time is read from PIT's own lines for that module only (see 3), never from Maven's total.
3. Read from each log, for the measured module only: PIT's own summary - mutations generated, killed, survived,
   timed out, no coverage, the score it prints (and test strength if printed) - and the time PIT itself prints for
   the run ("Completed in ..."), beside the wall time of the Maven call. The survivors by class and line come from
   the module's `target/pit-reports/**/mutations.xml` (or `.csv`): a count per class, and the ten lines with the
   most survivors.
4. If `-Dpit.skip=false` does not lift the skip (the log says the plugin was skipped): report the pom line that
   binds it and stop - the row then says which line, and nothing is edited.
5. `git status --short mapping/` prints nothing afterwards (build output is ignored; a tracked file changed is a
   fault of the run - report it, do not revert it yourself).

## Hand-back (at most 30 lines) and the file

Write `agent/work/MAP-10-numbers.md` (lines of at most 125 characters): date, commit (`git rev-parse --short HEAD`),
machine (cores, memory), the commands as run, and for each module one row - tests run, mutations generated, killed,
survived, timed out, no coverage, score, PIT's time, the call's wall time - then the survivors per class. Evidence
words: `VERIFIED_PASS` for a figure read from a log you produced, `EXECUTED_FAIL` for a call that failed (with its
last lines), `NOT_RUN`, `ENVIRONMENT_BLOCKED`. Report what was observed; no judgement on whether the modules should
join the nightly run - that is the owner's.

## The architect, after the hand-back

`issues/ISSUE-13.md` names the numbers beside "Not verified by a run"; the question of the nightly run is put to the
owner with them (plain words, the minutes and the score per module, one recommendation); BACKLOG, PROGRESS, HANDOFF.
