# MAP-12 - the designer's draft (2026-11-16; read-only pass, opus xhigh; written down by the architect from the hand-back)

GitHub behaviours the designer could not check from the repository's files are marked "unverified".

## 1. Proposal

- Q1 Baseline: the closest strict ancestor of HEAD whose push run on `main` finished with job `mapping · backend` at
  `success`. The ratchet step is that job's last step, so a green job means the check passed. From the switch date a
  red push's rows never become a baseline. During the week the job cannot fail, so the rule is "ran on". This needs a
  bracket on D-164 (2): "ran on [MAP-12: passed on, on `main`; ran on during the reporting week]". Why not
  `github.event.before` (`ci.yml`:94-100): it is the previous tip, not a checked commit. `mapping-ci.yml`:28-30
  cancels runs and :18-26 skips pushes outside the paths. It also makes a red last one push only: the next push takes
  the lowered rows as baseline, and nothing else stops them because mapping-ci is not required (D-164 (6)).
- Q1 Mechanism: a new `scripts/ratchet-baseline.sh <dir>`.
  - It calls `gh api repos/$GITHUB_REPOSITORY/actions/workflows/mapping-ci.yml/runs?branch=main&event=push&per_page=100`
    to get each run's id and head_sha.
  - It walks `git rev-list --topo-order HEAD^`; merge commits are included and children come before parents.
  - The first sha with a run whose `actions/runs/<id>/jobs` lists `mapping · backend` as `success` is the baseline.
    `git show <sha>:mapping/ratchet/<f>` writes the four files into <dir> and the script prints
    `BASELINE <sha> run <id>`.
  - Workflow: last step of backend, after :94-95, runs `scripts/ratchet-ci.sh`. Job-level permissions
    `contents: read` + `actions: read`; `GH_TOKEN: ${{ github.token }}` (the run's own token, not a repository
    secret); checkout `fetch-depth: 0`. The concurrency group :28-30 stays: a cancelled run concludes `cancelled` and
    is never a baseline.
  - First run: earlier backend-green runs from before MAP-12 are valid baselines. No earlier push run at all (a new
    fork) gives `BASELINE <HEAD^> (bootstrap)`.
  - Failures: runs exist but none of the 100 passed -> exit 1. A gh or HTTP error, or an unreadable file -> exit 1
    with `LOOKUP FAILED`. A failed lookup never passes silently.
  - Pull requests, forks included: `RATCHET_BASELINE_SHA=${{ github.event.pull_request.base.sha }}`, no API call.
  - Manual: a re-run never sees HEAD or a descendant as a candidate. mapping-ci has no `workflow_dispatch` (:9-26),
    and `event=push` keeps any future dispatch run out of the candidates.
