# MAP-12 - the advisor's opposition to the designer's draft (2026-11-16; fable, high; written down by the architect)

Verdict: SOUND WITH CHANGES - "passed on" through the API is the right baseline and the draft's rejections of W1 and
W2 hold, but six points fail as drafted.

## Required before a packet is written

1. Nightly "fails from day 1" (draft §1 Q4; §3 rejects "the same date on the nightly" with no reason) ignores
   `mapping/docs/findings/F-16.md`:5-13: a `TIMED_OUT` mutant counts as killed (`ratchet.java`:394,
   `pit-summary.sh`:49) and whether it times out depends on load. Today 18 are timed out
   (`agent/work/MAP-9-numbers.md`:43: xdm 11, runtime 5, mapping 1, api 1). One of xdm's 11 finishing on GitHub's
   runner gives `engine/xdm survivors 1 floor 0 DROP` with no code change; the reverse hides a survivor. §6 lists
   this as "unverified" although the repo holds the finding. Change: the nightly step reports under the same date,
   prints the TIMED_OUT count per module, and the ADR amendment carries F-16:19-20 (a drop from timed-out mutants
   alone is the architect's to judge).
2. The lookup can deadlock (§1 "runs exist but none of the 100 passed -> exit 1"; bootstrap only on "no earlier push
   run at all"). After the date, a fork whose first run ends red or cancelled, or a renamed job (every older run
   carries the old name), exits 1 on every later run, and no green run can ever appear because the step itself fails
   the job. If the list includes the running run (unverified), bootstrap never fires at all. Change: exclude
   `GITHUB_RUN_ID`, page to the end of the list, bootstrap when no green ancestor run exists at all; add cases
   `own_run_excluded` and `first_run_red_bootstraps_again`.
3. A decrease made on purpose has no way through, and the date stays as an off-switch. D-158 removes
   `service 98 96` (`mapping/ratchet/coverage.tsv`:10) and the UI rows; deleting 4 covered lines of
   `engine/structure` gives 106/114 = 92 < 93 (ADR-0019:114 names this effect). Check (a) then fails on every later
   push, and the only switch in reach is `RATCHET_ENFORCED_FROM`: moved "with a reason", its report-only green runs
   become baselines (§1 "ran on during the week") and the red is laundered. Change: 12c's commit removes the date
   switch, with the wiring test asserting its absence; the ADR amendment fixes one waiver now (a committed file of
   row, old, new, finding id, honoured by (a) for that exact change) instead of a second "retired" map beside MOVED.
   The wiring test also asserts no `RATCHET_TODAY` in a workflow and `RATCHET_BASELINE_SHA` only under
   `pull_request`.
4. The required-report rule (§1 Q2 b) fails a report that exists. `ratchet.java`:312-313 drops a header-only
   `jacoco.csv` from the map and :424 tests the map; a module whose first code is interfaces only (`engine/udf-api`,
   MAP-11) has production code by :441 and gets "missing report" (that JaCoCo omits such classes is unverified;
   :313's comment expects it). Change: required means the file exists (:366); add case `code_with_header_only_csv`.
5. Red-first does not hold as claimed (§2). `baseline_dir_or_file_missing gives 2` is green today, because
   `ratchet.java`:76-88 exits 2 for any unknown arguments; it must assert the message. The stub `gh` encodes exactly
   the JSON shape §6 calls unverified, so the tests can be green and the lookup wrong. Change: the fixtures are
   trimmed recorded responses of this repository, and the runs and jobs endpoint facts are checked against GitHub's
   documentation before the packet, not by the implementer.
6. The week can pass unobserved (§1 Q3, Q7). The workflow is path-filtered (`mapping-ci.yml`:18-26) and `mapping/`
   is at a standstill (D-153), so seven days may hold one run; one sample says nothing about the timing-dependent
   branch (103/107). Change: 12c's acceptance names a minimum of backend runs and their source (re-runs of one
   commit, or a `schedule:` trigger for the week, which `event=push` already keeps out of the candidates). 12b is
   not done on stubs: its evidence includes the first real run's `BASELINE <sha> run <id>` and MEASURED lines. The
   architect writes a calendar date into the packet and the row; "push day + 7" is not a date.

## Recommended

7. `bench.tsv`: (a) checks row presence only (scenario and machine, no scores). README:27 says "a result with no row
   is a new row", so a deleted row silently loses its baseline; about five lines, and D-164 (2) "no row removed"
   then needs no second bracket.
8. The baseline script exports the whole `mapping/ratchet/` tree, not four names (README:15 and ADR-0019:19 foresee
   `oracle/w3c-*.txt`). Drop the two-column compare in (a): `mutation.tsv`:1-7 is all three-column, so such a
   baseline row can exit 2 (older ancestors unchecked).
9. Print `::warning` when a measured figure is above its committed row; `ci.yml`:107-109 does this for the platform.
   It is the mechanical form of the verifier's regeneration duty (W3); enforce it once the week shows GitHub equals
   local.
10. In the week, `ratchet-ci.sh` gives distinct annotations for exit 1, exit 2 and `LOOKUP FAILED`; "non-zero ->
    would fail, exit 0" swallows a failed lookup against the draft's own "never passes silently". Set `GH_TOKEN` on
    the step, not the job.
11. Documents without an owner: `mapping/ratchet/README.md`:3-5, :27, :53-55; the comment at `mapping-ci.yml`:6;
    root `docs/WORKFLOW.md`:23-26. 12a adds its own test line at `mapping-ci.yml`:74-76, or the test runs nowhere
    until 12b.
12. Nightly: pass the rows plus every module that has a report, so a report without a row is printed; `if: always()`
    on the summary; print a fall of `generated`.
13. Ways the check passes while a figure falls, to be said in the ADR: UI coverage between the 90 floor and the 100
    row (no report in backend); a PIT exclusion that removes the classes holding survivors; a gain committed in a
    cancelled or infra-red push; whole-percent rounding.
14. Cost: `fetch-depth: 0` is unmeasured for this history; record the checkout time in 12c (`ci.yml`:26-28 already
    uses it on checkout@v4).

## Answers

- Q1 baseline: keep it; it is the simplest design that holds the property. `github.event.before` with no
  cancellation holds exactly this weaker one: every decrease gives one red run and the next push passes without
  restoring it. A git-only high-water mark per row holds the same stickiness without the API, but a wrong row
  committed once binds forever; not recommended. Merges, re-runs, pushes outside the paths and fork PRs do not break
  the draft; API down is a red `LOOKUP FAILED` and a re-run, acceptable.
- Q2 "passed on": a faithful build - the architect's bracket, marked and told to the owner at hand-over, not an
  issue. ISSUE-13:16-17 decides it ("a line never leaves a list and a figure never gets worse"), with :34-37: the
  old other side was `main`'s ratchet, written only by a regen that refuses a drop (ADR-0019:57-63). :147 ("before
  anything tightens") does not apply: no floor, figure or module is added.
- Q3 mode: exit codes 0/1/2 fit :88 and :93; plain `check` unchanged is right. Required reports as item 4;
  `bench.tsv` as item 7; removals as item 3.
- Q4 date against flag: the date - it fails toward enforcing, a forgotten flag fails open. The record lists every
  run (id, sha, BASELINE line, verdict, MEASURED c/t), not the last, since a `would fail` shows on one run only. The
  architect reads it in 12c; HANDOFF carries the date until the switch.
- Q5 counts: yes, a new survivor can hide behind a newly killed one in the same module. D-164 (3) accepts it
  ("against the committed count", "comparison with the committed counts"; ADR-0019:87-91). The amendment should say
  "no rise of a module's count". Runner speed: item 1.
- Q6 split: sound with items 5, 6 and 11. 12c has a red-first test only if it removes the date (item 3). The wiring
  test is a test of text: keep it as the guard against a removed step (W8) and call it that; the behaviour evidence
  is the first real run.
- Q7, §5: amend `mapping/docs/WORKFLOW.md` §6 here - it is not a decision record (ISSUE-13:120-124 restricts those;
  the file's :9 says "Decided by ADR-0017") and the row offers it. Fix ADR-0017:98 and :102-103 (D-164 (6)
  contradicts the latter). The tests live in `scripts/test/`; correct the row.
- Q7, §6: deliberate red - a probe PR closed unmerged, in 12c; it proves the check on GitHub's machine but not the
  lookup, which the first push run proves. Verifier's duties: root `docs/WORKFLOW.md`:23-26 (says only `verify`
  today), §6, ADR-0019 and each MAP packet; `.claude/agents/verifier.md` has no word on mapping or ratchet, so its
  patch joins MAP-13 as a convenience, not the carrier. UI rows: a row of their own.

## Checked and found sound

- Rejecting W1's overlay: it checks measured against the baseline but not committed rows, so a line removed while
  its case still passes is gone at the next baseline, and mutation rows are NOT_RUN on a push (:166-170). The
  draft's (a) is the advisor's earlier A1.
- Rejecting W2's grep: a new module with code, no row and no report prints no `NOT_RUN` at all (:130-155).
- Draft claims verified: `ratchet.java`:54, :58-59, :107-205, :415-443, :761-777; `pit-summary.sh`:42-45, :64-66;
  `mapping-ci.yml`:18-30, :70-95; nightly :51; `ci.yml`:94-100; README:27. `github.token` is no repository secret.
- "Closest" is well defined: without force-push, `main`'s pushed tips form one ancestry chain.

## Unverified or unchecked

Every GitHub API and runner behaviour in the draft's §6; whether a backend-green push run exists on `main` today (the
draft's "First run" rests on it - have the scout look); whether a session's token may re-run a workflow; whether a
session may edit root `docs/WORKFLOW.md` §1.
