# MAP-9 - the architecture advisor's opposition to the designer's draft (2026-11-16)

Hand-back as received (the draft: `agent/work/MAP-9-designer.md`). Verdict: SOUND WITH CHANGES - the floors, the single
PIT threshold and the scope are fit for the owner; P2 and the SAP sentence (P4) each claim more than the code and the
corpus support today. Required before it goes: W1, W2, W4, W5, W6, W8. Optional: W3, W7, W9 and the alternatives.

## Weak points
- W1, P2 "no decrease is its ratchet" (required). `check` reads `ratchet/` of the working tree
  (`mapping/scripts/ratchet.java`:54, :117, :130): in CI a commit is compared with its own rows. One push that deletes a
  case with its line in `corpus.txt`, or lowers a coverage row with the code, passes; a deleted row passes as `NEW`
  (:150-155, :187-192). ADR-0019:55-56 relied on an integration branch that does not move; direct pushes to `main`
  removed it. Smallest fix, in the same row as the CI step: overlay the previous commit's rows before `check`, as
  `ci.yml`:91-106 does (`git restore --source="$BEFORE" --worktree -- ratchet/`); the tool does not change.
- W2, P2 `NOT_RUN` (required). `check` needs the oracle's surefire reports and each module's `jacoco.csv` from
  `verify` in the same job. A push that sets `jacoco.skip` or `pit.skip`, or loses the oracle's report directory,
  prints `NOT_RUN` and passes (:141-143, :166-168, :211-212, :279-283). The nightly runs only `test-compile`: every
  oracle and coverage row is `NOT_RUN` there. Fix without a new mode: the step fails on
  `^NOT_RUN (oracle/|coverage (engine|service))` in the backend job and on `^NOT_RUN mutation` in the nightly.
- W3, regeneration. Without it the rule is "no decrease below the last regenerated row": 93 to 97 and back passes, and
  cases added by MAP-2, -3, -4, -6 are unprotected. The root `docs/WORKFLOW.md` does not mention the ratchet. See Q-A.
- W4, P3 survivor counts (required). They were enforced before a merge in `mapping/scripts/gate-ticket.sh`:57-72, and
  that gate fails on root-process commits today (MAP-7, step 2c). After the draft they are checked only nightly, after
  the push, against the same commit's `mutation.tsv`: a push that raises a row from 11 to 14 with its code passes both.
  Only the common 80 % is firmly in force, against a measured 98 to 100 %. Fix: W1's compare covers `mutation.tsv` and
  the UI rows as rows (A1), and a MAP packet that touches a PIT module names `scripts/pit-summary.sh` for the verifier.
- W5, P4 wording (required). "Observed on the owner's runs of SAP PO 7.5 and SAP Cloud Integration" is not true as
  worded: in the `meta.json` files 116 run entries in 11 of 15 sets say `"cpi": "NOT_RUN"`, others
  `ENVIRONMENT_BLOCKED`, and 15 say `"po75": "NOT_RUN"` (`mapping/corpus/observations/obs-text/meta.json`:13, :21:
  "CPI observations pending"). Write "PO 7.5 or Cloud Integration"; "follows CPI where they disagree" holds only where
  both ran. Counts verified by the advisor: 360 and 323 lines, 15 sets; provenance 148 observed, 40 documented, 171
  decided, 1 assumed (`mapping/corpus/cases/target/minOccurs/004`). How many of the 148 ran on both is not counted.
- W6, P4 counts in the root rule file (required). Only the owner edits that file and MAP-2, -3, -4, -6 "observe": 360,
  148, 323 and 15 go stale at the first corpus task and no session may correct them. The rule names the files
  (`ratchet/oracle/*.txt`, `provenance` in `case.json`); dated counts go in the proposal. "Run in every `verify`" holds
  only for a full-reactor `verify`.
- W7, P1. The draft's "75 % is not an option: it would lower today's floors" contradicts its own "the higher of": the
  75 tier lowers nothing (the seven stay 90/80, the structure pair gets 80/75). By `issues/ISSUE-9.md`:135-137 the
  engine is neither `api` nor `core`: 90 on branches is a choice beyond "the platform's floors", free today. P8b names
  `mapping/engine` directories that MAP-1 may move. "Class-C modules" in the root file collides with that file's
  BACKLOG class C.
- W8, Q3 and the wiring test (required). "`mapping-ci` a required check: yes" contradicts `issues/ISSUE-9.md`:118-120,
  the advice already given to the owner: not while pushing to `main`, because a path-filtered workflow never reports
  on other pushes. And `mapping-nightly.yml` is not in the `paths` of `mapping-ci.yml` (:11-17, :20-26): removing the
  nightly's ratchet step would run no wiring test. Add it to both lists.
