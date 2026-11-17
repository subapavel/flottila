# MAP-12b - the ratchet check runs on GitHub: the lookup, the two wrappers, the week (class C: a gate's wiring)

Row: `grep -n "^| MAP-12b " agent/BACKLOG.md` - it binds where this packet is silent. Decision D-164 (2), (3) with
its bracket of 2026-11-16 ("passed on"). Second of three parts: 12a gave `ratchet.java` the mode
`check --baseline <dir>` (71c3b69e); this part finds the baseline and runs the mode on GitHub; 12c records the week
and removes the switch. Design `agent/work/MAP-12-designer.md` (Q1, Q3, Q4, Q5), opposed by
`agent/work/MAP-12-advisor.md` (required 1, 2, 5, 6; recommended 9 to 12, 14); where they differ from this packet,
the packet holds. Today's scripts, line by line: `agent/work/MAP-12-scout.md` (§4 `pit-summary.sh`, §5 the nightly,
last section: what GitHub's API returned for run 43928160498). Expensive to reverse (script names, output lines,
exit codes, the job's name as a key): `draft — review at hand-over`.

Rules of this area: D-153 as D-164 amends it; `mapping/CLAUDE.md` - read it before writing. No row of `ratchet/`
changes here. No new dependency, action, listener or endpoint: `gh` and `jq` are the runner's (ubuntu-24.04), `git`
the checkout's.

**The date: 2026-11-25.** Until that day (UTC) the two wrappers report and cannot fail their job; from that day
they fail it. It is in this packet, the row and one file, `mapping/scripts/ratchet-enforced-from`.

## Words

- *baseline*: the commit whose `ratchet/` rows the check holds HEAD's rows against - the closest strict ancestor
  of HEAD whose push run of `mapping-ci` on `main` ended with the job `mapping · backend` at `success`.
- *the week*: the days before the date. *Reported*: printed with an annotation, exit 0.
- *verdict line*: the check's last stdout line, `RATCHET CHECK OK` or `RATCHET FAIL: <n> problem(s)`.

## 1. `scripts/ratchet-baseline.sh <dir>` - the lookup (cwd `mapping/`)

Finds the baseline and writes its whole `mapping/ratchet/` tree into `<dir>` (`coverage.tsv`, `mutation.tsv`,
`bench.tsv`, `oracle/`). Reads `GITHUB_REPOSITORY`, `GITHUB_RUN_ID`, `GH_TOKEN` (through `gh`) and
`RATCHET_BASELINE_SHA`.