- Q2 New mode: `java scripts/ratchet.java check --baseline <dir>` is today's check (:107-205) plus three things.
  - (a) Committed rows against <dir>: every case of `<dir>/oracle/*.txt` is still listed; every coverage row is
    present and not lower (the MOVED map at :58-59 applies to <dir>'s keys); every mutation row is present with no
    more survivors (old 2-column rows are compared as a percent).
  - (b) Missing required reports FAIL. Required: the oracle report when `engine/oracle` has tests, and a `jacoco.csv`
    for every Java row of the commit's `coverage.tsv` and every module with production code. That last part is the
    tree rule of :415-427 and :435-443, so `udf-api` and `udf` are exempt until they have code and a new module is
    required from its first code.
  - (c) It prints `MEASURED coverage <m> lines c/t branches c/t` for the week's record.
  - Mutation and UI rows stay NOT_RUN (a push runs no PIT; backend has no UI report). `bench.tsv` stays ignored
    (README:27).
  - Exit codes: 0 ok, 1 `RATCHET FAIL`, 2 usage error or a <dir> missing one of the four files. Without the exit-2
    check, `readLines` (:774-777) would read a missing file as empty and pass.
  - Plain `check` stays unchanged for the verifier's local run.
- Q3 Reporting week: `RATCHET_ENFORCED_FROM: 'YYYY-MM-DD'` appears once, in the backend job's env, and in the row.
  - `scripts/ratchet-ci.sh` runs the lookup and the check and appends both outputs to `$GITHUB_STEP_SUMMARY`.
  - A non-zero result before the date (UTC; `RATCHET_TODAY` overrides it in the test) prints `::warning::would fail`
    and exits 0. From the date it exits 1. A missing or malformed date exits 2 always.
  - Why not `continue-on-error` flipped by a later commit: enforcing must be the default. A forgotten flip leaves
    things as they are today; a date switches by itself, and postponing it takes a commit with a reason.
  - Before the date, the record must show: a baseline found on every run; no `would fail`; GitHub's counts equal to
    the local ones in `MAP-9-numbers.md`, including service branches 103/107; nightly survivors equal to
    `mutation.tsv`.
  - A figure that differs by platform gets no tolerance (ADR-0019:24-25). It gets a finding plus a FIX that makes the
    branch deterministic (fake clock). The date moves by a commit until the FIX lands. A row is never lowered by hand
    (`mapping/CLAUDE.md`:24-25).
- Q4 Nightly:
  - `env: PIT_MODULES` names the nine modules once (today they are inline at :51) and feeds the PIT step.
  - A step after PIT runs `scripts/pit-summary.sh ratchet/mutation.tsv $(cut -f1 ratchet/mutation.tsv)`. These are
    the committed rows, so `udf-api` and `udf`, which have no report (:42-45), are never passed, and a listed row
    with no report is a DROP.
  - "New survivor" is counted per module (:64-66): a fall in `mapping` never offsets a rise in `runtime`. A fall is
    only reported; the verifier's next regen locks it in. It compares counts, not which mutant survives (D-164 (3)).
  - It fails from day 1, because it fails no push.
  - `.github/workflows/mapping-nightly.yml` joins both path lists (:11-17, :20-26). The comment at :3-4 is rewritten.
- Q5 Wiring test: `scripts/test/ratchet-ci-wiring-test.sh` needs no JDK and joins the step at :80-89. It asserts:
  - backend's last step runs `scripts/ratchet-ci.sh`, after `../mvnw -B -Pbench verify`;
  - backend's permissions are exactly contents read + actions read;
  - `GH_TOKEN: ${{ github.token }}` is set, and the file has no `secrets.` and no `continue-on-error`;
  - `RATCHET_ENFORCED_FROM` appears once as an ISO date; checkout has `fetch-depth: 0`;
  - both path lists name the nightly; the new script tests are run;
  - the job's name equals the name `ratchet-baseline.sh` looks up;
  - in the nightly, the PIT step and the summary step use `PIT_MODULES`, whose set equals `MUTATION_MODULES`
    (`ratchet.java`:60-70).
- Q5 `floors-tier-test.sh` is nobody's. MAP-11 shows the floor live, and JaCoCo `check` in `verify`
  (`mapping/pom.xml`:358-374) already fails below a floor; a grep over poms would test text, not behaviour.
- Q6 Documents:
  - ADR-0019 (Status line plus amendment): :6-7 and :49-56 (where the check runs, its baseline, strict reports; the
    "integration branch" sentence goes); :57-60 (the verifier regenerates at every task commit that touches
    `mapping/`, after the full `verify` and `npm run test`; the reviewer reads the diff); :62-63 (becomes "a push may
    only keep or raise them"); :81-84 and :90-91 (the nightly comparison; the verifier's `pit-summary.sh` before a
    commit that touches a mutation-tested module); :111-113 (reversed by D-164 (2)); a considered option for
    `event.before` and for "ran on".
  - ADR-0017: :93 and :98-99.
  - `mapping/docs/WORKFLOW.md` §6: amend here the CI part (:77-96) and the Ratchet paragraph (:98-101), which would
    otherwise be false after this row. Rows 1b and 5 (:22, :28) are MAP-7's, because `gate-ticket.sh` still runs them.
  - `mapping/CLAUDE.md`: nothing; :19 and :24-25 stay true.
- Q7 Split into three rows, in order MAP-11 -> 12a -> 12b -> 12c:
  - MAP-12a: `check --baseline` and its script test, red on today's script, with the existing `ratchet-*-test.sh`
    still green.
  - MAP-12b: both shell scripts and their test, both workflows, the wiring test and the documents; the date is the
    push day + 7.
  - MAP-12c [B]: the week's record in `agent/work/MAP-12-week.md`, before the date; the date is kept or moved by a
    commit.

## 2. Tests (no property tests and no contract-suite cases: no batcher, admission, WorkQueue or component is involved)

- `ratchet-baseline-test.sh`, each case red on today's script (exit 2, usage):
  - `lowered_row_with_code`: plain `check` gives 0, `--baseline` gives 1;
  - `removed_corpus_case`, `removed_coverage_row`, `raised_committed_survivors`: 1 each;
  - `missing_jacoco_of_module_with_code` gives 1; `udf_api_module_info_only` is not a failure;
    `oracle_tests_without_reports` gives 1;
  - `baseline_dir_or_file_missing` gives 2; `no_pit_no_ui_report` gives NOT_RUN and 0.
- `ratchet-ci-test.sh` (a stub `gh` on PATH; a scratch git repo with one merge commit):
  `nearest_passing_ancestor_across_merge`, `failed_and_cancelled_runs_skipped`, `non_ancestor_run_skipped`,
  `bootstrap_head_parent`, `none_passing_exit_1`, `gh_error_lookup_failed`, `warning_before_date_exit_0`,
  `fails_on_date`, `date_unset_exit_2`, `pull_request_no_api_call`.
- `ratchet-ci-wiring-test.sh`: the assertions in Q5, red on today's files.
- `pit-summary-test.sh` gains `fall_does_not_offset_rise`. It already passes on today's script and is marked as a
  characterization test.

## 3. Rejected

- "Ran on" through the same API: same cost, but a red is absorbed by the next push.
- No-cancel plus `event.before`: pending runs are still replaced (unverified), and it gives "pushed before", not
  "ran on".
- A ref or tag pushed by the job: `contents: write` for a check, races between runs, and anyone who pushes can move it.
- `actions/cache`: a new action, and it can be evicted, which would silently bootstrap. An artifact of the last green
  run still needs the lookup and expires.
- The run-level `status=success` filter: a run red on `windows` alone is skipped, giving an older, looser baseline.
- Overlaying the baseline's `ratchet/` before plain `check` (advisor W1): a row removed at HEAD goes unnoticed and is
  exempt from then on (NEW, :150-155).
- A workflow grep for `^NOT_RUN` (W2): the required list would live in YAML, not in the tree.
- The same date on the nightly; a committed list of surviving mutants (a new persisted format); one row for
  everything.

## 4. Expensive to reverse

`check --baseline` and its exit codes; "passed on" and the D-164 (2) bracket, which decide which reds stick; the job
name `mapping · backend` and the rule that the ratchet step comes last, both pinned by the wiring test;
`actions: read`; the name `RATCHET_ENFORCED_FROM`; the ADR-0019/0017 amendments. No `ratchet/` format changes.

## 5. Conflicts

- D-164 (2) says "ran on"; this draft says "passed on". The architect writes the bracket.
- ADR-0019:54-56, :62-63 and :111-113 are amended here, as D-164 (5) allows.
- ADR-0017:102-103 (branch protection requires the four jobs) contradicts D-164 (6) and `mapping-ci.yml`:4-5. :86-87
  (`ci.yml`, `t/**`) and :98 (`nightly.yml`) have been stale since BRAND-3. Recommended: fix only :98 here.
- The row names `scripts/ratchet-ci-wiring-test.sh`, but every script test lives in `scripts/test/`
  (`mapping-ci.yml`:72-89).
- D-164 (5) names only ADR-0019 and ADR-0017 as files a session may amend, yet Q6 amends `mapping/docs/WORKFLOW.md`
  §6.
- D-164 (2) says "no row removed", while ADR-0019:35 and README:27 say check ignores `bench.tsv`. This draft keeps
  `bench.tsv` ignored.

## 6. Open

- The date: MAP-12b's push day + 7 (UTC)?
- A deliberate red on GitHub: a probe PR from a throwaway branch, or do the script tests stand in ("direct to main")?
- Deliberate removals (the service and UI rows under D-158): until a retired-rows map sits beside MOVED (:58-59),
  they stay red. Is that map built by the row that removes them?
- Where does the verifier read its two duties: in packets, or in `.claude/agents/verifier.md` (MAP-13)?
- The UI rows stay NOT_RUN in CI (advisor A3). Should they get a row of their own?
- Unverified, for the implementer to check:
  - `gh` is on ubuntu-24.04 runners (otherwise curl + python3);
  - `actions/checkout@v7` accepts `fetch-depth`;
  - the runs list takes `branch`/`event`/`per_page`, comes newest first and carries `head_sha`;
  - the jobs endpoint returns the latest attempt and the display name;
  - `actions: read` is enough;
  - `$GITHUB_STEP_SUMMARY` works;
  - PIT counts a TIMED_OUT mutant as `detected="true"`, which decides survivor counts on a slower runner.