- W9, the draft's tests. The committed tests only grep the wiring; the behaviours are scratch runs. "A row lowered with
  its code in one commit" is tested nowhere and passes today. Commit them as script tests on a scratch copy beside
  `mapping/scripts/test/ratchet-*-test.sh`, red first.

## Missed alternatives
- A1 Row monotonicity: compare `ratchet/` at HEAD with the previous commit's as text (no line removed, no percentage
  lower, no survivor count higher, no row removed), then run `check` on HEAD. It needs no report, so it covers the
  mutation and UI rows on every push. About 30 lines and an ADR-0019 amendment.
- A2 Tier by role: the root rule states the tier by role and points to ADR-0019's floor table for the modules. One
  indirection, and no owner patch when MAP-1 moves modules.
- A3 Harness: no new floor for the service - its row 98/96 in CI binds harder than a floor of 75. For the UI, either P6
  or `check` in the `ui` job with a JDK, not both.
- A4 Property tests: CONSIDER-6 (pending the owner since 2026-10-29) already asks for a jqwik alternative; the
  mapping's seeded JUnit generators are one of its three candidates and ADR-0018:161-167 rejected the other two. One
  answer there makes the property-test line common.

## Risks
- Noise: the ratchet has no band and has never run on a GitHub runner. Service branches are 103 of 107: one
  timing-dependent branch gives 95 < 96 and a random red - the failure D-029 was written for. PIT counts with
  timed-out mutants (xdm 11, runtime 5) on a slower runner are unobserved. Measure several runner runs and one
  deliberate red in CI before the owner applies the rule patch.
- Nightly: nobody in the root loop reads a red nightly, and after the draft the survivor check lives only there.
- Not in the draft: what a platform-only task runs when it touches `mapping/`, the root pom or the wrapper
  (`./mvnw -f mapping/pom.xml -Pbench verify` plus `check`; 3:04 min measured); the Windows job after a branch
  minimum (`-DskipTests`, unchecked); PIT minutes for structure-import, the largest module (unmeasured); the UI rows
  match absolute paths (`ratchet.java`:337), so a summary carried from another job works only on the same path.
- Expensive to reverse, unmarked: counts and directory names in the root rule file; three owner patches on one file -
  P8b's "lines 62-68" is right today and shifts by 8 after `agent/patches/BRAND-3-steering.patch`, whose lines 12-13
  cite the D-153 list that P5 narrows.

## The open questions
- Q1 (owner): yes for the seven PIT modules. The structure pair as a choice: 90/90 leaves `engine/structure` three
  lines of margin (110 of 118), though its ratchet row 93 binds first once W1 is fixed. The advisor would accept
  90/90, marked `draft - review at hand-over` until MAP-1.
- Q2 (the architect's, reversible): the service untouched - the owner was told "keep their floors"
  (`issues/ISSUE-9.md`:137). The UI's two branch thresholds: yes - the only CI guard of those branches while the UI
  rows are `NOT_RUN`. The installed type accepts `branches` per glob
  (`mapping/ui/node_modules/vitest/dist/chunks/plugin.d.C8mmycim.d.ts`:1887).
- Q3: the CI step is the architect's - yes, with W1 and W2 in the same row. Required check: no (W8).
- Q4 (the architect's): not now. Tell the owner the mapping's real bar is "no new survivor", stricter than 80 %.
- Q5 (the architect's): a measuring row - score and minutes.
- Q-A, who regenerates: the verifier, at every task commit that touches `mapping/` - `regen --all` after the full
  `verify` and `npm run test` (regen needs the UI report, :428-432, not PIT, :168). The reviewer checks the `ratchet/`
  diff against the measurement in PROGRESS, as D-050 does. ADR-0019:111-113 rejected this because of three tickets in
  flight, which one task = one commit removes. An ADR-0019 amendment: it joins the owner's amendment item; MAP-7
  carries the script change.
- Missing for the owner: the ADR-0019 and ADR-0017 amendments as a question of their own; W5's plain statement, with
  the offer to queue the pending CPI runs and the one `assumed` case, which only the owner can run; CONSIDER-6 in the
  same sitting; the order of the three patches.
- P5: narrowing D-153 is not premature if the new row amends it by reference and stays `draft`. The jqwik issue is
  real for the platform but not MAP-9's and needs no new row: FIX-132 and CONSIDER-6 exist; the draft cites neither.

Unchecked by the advisor: the jqwik text in the jar (taken from ADR-0018:161-165 and the two rows), `jacoco:check`
under `-DskipTests`, and the 1 s for `check` (ADR-0019:140 is a prototype figure).