- `RATCHET_BASELINE_SHA` non-empty (a pull request's base): that commit is the baseline, `gh` is never called;
  prints `BASELINE <sha> (pull request base)`.
- Otherwise: `gh api "repos/$GITHUB_REPOSITORY/actions/workflows/mapping-ci.yml/runs?branch=main&event=push`
  `&per_page=100&page=<n>"` for n = 1, 2, ... until a page holds fewer than 100 runs - the list is read to its end
  and its order is not relied on. A run is a candidate when its `status` is `completed` and its `id` is not
  `GITHUB_RUN_ID`. The script walks HEAD's strict ancestors, children before parents (`git rev-list --topo-order`
  without HEAD itself; a merge's both sides). For the first ancestor that has candidates it asks each one's jobs,
  `gh api "repos/$GITHUB_REPOSITORY/actions/runs/<id>/jobs?per_page=100"`: a job whose `name` is
  `mapping · backend` and whose `conclusion` is `success` makes that ancestor the baseline -
  `BASELINE <sha> run <id>`. If none of its runs has it, the walk goes on to the next ancestor with candidates.
  The run's own conclusion is not read: a run red on another job alone still has a green backend.
- No ancestor has a green backend job (a fork's first run, a renamed job): the baseline is HEAD's first parent,
  `BASELINE <sha> (bootstrap: no push run of mapping-ci on main has a green backend job among HEAD's ancestors)`.
- For the record (12c compares the test's fixtures with these lines), before the `BASELINE` line:
  `LOOKUP pages <n> runs <count>`, and for every run whose jobs were asked
  `LOOKUP run <id> sha <head_sha> status <status> conclusion <conclusion>` and
  `LOOKUP job <run id> name <name> conclusion <conclusion>` (or `LOOKUP job <run id> none named mapping · backend`).
- Failure: `gh` exits non-zero, a response is not the JSON expected, HEAD has no parent, or the baseline commit has
  no `mapping/ratchet/` tree with its four names: one stdout line `LOOKUP FAILED: <reason>`, exit 1, and `<dir>` is
  not to be used. Usage (no `<dir>`): stderr, exit 2. Exit 0 only with a `BASELINE` line and a complete `<dir>`.

## 2. `scripts/ratchet-ci.sh push` - the backend job's last step (cwd `mapping/`)

Runs on a push, a pull request and the week's scheduled runs. In order:

1. The date file `scripts/ratchet-enforced-from` (beside the script): exactly one line, one ISO date
   `YYYY-MM-DD`. Missing, empty, two lines or anything else: `::error::ratchet: the date file <why>`, exit 2, on
   any day, before anything else runs. Today is `date -u +%F`; `RATCHET_TODAY` replaces it (the test's only).
   *Enforced* means today >= the date.
2. The lookup, its stdout passed through. On `LOOKUP FAILED` (or any exit but 0):
   `::error::ratchet: LOOKUP FAILED - <reason>` when enforced, `::warning::ratchet: LOOKUP FAILED - <reason>
   (reported until <date>)` in the week - never worded as a failing check. Then plain
   `java scripts/ratchet.java check` runs instead of the mode: the half that holds the build's reports against
   the commit's own rows needs no baseline, and the run still says whether that half holds.
3. With a baseline: `java scripts/ratchet.java check --baseline <dir>`, its stdout passed through.
4. The check's result is read from its verdict line and its exit code together: `RATCHET CHECK OK` with 0, or
   `RATCHET FAIL: <n> problem(s)` with 1. Anything else - exit 2 (it refused its command line or the baseline's
   shape), exit 1 with no verdict line (an exception), another code - is
   `::error::ratchet: the check wrote no verdict (exit <code>)` and exit 2 on any day: no verdict, so nothing
   to report.
5. Every `RISE ...` line of the check is repeated as `::warning::ratchet: <the line> - regenerate ratchet/`.
6. A failing check: enforced `::error::ratchet failed: <verdict line>`; in the week
   `::warning::ratchet would fail from <date>: <verdict line>`.
7. Exit: 2 as said in 1 and 4; else 1 when enforced and the lookup failed or the check failed; else 0.
8. When `GITHUB_STEP_SUMMARY` is set, the lookup's and the check's lines are appended to it in one fenced block.

Unknown or missing mode: usage on stderr, exit 2.

## 3. `scripts/ratchet-ci.sh nightly` - after the nightly job's PIT step (cwd `mapping/`)

1. The date file, as above.
2. Modules: the rows of `ratchet/mutation.tsv` and every module directory that has
   `target/pit-reports/mutations.xml`, one sorted list without doubles. Then
   `scripts/pit-summary.sh ratchet/mutation.tsv <modules>`, its lines passed through: a row without a report is
   a `DROP`, a report without a row is printed (`floor none ok`), a count above its row is a `DROP`.
3. Each `DROP` line: enforced `::error::mutation ratchet failed: <line>`, in the week
   `::warning::mutation ratchet would fail from <date>: <line>`. `pit-summary.sh` exiting with anything but 0 or
   1: `::error::ratchet: the mutation summary wrote no verdict (exit <code>)`, exit 2.
4. Exit: 2 as said; 1 when enforced and a module dropped; else 0. The step summary as above.

`scripts/pit-summary.sh` gains two lines, each printed after a module's own line and only when it has something
to say, so every line it prints today stays as it is (the old gate's scripts read them, MAP-7's):
- `<module> timed-out <T> of <killed> killed` when the report holds `TIMED_OUT` mutants (they count as killed and
  depend on the machine: the mapping's F-16);
- `<module> generated <G> below its row's <R>` when the row's third column is higher than the report's count (a
  PIT exclusion shows here). Neither line changes the exit code.

## 4. The workflows

`.github/workflows/mapping-ci.yml`:
- the backend job: `permissions:` exactly `contents: read` and `actions: read`; its checkout takes
  `fetch-depth: 0`; a new last step runs `scripts/ratchet-ci.sh push` with `GH_TOKEN: ${{ github.token }}` and
  `RATCHET_BASELINE_SHA: ${{ github.event.pull_request.base.sha }}` on the step (empty on other events). No
  `continue-on-error`, no `secrets.`, no date and no `RATCHET_TODAY` anywhere in the file; the step has no `if:`;
- the three new script tests join the two existing steps (`ratchet-ci-test.sh` and `ratchet-ci-wiring-test.sh`
  beside the tests that need no JDK; nothing new needs one);
- both path lists also name `.github/workflows/mapping-nightly.yml`; the comment at :6 says so;
- the week's runs: `schedule:` with cron `'23 */6 * * *'` (the standstill of `mapping/` would leave the week with
  one run). A scheduled run is never a baseline - the lookup reads push runs. The concurrency group gains the
  event's name (`mapping-ci-${{ github.event_name }}-${{ github.ref }}`), so that a scheduled run and a push run
  never cancel each other; the jobs `windows`, `ui` and `stack` carry `if: github.event_name != 'schedule'`.
  12c takes the schedule and those three lines out again.

`.github/workflows/mapping-nightly.yml`: `env: PIT_MODULES` names the nine modules once and the PIT step reads
it; a step after it runs `scripts/ratchet-ci.sh nightly` with `if: always()`; the comment at :3-4 says what the
job now compares. Its 80 % threshold and its artefact step stay.

## Acceptance

1. Red first, the test-author. Three files under `mapping/scripts/test/`, in the harness of their siblings
   (`check` helper, a scratch tree, `ok`/`FAIL` lines, exit 1 on any FAIL). Each new case is red today for the
   reason it names (today: no such script - assert the line or code that the script would give, not "it failed").
   a. `ratchet-ci-test.sh` (new). A scratch git repository with `mapping/ratchet/` committed over several
      commits and one merge; the three scripts and the date file copied into its `mapping/scripts/`; a stub `gh`
      first on PATH that serves fixture JSON per path and page and logs every call; a stub `java` that prints a
      canned check output and exits with a canned code (the mode's own behaviour is 12a's 47 cases). The fixture
      JSON has the shape and field names of the scout's last section (`workflow_runs[].id`, `head_sha`,
      `head_branch`, `event`, `status`, `conclusion`, `run_number`, `run_attempt`; `jobs[].name`, `status`,
      `conclusion`, `head_sha`, `run_attempt`), with the scratch commits' shas written in at test time.
      The lookup: the nearest green strict ancestor across a merge; HEAD's own green push run is never the
      baseline; a red backend job and a cancelled run are skipped; a run that is red on another job alone is
      taken; a run of a commit that is no ancestor is skipped; a run with `GITHUB_RUN_ID`'s id is never asked for
      its jobs; a green ancestor on page 2 of a list whose page 1 holds 100 runs is found; the list's order does
      not matter; no green ancestor gives the bootstrap line and the first parent's tree; a pull request base is
      taken and the stub's call log stays empty; `<dir>` holds the baseline commit's four names with that
      commit's content (not HEAD's); `gh` failing, a response that is not JSON, and a baseline without
      `mapping/ratchet/` each give `LOOKUP FAILED: ...` and exit 1; the `LOOKUP pages`, `LOOKUP run` and
      `LOOKUP job` lines are printed for the runs asked.
      The push mode, each case on both sides of the date where it differs (`RATCHET_TODAY` the day before, the
      day itself): check OK exits 0 with no annotation; a failing check is a `would fail` warning and 0, then an
      error and 1; a failed lookup is its own annotation, 0 then 1, and plain `check` ran (the stub `java` logs
      its arguments: no `--baseline`); a failed lookup with a failing plain check prints both annotations; the
      check's exit 2, and its exit 1 with no verdict line, exit 2 on both sides; a `RISE` line becomes a warning;
      a missing, empty, two-line and malformed date file each exit 2 on both sides before `gh` or `java` is
      called; an unknown mode exits 2 with the usage; the step summary file receives the lines.
      The nightly mode: no drop exits 0; a count above its row is a warning and 0, then an error and 1; a row
      without a report drops; a report without a row is printed and drops nothing; the module list is the union,
      sorted, each once; `pit-summary.sh` exiting 2 gives exit 2.
   b. `pit-summary-test.sh` gains: the timed-out line for a report with `TIMED_OUT` mutants (counted as killed:
      the survivors and the exit code are those of the same report without the line); no such line for a report
      with none; the generated line when the row's count is higher, none when it is equal or lower. Its existing
      cases stay as they are and stay green.
   c. `ratchet-ci-wiring-test.sh` (new) - a guard of text, and its header says so: it reads the two workflow
      files and the date file and asserts what §4 says of them and the row lists: the ratchet step is the backend
      job's last and comes after the build step; the job's permissions; the token and the base sha on the step;
      no `secrets.`, `continue-on-error`, `RATCHET_TODAY` or ISO date in either workflow; the date file exists
      and holds exactly one ISO date; `fetch-depth: 0`; both path lists name the nightly; the three new tests
      are run by the workflow; the job's `name:` equals the name `ratchet-baseline.sh` looks up; the nightly
      runs `scripts/ratchet-ci.sh nightly` after its PIT step with `if: always()`; `PIT_MODULES` equals
      `MUTATION_MODULES` of `ratchet.java` (:67); the concurrency group carries the event's name and the three
      other jobs do not run on a schedule.
2. The implementer: the two new scripts (mode 100755), `pit-summary.sh`, the date file, the two workflows. All
   three tests green; every script test the two steps of `mapping-ci.yml` run stays green, unchanged.
3. The verifier: every script test of the workflow's two steps once, exit codes and the count of `ok` and `FAIL`
   lines per file; `bash -n` on the three scripts; both workflow files load as YAML (`python3`, PyYAML). Evidence
   `agent/work/MAP-12b-evidence.txt`. Then the first exercise of the duty this row writes into the documents
   (D-164 (2); added 2026-11-16 before any verification ran): the mapping's build
   (`./mvnw -B -ntp -f mapping/pom.xml -Pbench clean verify`) and, in `mapping/`,
   `java scripts/ratchet.java regen --all` - its output whole in the evidence. Either `RATCHET REGEN OK`, and a
   row it moved is part of this commit for the reviewer to read; or it names the report it lacks - then nothing
   is forced, `git status --short mapping/ratchet` stays empty, and the documents say what the verifier has to
   run before it can regenerate.
4. Not done on stubs. After the push, the architect reads the first real `mapping-ci` run by job name: the
   backend job's `BASELINE <sha> run <id>` line (expected: the closest earlier commit of `main` whose push run
   has the backend job green - 71c3b69e's run 43960684995 if it ended green, else 89311471's run 43928160498),
   the ten `MEASURED` lines and the verdict line, written into the evidence file with the run's id. A `LOOKUP
   FAILED`, a missing `gh` or `jq`, or a figure that differs from the local one is this row's to explain before
   it is `done`; a fix is a new round, red first.
5. Documents, the architect, after the implementer: the mapping's ADR-0019 (where the check runs, its baseline,
   the week and its date, the nightly comparison, the verifier's two duties, F-16's sentence that a drop from
   timed-out mutants alone is the architect's to judge) and ADR-0017 (:93, :98-99, :102-103);
   `mapping/docs/WORKFLOW.md` §6 (the CI part, the Ratchet paragraph); `mapping/ratchet/README.md`; root
   `docs/WORKFLOW.md` §1 step 5 (the verifier regenerates the ratchet files at every task commit that touches the
   mapping and runs `pit-summary.sh` for a mutation-tested module it touches; the reviewer reads their diff).

## Read with the tests (approved 2026-11-16)

The three test files are approved as written. Where they read this packet narrower than its words, the tests are
the contract:
- the lookup: `BASELINE ...` is its last stdout line; without a `<dir>` it prints the usage on stderr, nothing
  on stdout, exit 2, and calls nothing; `gh` is called only as `gh api <path>`, the paths exactly those of §1
  (no `--paginate`, no `--jq`: the pages are asked one by one and parsed with `jq`); a page of 100 runs is
  followed by the next page, `total_count` is not trusted; the walk stops at the baseline - the jobs of a
  farther ancestor's run, of HEAD's own run, of a run that is not completed, of a stray commit's run and of the
  run with `GITHUB_RUN_ID`'s id are never asked; a baseline or a base whose `mapping/ratchet/` lacks one of the
  four names, and a HEAD without a parent when the bootstrap needs one, are `LOOKUP FAILED: <reason>`, exit 1;
- `ratchet-ci.sh`: annotations go to stdout; the lookup's and the check's stdout lines are passed through; the
  step summary gets one fenced block appended; an empty module list in the nightly mode is "no verdict", exit 2
  (the summary script's usage exit); the date file is read before anything is called, in both modes;
- §4's "the three new script tests": two files are new, the third is `pit-summary-test.sh`, which the step
  already names - each of the three is named once in the step that needs no JDK;
- §4's "no date anywhere in the file" stands as written, comments included: the header comment of
  `mapping-ci.yml` (:5, "owner ruling 2026-11-02, #48") is reworded to name the ruling by its number alone;
- the wiring test also asks the header comment of `mapping-ci.yml` to name `mapping-nightly.yml` and the
  nightly's to name `mutation.tsv`.
Not pinned by a test, the implementer's to keep: the step summary is skipped when `GITHUB_STEP_SUMMARY` is
unset; the nightly's 80 % threshold stays where it is. From the test-author's reference: `git archive` exports
`mapping/ratchet` only when run from the repository's top level.

### Added on the implementer's hand-back (2026-11-16): what the scripts guard beyond §1-§3, each with its case

The scripts as first written carried guards no case reached, and one way to switch the check off by accident.
Each sentence below is contract and gets its case in `ratchet-ci-test.sh` (second round, the test-author):
1. `RATCHET_TODAY`, when set and not empty, is one real ISO date; anything else is
   `::error::ratchet: RATCHET_TODAY is not an ISO date`, exit 2, before `gh` or `java` is called, in both modes
   (as first written a value that is no number compared as "before the date": the check reported and passed).
2. `gh` exiting non-zero is `LOOKUP FAILED` also when its stdout is a well-formed answer - on the runs call and on
   the jobs call.
3. A shallow checkout is `LOOKUP FAILED` (a cut history would name a wrong baseline or the bootstrap, silently).
4. `RATCHET_BASELINE_SHA` that is not a full lowercase hex sha (a branch name, an option, a short sha) is
   `LOOKUP FAILED`; `gh` is not called and `<dir>` holds nothing.
5. What the API answers is data: a run whose `id` is no number, whose `head_sha` is not 40 hex digits or whose
   `status` is not a word, and a backend job whose `conclusion` is not a word, are `LOOKUP FAILED`; nothing of
   such a value reaches `git` or a second `gh` call.
6. `GITHUB_REPOSITORY` that is not `<owner>/<repository>` is `LOOKUP FAILED` before any `gh` call.
7. A lookup that ends non-zero without a `LOOKUP FAILED` line (it crashed, it is missing) is a failed lookup to
   `ratchet-ci.sh`: its own annotation with a non-empty reason, plain `check` runs, exit 0 before the date, 1 from
   it.
8. The date file: a date that is no calendar day (`2026-02-30`) and a date without its final newline are the
   date-file error, exit 2, before any call.
9. Any green run of an ancestor makes it the baseline, whichever of its runs is newer; the run named on the
   `BASELINE` line is the newest green one.
10. The runs list is read for at most 100 pages; a list that has not ended by then is `LOOKUP FAILED`, and page
    101 is never asked. (GitHub answers at most 1 000 runs to a query filtered by branch and event, ten pages:
    the cap ends a list that never ends, it is not a limit the repository grows into.)
11. A run listed on two pages (the list moved between the two requests) is asked for its jobs once.
12. `ratchet-ci.sh` does the same from any working directory (it changes to the mapping's root itself).
A case that is green on arrival shows its red against a hand mutant of a scratch copy of the script, as 12a's
and FIX-280's did; the real scripts are not edited by the test-author.
Round 2 as it went (2026-11-16): 75 assertions added (249 in the file); against the first scripts only
sentence 1's 15 failed; every other case killed its mutant (`agent/work/MAP-12b-red2.log`); the implementer then
changed `ratchet-ci.sh` alone (249 green, `agent/work/MAP-12b-green.log`, "round 2"). Guards left without a case,
each because no input reaches it or it only words a reason: the run's own `conclusion` being a word (it is only
printed), the clock's date being an ISO date, the comparison's exit-2 branch (both dates are checked before it),
the reasons of `git archive` failing, of a HEAD without a parent and of a base that is no commit (a later check
gives `LOOKUP FAILED` all the same), `PIT_ROOT`, the date file's "is missing" wording.

### Round 3: the review's FAIL of 2026-11-16 (round 1), each with its case - red first again

The reviewer [C] found two guards that no case holds, one false sentence of the contract and two false sentences
of the documents. The contract gains 13 to 18; 5 is restated by 14.
13. An answer that `gh` returns with exit 0 and that is not exactly one JSON value of the shape expected is a
    failed lookup, never "no runs" and never the bootstrap: for a runs page an empty answer, two JSON values, an
    object without `workflow_runs` (`{"total_count": 0}`), `workflow_runs` that is no array -
    `LOOKUP FAILED: runs page <n> is not the JSON expected`; for a run's jobs an empty answer and two JSON values -
    `LOOKUP FAILED: the jobs of run <id> are not the JSON expected`. (`ratchet-baseline.sh`:39, :42, :48-52: the
    mutants `error("no workflow_runs")` to `empty` and `one` to `[inputs] | .[]` keep all 249 assertions green.)
14. A job's conclusion is judged as the one JSON string it is, before any line is split: a conclusion that is not
    null and not exactly `^[a-z_]+$` - `"success "`, `" success"`, `"failure\nsuccess"`, `"success\tx"`, `""` - is
    `LOOKUP FAILED: the jobs of run <id> hold a conclusion that is not the JSON expected`. It is never trimmed into
    `success` and never split into two conclusions (:51 with :135-136 does both today: red on the scripts as
    they are). The same holds for a run's `status` and `conclusion` on a runs page (they pass through `@tsv` and a
    tab-split `read`: show which of these values are refused today and which are not).
15. The nightly mode without `ratchet/mutation.tsv` (missing, or not a readable file):
    `::error::ratchet: ratchet/mutation.tsv is missing`, exit 2 on any day, before `pit-summary.sh` is called.
    (Today every module prints "floor none ok" and the step exits 0.)
16. The lookup's own tools: where it is about to call the API (not on the pull-request path, which calls
    neither), `gh` or `jq` not on PATH is `LOOKUP FAILED: gh is not installed` / `LOOKUP FAILED: jq is not
    installed` and no call is made. (Today a missing `jq` reads "runs page 1 is not the JSON expected".)
17. `ratchet-ci.sh` cannot make its temporary directory (`TMPDIR` names a directory that does not exist):
    `::error::ratchet: cannot create a temporary directory`, exit 2 on any day, before `gh` or `java` is called.
    Its failing `cd` gets `::error::ratchet: cannot change to the mapping's root` (no case: nothing reaches it).
    The header's exit-2 list names both, and says that GNU `date` is assumed and not checked.
18. The token is never printed: with `GH_TOKEN` set to a value the test chooses, that value appears in neither
    stdout nor stderr of a push-mode run with a lookup that succeeds, nor of one whose `gh` fails.
The wiring test keeps `date file: the packet's date` (:146): the date is the gate's off-switch, and a commit that
moves it then also edits a test - the ADR says so instead of calling the file the only place.
Steps: the test-author [C] adds the cases (allowed path: `mapping/scripts/test/ratchet-ci-test.sh`; red to
`agent/work/MAP-12b-red3.log`, a green-on-arrival case against its hand mutant on a scratch copy; the log ends
with the sha256 of the three test files). The architect reads them. The implementer [C]: `ratchet-baseline.sh`,
`ratchet-ci.sh`, and the comment of `mapping-ci.yml`:116-117 ("it reports and cannot fail the job" is false: the
date file, `RATCHET_TODAY`, a check without a verdict fail it on any day - "a failing check or a failed lookup
cannot fail it"); its log repeats the three sha256. The architect: ADR-0019 (:123-125), `mapping/docs/WORKFLOW.md`
§6 (:85: the two new tests need no JDK), root `docs/WORKFLOW.md` §1 step 5 (the build is `-Pbench clean verify`,
as ADR-0019 says). The verifier: the script tests again. The reviewer [C], round 2.
Round 3's tests, read and approved 2026-11-17: 77 assertions added (326 in the file); on the scripts as they
were 29 failed - 14's jobs cases (8: trimmed, split, an empty conclusion read as "no job named"), 15's twelve, 16's
three, 17's line in both modes (6) - and every case green on arrival killed its hand mutant, among them the three
mutants of the review's first finding and the guard on a run's conclusion (:101), unpinned since round 2
(`agent/work/MAP-12b-red3.log`, which ends with the three test files' sha256). Not tested, each for a reason: "no
call" with `gh` absent (nothing on that PATH counts calls), an unreadable file (the test runs as root: a directory
stands in), the failing `cd`.
Round 3's scripts (2026-11-17): 326 green (`agent/work/MAP-12b-green.log`, "round 3"); the implementer named one
more value that 14 must hold and no case had: `"success\n"` - jq's `$` also matches before a final newline, so
a check written `^[a-z_]+$` takes it for `success`; the script uses `\A[a-z_]+\z`. 14's list gains that value
(test-author, green on arrival, red against the `^..$` mutant on a scratch copy). Guards without a case: the
`-r` term of 15's check (the test runs as root), the failing `cd`, `word` on a runs page (the tab-split and
`is_word` refuse every such value already; only which check refuses an empty status changes).
Review [C], round 2 (2026-11-17): PASS. Taken from its notes: ADR-0019's "What cannot be decided" names its
cases "among them" and points at the script's header for the whole list; `word` stays on the runs page (under a
UTF-8 collation bash's `[a-z]` may take other letters). Regen ran at 6b23ddc1 (round 1); round 3 changed no
input of it. Open after the push: acceptance 4.

## Not this part's

`ratchet.java` (12a; a fault found in it is a finding and a row). The week's record, the fixtures compared with
a real run's lines, the end of the date and of the schedule, the checkout's time (12c). The editor's rows on
GitHub (MAP-15). Any floor (MAP-11). The two modules without mutation testing (MAP-16, the owner's). A way for a
row to fall or leave on purpose (the owner's, with MAP-1). The old gate's scripts and rows 1b and 5 of §6
(MAP-7). Required checks and branch protection (the owner's). A probe pull request (only when the owner asks).

## Steps

1. Test-author [C]: acceptance 1. Allowed paths: the three test files. Red shown per file to
   `agent/work/MAP-12b-red.log` - for the two new files every case red; for `pit-summary-test.sh` the new cases
   red and the old ones green. Hand back at most 40 lines, with the count of cases per file.
2. The architect reads the three files and approves them.
3. Implementer [C]: acceptance 2. Allowed paths: `mapping/scripts/ratchet-baseline.sh`, `ratchet-ci.sh`,
   `pit-summary.sh`, `ratchet-enforced-from`, the two workflow files. Evidence `agent/work/MAP-12b-green.log`. A
   test that cannot be made green as written is handed back with the reason, never edited.
4. The architect: acceptance 5. The verifier: acceptance 3. The reviewer [C] on `agent/work/MAP-12b.diff` and the
   evidence. After the push: acceptance 4, then the row is `done`; HANDOFF carries the date until 12c.
