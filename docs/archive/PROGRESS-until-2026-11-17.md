
## 2026-11-17 — the session stopped on the owner's relayed request: MAP-12b done, the rest saved unfinished

- Stop: a message from the owner's other session ("Rail8 projekt restructuring") relayed his request to stop; it was not typed
  in this session, which reports to him and waits. Nothing new was started after it; two running agents were let finish.
- MAP-12b is `done`: acceptance 4 read on GitHub - `mapping-ci` run 5 on 8d3daf37, backend job green, `BASELINE 71c3b69e...
  run 43960684995`, ten MEASURED lines equal to the local counts, `RATCHET CHECK OK` (`agent/work/MAP-12b-evidence.txt` D).
- FIX-268 [G]: measured on 77b2c587, every part VERIFIED_PASS, `ci` run 255 green by job name - NOT judged: no gate-reviewer
  ran, `HANDOVER.md` and `bench/RESULTS.md` are unchanged. Evidence and logs committed; drafts in `agent/wip/FIX-268-close.py`.
- MAP-11 [B]: steps 0 and 1 ran (check alive; build green with the 90/90 floor). Not on main: `agent/wip/MAP-11.patch`.
- FIX-269: only the scout ran (`agent/work/FIX-269-scout.md`). No packet, no test, no code.
- Relayed with the stop, not confirmed here, no CI changed: per change only the build with unit and integration tests and the
  sign-off check are to block; floors, no-decrease, mutation, the second Postgres, Windows and benchmarks become a nightly
  report. The remaining mapping test-rule rows wait for the owner; so does the date 2026-11-25 of MAP-12b's check.
- Context compacted earlier in this episode; tree and log re-verified. `ci` run 255 (77b2c587) green; 256 (8d3daf37) not read.

## 2026-11-17 — MAP-12b the mapping's ratchet check runs on GitHub against a baseline — on main, review PASS [C] r2; acceptance 4 open

- New under `mapping/scripts/`: `ratchet-baseline.sh` (the closest strict ancestor of HEAD whose push run of `mapping-ci` on `main`
  has the backend job green - GitHub's API with the run's own token), `ratchet-ci.sh push|nightly`, `ratchet-enforced-from`
  (`2026-11-25`); both workflows call them; `pit-summary.sh` prints a timed-out line. Names, lines, exit codes, date: draft.
- Until the date a failing check and a failed lookup warn; from it they fail the job. What the script refuses fails it on any day.
- Red first in three rounds (test-author): `ratchet-ci-test.sh` 332 assertions, the wiring test 24, `pit-summary-test.sh` 22
  (`agent/work/MAP-12b-red.log`, `-red2.log`, `-red3.log`); each new case shown red against a named mutant of the script.
- VERIFIED_PASS (`agent/work/MAP-12b-evidence.txt`): 17 script tests, 0 FAIL, on 77b2c587; the build, `check` and `regen --all`
  at 6b23ddc1 (round 1: no ratchet data file moved). Round 3 touched scripts and documents only: its part C is NOT_RUN, reason given.
- Review [C]: FAIL r1 (two unpinned jq guards; a job's conclusion trimmed by `read`; two document sentences that were false),
  PASS r2. Fail-closed since: an answer that is not the JSON expected, a conclusion that is not one word, a missing `gh` or `jq`.
- Open: acceptance 4 - the first real `mapping-ci` run read by job name; then the row is `done`. MAP-7's row notes the comment
  of `gate-ticket.sh`:13-15. Context compacted during this task; tree and log re-verified before the commit.

## 2026-11-17 — FIX-265 the last of replay leaves the code: `Envelope.REPLAY_OF` and the two guards — done, review PASS [B]

- Removed: the public constant `Envelope.REPLAY_OF` of `rail8-api` and its Javadoc mentions, the clause `props->'replayOf' is null`
  of `PgWorkQueue`'s restart statement and its twin check in `AdminServer`. Draft - review at hand-over (HANDOVER §6).
- Safe because a replay's row never carried the worker's `attempt`: the attempt check alone refuses it; old rows keep the prop.
- Red first (test-author): a worker's park that carries another prop is restarted - `PgWorkQueueRestartIT`, `AdminDlqRestartIT`
  (202), `AdminDlqRestartTest` red on the guards (`agent/work/FIX-265-1-red.log`, `-1-red-it.log`). "A row without an attempt is
  not restarted" is green from the start: shown red on a scratch statement without the attempt clause. The 422's text is pinned whole.
- VERIFIED_PASS (`agent/work/FIX-265-evidence.txt`): the two greps (3 hits, one `RETIRED` string per test file; 6 of the word in
  another meaning); whole reactor `-Pit`, 4668 tests (4387 under platform/, 281 in bench/), 0 failures; coverage: missed unchanged
  (api 2 lines; runtime 33 lines, 15 branches), no decrease, the baseline file identical after `--write`; scoped PIT: api 32 of 32,
  core's queue package the same two survivors before and after.
- Review [B]: FAIL r1 on two documents (D-135's row still described the removed re-check; HANDOVER §6 did not list the draft),
  PASS r2. The FIX-262 entry's "ARCHITECTURE is ahead of the code until FIX-265" ends with this commit.
- Also: FIX-268's packet and its stand-in partner are written; `ci` run 254 read green (237 to 254 in a row).

## 2026-11-16 — FIX-280 testkit's branch figure is the same in the runs measured — done, review PASS [B]

- Cause: each of `ComponentContract`'s twelve polls kept its comparison in the call site's lambda; its false outcome was taken
  only when the first poll came before the delivery, so the module's branches read 397 to 402 of 417 on unchanged code.
- Red first (test-author): five cases of `ComponentContractSoonTest` over three helpers that did not exist - 8 compile errors
  (`agent/work/FIX-280-red-testkit.log`); one case changed before approval (its witness: three polls inside 20 ms).
- Change (implementer, `ComponentContract.java` only): `soonEquals`, `soonAtLeast`, `soonPresent`; the twelve call sites use them
  with a method reference - same value, comparison, number and message. No comparison is left in a call site's lambda.
- `Journal`:1013's fourth branch (a later force in flight: no wait) gets a case that reaches it by construction; it fails on
  its own assertion against a hand mutant (`agent/work/FIX-280-mutant-journal.log`); `Journal.java` is unchanged.
- VERIFIED_PASS (`agent/work/FIX-280-evidence.txt`): testkit alone three times and the whole reactor with `-Pit` once - 384 of
  399 branches and the same missed lines in all four; reactor 4383 tests, 0 failures; `coverage_check.py` no decrease;
  `Journal`:1013 `mb="0" cb="4"`. `ci/coverage-baseline.json` written as measured (it was last written at 0481fac9).
- Review [B]: PASS, round 1. Its remarks: "the runs measured", not "every run"; FIX-281 gains the older case's witness and a
  watch on `PollingConsumerContract`'s polls. `ci` 252 and 253 read green (237 to 253 in a row).

## 2026-11-16 — FIX-240 the polling suite's self-test fails the same cases in every run — done, review PASS [B]

- The flaky test `aRemovalBeforeTheAnswerIsAwaitedFailsEveryCaseThatKeepsAFile` failed 1 run of 3 on 2026-11-16 (red on record:
  `agent/work/FIX-280-before-run2.log`): its broken variant removed the file on the round's thread after it had started the
  handler's thread, and the contract's stop case reads the directory once it saw the handler's call - two unordered acts.
- Change, test code only (+6/-2, the test-author): the variant removes before it starts the handler. All eight expected cases
  fail in every run (`agent/work/FIX-240-evidence.txt`); `PollingConsumerContract.java` is untouched.
- VERIFIED_PASS: the class 20 times in a row, 10 under load - 22 tests, 0 failures each (`agent/work/FIX-240-20runs.log`);
  `clean verify` of `:rail8-testkit` - 300 tests, 0 failures. NOT_RUN: the whole reactor (test code of a leaf module).
- F-59: the suites' `soon` is a real-time bounded wait for an event of the component under test, not a timer; only a failing
  case pays the patience. Measured: sixteen waits of 2 s and two joins of 4 s are 40.25 of the class's 40.93 s.
- F-60 and CONSIDER-30: two reads of the contract are one instant. FIX-281: two leftovers in other variants.
- Review [B]: FAIL, FAIL, PASS - every reason in the architect's findings, none in the test: F-60 named a case that does catch
  the fault; F-59's count of waits was read, twice wrong, and is now measured. `ci` 250 and `mapping-ci` 4 read green.

## 2026-11-16 — MAP-10 mutation testing measured on `structure` and `structure-import` — done, review PASS [A]

- A measurement: no file under `mapping/` or `.github/` changes. One PIT run per module by the verifier, upstream installed
  first so that no upstream module is mutated again, the skip lifted on the command line: `agent/work/MAP-10-numbers.md`.
- `engine/structure`: VERIFIED_PASS - 33 mutants, 33 killed (100 %), the tool's own time 12 s.
- `engine/structure-import`, first call: EXECUTED_FAIL - "126 tests did not pass without mutation". Its tests read the system
  property `rail8.corpus`, which its pom hands to surefire alone (:57-63), so the mutation tool's JVMs had no corpus.
- Second call, the property handed over for the one run (`-DjvmArgs=-Drail8.corpus=...`): VERIFIED_PASS - 625 mutants, 598
  killed (96 %; 2 of them by time-out), 27 survive in eight classes, the tool's own time 78 s. Not run on GitHub's machine.
- `issues/ISSUE-13.md` names the numbers beside "Not verified by a run". Whether the two modules join the nightly mutation run
  is a floor and the owner's: new row MAP-16, `pending (owner)`, with the numbers, what a yes builds, and one recommendation.
- Review [A]: PASS, round 1; its remark - the minute and a half is this machine's, not GitHub's - is in MAP-16's row.

## 2026-11-16 — MAP-12a the ratchet check is handed a baseline (`check --baseline <dir>`) — done, review PASS [C]

- `java scripts/ratchet.java check --baseline <dir>` does what `check` does, then holds the commit's `ratchet/` rows against the
  copy in `<dir>`: a removed row or listed case, a lower percentage, a higher survivor count, a missing required report fail it.
- Red first: `mapping/scripts/test/ratchet-baseline-test.sh` - 43 of its 46 cases red on the old script for the reason each
  names, 3 green by design (`agent/work/MAP-12a-red.log`); all green after, the three sibling tests unchanged (`-green.log`).
- `regen --all` changes in one point (a JaCoCo csv file without figures is a report); plain `check` and `bench` do not.
- VERIFIED_PASS on the real tree after the mapping's build (`agent/work/MAP-12a-evidence.txt`): plain `check` byte-identical old
  against new; the mode exits 0 against HEAD's rows with ten MEASURED lines; a raised coverage row and a lowered survivor count
  each exit 1 on that row; the 15 script tests of the workflow exit 0. The implementer's 37 hand mutants: all killed.
- The test joins the script step of `mapping-ci.yml`. Documents: the mapping's ADR-0019 (the mode; what still passes it while a
  figure falls) and `mapping/ratchet/README.md`. `draft — review at hand-over`: the command line and the exit codes.
- Review [C]: FAIL (6: two behaviours no case pinned - each got a case shown red against a hand mutant, 47 cases now - and four
  sentences of the documents), then PASS. Not here: which commit is the baseline and where the mode runs (MAP-12b, ready).

## 2026-11-16 — FIX-264 replay leaves the platform's code (D-156) — done, review PASS [B]

- Gone: `Replay`, `ReplayRefusedException`, `RowNotRemovedException`, `FlowBuilder.replay`, the producers a deployment opened
  for it, the oracle's check `REPLAY_YIELDS_RECORDED` (deleted, no tombstone: D-165), and replay's tests with no successor.
- Kept and tested as before: park, the failed list, `/retry`, the trail, delivery close. Left for their rows: `REPLAY_OF` and
  `/retry`'s refusal words (FIX-265), `Dlq.remove` and `WorkQueue.restart` (FIX-276), what has no main caller now (FIX-269).
- Run 1 was red: `DeploymentsSecretsTest.producerOptionsResolvedToo` asked for at least four producers, replay's among them
  (now exactly three) - a wrong expectation the packet had missed. Core and runtime read lower by ratio: fix round 1 added cases, no exception.
- VERIFIED_PASS run 2 (`agent/work/FIX-264-evidence.txt`): whole reactor `-Pit` 4377 tests, 0 failures, errors, skips; ArchUnit
  48; coverage no decrease - core 99.72 -> 99.96 % lines, 99.45 -> 99.51 % branches; runtime 99.47 -> 99.50, 99.40 -> 99.47.
- Mutation (`agent/work/FIX-264-pit-match.txt`): no mutant unkilled after and killed before; `Parking`:88's two lambdas,
  SURVIVED before, are KILLED. `PinnedSeedsTest` green, `pinned-seeds.tsv` byte-identical.
- Amended in the packet (acceptance 8): testkit's branch figure is lower, 397 of 417 against 400, in a module the diff cannot
  reach - it read 401, 400, 398, 397 in four runs on the same code. New row FIX-280 [B], put first in the order.
- Review [B]: PASS, round 1; its remarks on Javadoc and a dead test fixture went to FIX-269's notes.

## 2026-11-16 — MAP-12 the ratchet's check on GitHub — designed and split into 12a, 12b, 12c (nothing built)

- Class C path: the designer's draft (`agent/work/MAP-12-designer.md`), the advisor's opposition (`-advisor.md`: sound with
  changes, six required), a scout's map of today's script (`-scout.md`), the architect's decision in the rows.
- Decided, as a bracket on D-164 (the architect's reading, draft, for the owner at hand-over): the baseline is the last commit
  the check PASSED on, not ran on - else a red push's lowered rows are the next push's baseline. Found through GitHub's API.
- The reporting week's date is one file both workflows' script reads; the nightly comparison reports for the same week (the
  mapping's F-16: timed-out mutants depend on the machine); the switch leaves at the week's end (12c): no off-switch remains.
- Not built: a way for a row to fall or leave on purpose. The advisor asked for a waiver file; the review showed it would excuse
  only a hand edit that the mapping's rule file forbids - the owner's file. The question goes to him with MAP-1's design.
- Rows: MAP-12a [C] the script's `check --baseline` (ready; packet; no Maven for its red), MAP-12b [C] the lookup, both
  workflows, the wiring test, the documents; MAP-12c [B] the week's record; MAP-15 [B] the editor's rows. MAP-13 waits for 12c.
- Checked first: GitHub's documentation of the two endpoints (through Context7; the host is outside this session's network) and a
  real run's jobs (43928160498: the backend job green on 89311471 - a baseline exists today). Review [C]: three rounds - FAIL (11), FAIL (3), PASS.

## 2026-11-16 — MAP-14 the list for the owner's next observation session on SAP — done, review PASS [A]

- A document, `mapping/corpus/observations/follow-up-2026-11.md`, and one sentence in the kit's README. No case, output, ratchet
  row or log entry changes. D-164 (7): the architect's reading - a list obliges no one, the runs stay the owner's.
- Scout first (the requests' form, the marks' own words); then the logs by script, a run = a mapping on an input: 195 runs in 14
  sets - 152 observed on both, 32 marked on Cloud Integration, 1 on PO only (blocked on Cloud Integration), 10 on neither (2 withdrawn).
- Review r1 found the first count wrong: entries linked by case merged distinct runs and hid the blocked one. ISSUE-13's 190 is
  corrected in place, dated: 193 requested runs. The eight "neither" stand; no item of the proposal rested on the count.
- Found: the 28 "runtime provenance unverified" marks need no mapping run again - a later field of the same entries verified the
  parameters; open are the JVM's locale and zone ID (two facts) and, for four current-date runs, the monitor's start time.
- The 3 "partial, time zone" marks sit on cases the owner decided on 2026-11-13 (the mapping's F-43): nothing to run. `fn/trim/001`:
  one run. `target/minOccurs/004` is not requestable from a list (F-38's next step: two mappings drawn up); its part names the blocked run.
- Review [A]: r1 FAIL (the count; the later fields that answer part of a mark not named; two unsourced sentences), r2 PASS; rounds in the commit body.
- Beside it: the scripts that count and generate are in `agent/work/MAP-14/`; NOTES has the counting rule. Name guard clean; ledger check: the 9 old.

## 2026-11-16 — ISSUE-13 the owner's yes on one set of test rules for both areas (D-164) — done, review PASS [C]

- The owner, in the session, to the five questions: "souhlas se všemi doporučeními, pokračuj". Documents only; nothing is built.
- D-164: engine modules with code at 90 % line and branch (draft - review at hand-over until MAP-1); the ratchet's check in
  GitHub's checks, repaired, after a week of reporting only; 80 % mutation nightly in both areas, "no new surviving mutant" kept
  by the verifier before a commit and by the nightly comparison; the sentence about SAP; root rules name the tier by role;
  a session may amend the mapping's ADR-0019 and ADR-0017; `mapping-ci` not a required check now. D-153 carries a bracket.
- The architect's readings, told to the owner: the SAP sentence was the proposal and is taken as agreed; the list of the 32 marked
  outputs and the assumed case had no recommendation - they are listed, the runs stay the owner's.
- Rows: MAP-9 done. New: MAP-11 [B] floors, MAP-12 [C] the ratchet on GitHub and in the nightly job, MAP-13 [C] the rule-file
  patch for the owner, MAP-14 [A] the observation list. MAP-10 [A] keeps its turn. ISSUE-13 closed; ISSUE-9 item 4 points at D-164.
- Not decided, and D-164 says so: the mapping's floors as phase-gate conditions (to the owner with MAP-1's design); whether
  `structure` and `structure-import` join the nightly mutation run (a floor: to the owner with MAP-10's numbers).
- Beside it: FIX-264 [B] steps 1a, 1b approved (tests, in the tree), step 2 running; MAP-11's packet written. `ci` runs 245, 246 green.
- Review [C]: r1 FAIL, five (one row made the floors a gate condition unasked); r2 FAIL, two; r3 PASS. Name guard clean; ledger check: the 9 old.

## 2026-11-16 — MAP-9 one set of test rules for both areas: the proposal (ISSUE-13) — pending (owner), review PASS [C]

- Context compacted after FIX-275's push; HANDOFF, `git status` and `git log -3` were re-read first.
- No code and no rule changed. The owner asked for coverage on the mapping and a proposal; nothing tightens before the answer.
- Measured at f1be875c (`agent/work/MAP-9-numbers.md`): every measured engine module is above 90 % line and branch (lowest:
  structure 93.22 line, mapping 98.92 branch); PIT 98 to 100 % on seven modules; the editor's unit tests reach 36 % of its lines.
- Found beside the numbers: the mapping's ratchet check runs in no workflow, and run as it is it would compare a commit with its
  own rows. Of 360 corpus cases 148 are observed on a real system (147 on both), 40 documented, 171 decided, 1 assumed - and that
  one is contradicted by both systems and still listed. No build runs SAP. Of 190 requested observation runs none is waiting.
- Designer's draft, advisor's opposition (`-designer.md`, `-advisor.md`); the proposal takes the advisor's W1, W2, W4, W5, W6, W8.
- ISSUE-13 asks five things: 90/90 for engine modules or 75 as a lower bound; the ratchet on every push after a reporting week;
  may a session amend the mapping's ADR-0019 and ADR-0017; the sentence about SAP and the 32 marked outputs; `mapping-ci` as a
  required check (recommended: not now). ISSUE-9 item 4 points at it. Without an answer only MAP-10 [A] (new row) proceeds.
- Review [C]: three rounds. r1 FAIL, seven reasons - above all 116 log entries read as pending SAP runs; r2 FAIL, two, both in the recount; r3 PASS.
- ci run 245 (FIX-275) was still running. FIX-264's packet and second scout pass are written. Name guard clean; ledger check: the 9 old.

## 2026-11-16 — FIX-275 `/retry` refuses while a work row of its id is unsettled (F-57) — done, review PASS [C]

- Context compacted before the implementer's hand-back; HANDOFF, `git status` and `git log -3` were re-read first.
- Red first, observed on the code before the guard (`agent/work/FIX-275-red-its.log`): `DlqRestartIT` 3 of 5 failed - a second
  work row of the id ([8, 9]); the partner accepted twice; delivered and back in the failed list. F-57's Evidence has the lines.
- The guard: RESTART probes `work` under the DLQ row lock (by the row's key when it holds one) and writes only when the probe
  is empty; `UnsettledException(messageId, seq)` in core; the in-memory queue refuses likewise; `/retry` answers 409 with id and seq.
- Answer order kept: `false` without the DLQ row, then unsettled, then the lost body. The refusal names the id's oldest row.
- Step 2b (added on the hand-back): core's own report missed the new code - the contract runs from runtime. Four cases in core, one
  in the contract (two rows of one id). Green on arrival; evidence: scoped PIT 93 of 94 (the survivor is `eligible` :213) and mutants.
- Verified: whole reactor `-Pit` green - 17 modules, 4 679 tests, 0 failures, errors or skips; coverage no decrease (core 99.7 / 99.4).
- Not pinned: the probe's plan, its time bound (FIX-276), a node's death as the cause, what the snapshot relies on. ARCHITECTURE 3.33.
- Also: FIX-264's coverage 'before' (`agent/work/FIX-264-before/`); ci runs 243, 244 read green. MAP-9 measured alongside, at the
  owner's question: `agent/work/MAP-9-numbers.md` - every measured mapping module is above 90 % line and branch; its row stays open.
- Review [C]: four rounds - 1 FAIL a Javadoc answer list; 2, 3 FAIL stale 'pending' sentences in documents; 4 PASS. Name guard clean; ledger check: the 9 old.

## 2026-11-16 — ISSUE-12 the owner's answers on cancelling a message — recorded, review PASS [C]; Deviation: F-58

- The owner answered ISSUE-12's six items in the session; D-163 records them and amends D-162; ISSUE-12 is closed.
- Item 1 withdrawn: a sender's resend is another message under another id. F-58 (minor): sftp's dedupe key is path, mtime
  and content hash - it recognises the platform's second pickup of one file. ARCHITECTURE section 4's two sentences and
  section 6's one are corrected; ISSUE-11 item 2 and CONSIDER-27 carry a note.
- No name is required on either act (roles and authorization, OPS-9); `/retry` takes no reason, a cancel an optional one
  (an optional JSON body); a cancel's record and its `events` entry carry the error text, cut to 4 096 characters; history
  by age; a cancel is never undone. ARCHITECTURE 3.32 (sections 4, 6, 9).
- Stated consequence: an audit row can quote payload values and outlives the payload - OPS-9 and CONSIDER-29 carry it.
- Rows amended: FIX-276, FIX-277, FIX-278 (cases for the optional reason, no required name, the error text). ISSUE-10
  item 3 answered in part. Documents only; review [C]: three rounds (FAIL 1, FAIL 1, PASS). Name guard clean; ledger check: the 9 old.
- In flight in the same tree, not in this commit's code: FIX-275 - its red tests are observed (three ITs red on today's
  code: two accepts; delivered and back in the failed list) and saved as `agent/work/FIX-275-tests.patch`.

## 2026-11-16 — FIX-267 cancel and the record of a retry, designed — done, review PASS [C]; Deviation: F-57

- Context compacted before this entry; state re-read from HANDOFF, `git status` and `git log -3`. A design row: no code,
  no test - the red tests are named in the rows it wrote.
- D-162 (draft - review at hand-over): `POST /admin/dlq/{id}/cancel`, actor and reason required; one audited delete; both
  acts recorded in `audit` (`message.retry`, `message.cancel`), no new table or status; a cancelled id answers 410 on both
  routes, any other id that is not parked 404 with its last event; `events` in the timeline; core type `FailedMessages`.
- F-57 (major; read in the code by designer, advisor and architect, not staged): a `/retry` before a park has settled
  inserts a second work row - delivered twice, or delivered and parked again. Both acts are to answer 409 while a work row
  of the id exists; an act's statements get a bound (`RAIL8_ADMIN_ACT_TIMEOUT`), its commit never.
- ARCHITECTURE 3.31: section 6 *The operator's acts on a failed message*; sections 4, 7, 8, 9. ISSUE-12: six questions.
- Rows: FIX-275 [C] (the guard, before FIX-268), 276 [C] (the type, retry's record, the bound), 277 [C] (cancel, events,
  `Dlq.remove` out), 278 [B] (routes), 279 [A] (HANDOVER's lists); CONSIDER-28 (the parked row reruns), CONSIDER-29 (audit's
  bound); brackets on D-126, D-127, D-135, D-136, D-156. Also: FIX-275's packet, FIX-264's scout file; ci 241, 242 green.
- Path: designer, advisor (ten changes), designer again, advisor again (eight), the architect; where D-162 departs from
  the drafts is in `agent/work/FIX-267-evidence.txt`. Review [C]: three rounds (FAIL 10, FAIL 1, PASS). Name guard clean; ledger check: the 9 old.

## 2026-11-16 — FIX-263 the two replay routes leave the admin API — done, review PASS [B]

- D-156, F-55: `POST /admin/messages/{id}/replay` and `POST /admin/dlq/{id}/replay` are gone - both answer as an unregistered
  route (404). `AdminServer` loses the routes, their handlers and helpers and its id source; `Node` passes none.
- `/retry`'s 422 texts say why and name no other route. Red first: `NodeIT.bothReplayRoutesAnswerAsUnknownRoutes`,
  `AdminDlqRestartTest.noRetryRefusalNamesAnotherRoute` (observed red, `agent/work/FIX-263-ta-red.log`, `-ta-it2.log`).
- Tests by the approved per-case list (`agent/work/FIX-263-cases.md` C): the cases that pinned only the copy are deleted
  (`AdminServerReplayTest`, `AdminServerReplayRaceTest`, two of `NodeIT`, one of `NodePoolWaitIT`); the survivors moved - the
  token, the virtual-thread offload, the browser guard, retention against a parked message (`RetentionRetryIT`), a lost body.
- Whole reactor green (17 modules): runtime 1583 -> 1562 unit tests (24 went, 3 new) and 719 -> 717 ITs, every other count
  unchanged; coverage no decrease; per class no surviving runtime class misses more (`agent/work/FIX-263-cmp.log`).
- ARCHITECTURE 3.30: the lease paragraph loses its "pending"; noted that any keyed flow's row parked without its key gets the
  V17 refusal and can never be retried - new row FIX-274 [C]. FIX-264's row corrected by the list (`ReplayTest` does not go whole).
- Review [B]: PASS, one round; three remarks taken in tests after it, their classes run green again. Evidence:
  `agent/work/FIX-263-evidence.txt`. FIX-267 [C] (cancel, the record of a retry) is in design beside it: draft and opposition.

## 2026-11-16 — FIX-270 a message can arrive more than once: the architecture says it plainly — done, review PASS [C]; Deviation: F-56

- Context compacted before this entry: HANDOFF, `git status` and `git log -3` (b04e336a) re-read before any change.
- `docs/ARCHITECTURE.md` 3.29, section 4: a new paragraph, "A message can arrive more than once", `draft — review at hand-over`
  (a public statement to partners; ISSUE-11 items 2 to 4 as their defaults). Causes: the in-step retry in every mode, a scheduled
  retry, a run left to its lease after the partner had the message, an operator's retry. Same `x-message-id`; nothing marks a repeat.
- Deviation, F-56 (minor): three standing sentences read wider than what is built and are made exact - XI "exactly-once" is the
  intake's and only where a dedupe key is named; section 5's "every producer"; the Phase 2 gate's and GATE check 5's "no duplicate"
  carry a dated reading (none saved twice), their meaning left to the owner: ISSUE-11 item 6 (new).
- Corrected: ISSUE-11 item 5 said the park of a delivered message was only read in the code - two existing tests stage it for a
  death mid-send; FIX-271 keeps the death between the two writes. Only sftp names a dedupe key, http none: CONSIDER-27 (owner).
- Each claim names its test, decision or row (`agent/work/FIX-270-evidence.txt`). Nothing is built; no Maven run (documents only).
- Review [C], four rounds: FAIL (8), FAIL (4), FAIL (1, in ISSUE-11's own text), PASS; every finding taken (the commit body).
- `ci` on main: runs 237, 239, 240 success (`agent/work/MAIN-1-ci.md`). FIX-263 is in flight in the same tree, not in this commit.

## 2026-11-16 — The owner agreed the order of the next work — recorded

- The owner, 2026-11-16, to the order proposed in the session: "souhlas s pořadím, pokračuj" - agreed with the order, go on.
- (1) Replay leaves the code and the gate's check 7 is measured again: FIX-263, 267, 264, 265, 268, 269. FIX-270 - the plain
  sentences on a second delivery - is done early, it is cheap. (2) The mapping: MAP-9 measured first, then MAP-1's design.
  (3) The other rows on a second delivery, FIX-271 to 273, last: the owner called that death an exceptional case (D-161).
- This lifts the stop of 2026-11-16 ("start nothing else"). The scratch worktree of CONSIDER-24 is removed; its files are on main.
- `ci` on main: run 238 on 25748fb9 (CONSIDER-24) success; the scheduled run 237 on 28e7ff9a was still running when read
  (`agent/work/MAIN-1-ci.md`).

## 2026-11-16 — The owner's answer on ISSUE-11 item 1: the two writes at a run's end stay apart — recorded

- Context compacted before this entry: HANDOFF, `git status`, `git log -3` (25748fb9) and the fetch re-read before any change.
- The owner asked for the second delivery at a node's death to be explained: when it happens and what it costs in
  performance. Answered in the session from `bench/RESULTS.md`, `issues/ISSUE-11.md` and the scout's list of other sources.
- The owner, 2026-11-16: "souhlas nespomalovat, tohle je výjimečný případ" - do not slow the platform down; an exceptional case.
- D-161: the owner's word on D-160 (a) - the run's rows and the work row's delete do not become one transaction. ISSUE-11
  item 1's default stands (the check only as CONSIDER-13's last case); items 2 to 5 are not answered, their defaults hold.
- Documents only: `issues/ISSUE-11.md`, `docs/DECISIONS.md`, `agent/BACKLOG.md` (a note on CONSIDER-13), `agent/HANDOFF.md`.
- No row is started: the owner's "finish what is in flight, start nothing else, we will agree the next steps" still holds.

## 2026-11-16 — CONSIDER-24 the second delivery at a node's death: cause, size, options — done, review PASS [C]

- Cause, pinned by 8 green characterization cases (`FlowWorkerRerunWindowTest`, `RerunWindowIT`): a run commits its rows, then
  deletes its work row in another store call; a death between runs the message again from the ingress under the same id. A
  death after the partner answered and before the rows commit does too, and the history then shows one finished run.
- Other sources named or ruled out (`agent/work/CONSIDER-24-scout-3.md`). A dead node's items wait for that node's restart.
- Measured, 3 runs per load (`bench/RESULTS.md`, `bench/sql/settle-gap.sql`): ledger load 4.2-4.5 ms to the rows' commit, then
  2.4-2.5 ms to the delete; the part between the two commits is 36 % of the window (29 % at one message at a time).
- Designer, then advisor (SOUND WITH CHANGES, eight, all taken): D-160 - nothing in the code changes by this row; no single
  transaction; no skip decided from the recorder's rows (it is CONSIDER-13's last-step case); no attempt number on the wire.
- The owner decides: ISSUE-11, five questions with defaults. Rows: FIX-270 [C] documents, FIX-271 [B] and FIX-273 [C] stage
  what was read but not shown, FIX-272 [B] the ledger counts both kinds. Whole reactor green with the two classes; coverage ok.
- Evidence: `agent/work/CONSIDER-24-evidence.txt`. A usage limit and a container restart interrupted the task; resumed.
- The owner, 2026-11-16: finish what is in flight, start nothing else. Nothing is in flight now; no new row is started.

## 2026-11-16 — FIX-266 replay leaves the other documents — done, review PASS [A]

- Context compacted before this entry: HANDOFF, `git status`, `git log -3` (8cfd066e) and the fetch re-read before any change.
- The word is out of `README.md`, `docs/GATE.md` (check 7; the core gate's condition 6), `HANDOVER.md`,
  `docs/PROTOTYPE-VS-ARCHITECTURE.md` and `agent/NOTES.md`; `agent/packets/GATE.md` carries a note. Documents only, no build.
- The gate: both changes are `draft — review at hand-over`, asked in ISSUE-10 item 1. `HANDOVER.md` says GATE_MET on 2026-10-30
  and check 7 not measured since D-156; whether the verdict stands until FIX-268 is the owner's (a sentence added to the issue).
- Review: round 1 FAIL - the hand-over's first line still said "Checks 1–9 hold" beside the new row 7. Fixed; round 2 PASS.
- Evidence: `agent/work/FIX-266-evidence.txt` (what stays of the word and why). Name guard clean; ledger check the same 9.
- In flight, CONSIDER-24: the cause is shown by eight staged-death cases (in the worktree); the other sources are read
  (`agent/work/CONSIDER-24-scout-3.md`); the designer's draft is in (`agent/work/CONSIDER-24-designer.md`); the measurement runs.
- A usage limit stopped two agents on 2026-11-15 at 23:29Z and the container restarted; both were resumed on 2026-11-16.
- The owner, 2026-11-16: finish what is in flight, start nothing else, then agree the next steps. No new row is started.

## 2026-11-15 — FIX-262 replay leaves the architecture (F-55, 3.28) — done, review PASS [C]; Deviation: F-55

- Context compacted before this entry: HANDOFF, `git status`, `git log -3` (baa089c9) and the fetch re-read before any change.
- D-156 (the owner: no replay) is in `docs/ARCHITECTURE.md` 3.28 by finding F-55: one id for the life of a message; the operator's
  operations on a failed one are retry and cancel under that id; no copy under a new id. Documents only, no build.
- Class C: the scout's list, the designer's draft, the architecture advisor as opponent - sound with ten changes, all taken
  (agent/packets/FIX-262.md): `Delivery.admission()` stays; "refused, removed by retention" cannot occur and is gone; recording
  is for monitoring except the at-least-once ingress row; nothing records who retried a message - said, and nothing promised.
- Cancel does not exist in the code: the text names it pending (FIX-267). F-55 states the operator's gap until then exactly.
- Rows: FIX-263 routes, 264 core and sim, 265 api, 266 documents and gate check 7, 267 [C] cancel and the record of a retry,
  268 the gate check measured again, 269 leftovers. FIX-138b and P2-20 withdrawn; thirteen open rows carry a note.
- ISSUE-10: seven questions only the owner can answer, each with a default. A one-line patch for the root CLAUDE.md waits.
- ARCHITECTURE is ahead of the code until FIX-265 - accepted, and said in the changelog row.
- `ci` runs 230-234 green (233 is FIX-235 on FIX-260, 234 BRAND-6); `site` run 1 red at configure-pages until Pages is on.
- In flight: CONSIDER-24 (re-delivery at a node's death): the cause is read from the code; the staged-death cases are being written.

## 2026-11-15 — BRAND-6 the website at rail8.dev — done, review PASS [B]; the owner switches it on

- The owner asked for a website for `rail8.dev` on GitHub Pages and chose its headline (the same text as the repository's
  description): "a modern open-source alternative to legacy integration middleware ... fast by design, every message traceable".
- `site/`: one static page and a 404 page - no script, nothing from another origin, light and dark, readable from 320 px. Brand
  files are taken from `docs/brand/` at build (`ci/site_build.py`), never copied into `site/`.
- `ci/site_check.py`: six rules (an index; every local reference resolves; nothing loaded from another origin; no script; the
  page's metadata; no word of replay). Red first: 59 cases, then 66 green after the review; `ci` runs them.
- `.github/workflows/site.yml`: a build job (checks, build, artifact) and a deploy job that alone holds the Pages token.
- Review: FAIL in round 1 on two cases that passed for the wrong reason, PASS in round 2. Nine hand mutants of the checker,
  all killed. The copy was narrowed to what the design says (a large body is streamed; payloads recorded up to a limit).
- Not shown: the page as Pages serves it. Nothing is deployed and the `site` workflow is red until the owner switches Pages
  on; the owner's steps (Pages, DNS, the custom domain, the About box) are in agent/packets/BRAND-6.md. DNS values from knowledge.
- New row BRAND-8 [A]: what the checker would miss if the page grew. `ci` of FIX-235's head: run 233 in progress at this commit, to be read.

## 2026-11-15 — FIX-235 nothing the writer was given throws through BatchingWriter — done, review PASS [C]

- D-146 says no public method of the writer completes by throwing; FIX-195's late review found four throws that still did.
- Decided (class C: designer's draft, the advisor as its opponent, seven decisions in agent/packets/FIX-235.md): a sink that answers
  null fails its batch with an NPE that names it; a throw of the weight function and a negative weight fail that save; the five
  catches are Throwable; a commit succeeded only when its result says so; a null item stays thrown; no log for a fault that fails an item.
- Red first: 21 failing cases, then core 784 green; the class 142/142 lines, 66/66 branches; scoped PIT 78 of 79, the survivor
  FIX-195's equivalent mutant. One case replaced (it pinned the negative weight's throw). A caller changes: a negative weight now
  reaches PgIntake's failure handling instead of leaving `save` as a throw.
- Left outside and listed (Javadoc, D-146): a sink's Future whose `onComplete` throws or calls its listener twice; a timer's null
  handle; the nesting of inline answers (FIX-257); weight overflow (FIX-258); a throwing log handler (FIX-245).
- Review: FAIL twice on what the documents claimed beyond the code, PASS in round 3. D-146's amendment is `draft - review at
  hand-over`. Scoped PIT not run again after the fix round (comments and two assertions).
- Whole reactor, the one run over this change and FIX-260's together: VERIFIED_PASS, all modules 0 failures (core 784), coverage no decrease.
- New rows: FIX-257 [C], FIX-258 [A], FIX-259 [A] (callers that drop the cause of a failed save).

## 2026-11-15 — FIX-260 the pool-wait fixture starts its node again when the start is refused — done, review PASS [B]

- `ci` run 229 (efb9d4ae, Postgres 18.6) failed once: NodePoolWaitIT, `VertxException: Timeout waiting for connection`. The class
  starts nodes under RAIL8_PG_POOL_WAIT PT0.2S, the wait covers the opening of a connection, and one start's first pooled statement
  was refused: the failing window holds 11 started nodes where a green run holds 12. Why the connect was slow there is not shown.
- Staged at node level: a start whose first connect is held is refused after 219 ms with the sighting's exception, and it does not
  return while the connect is pending (`closeStores` waits for it). That is the product's fault and row FIX-261; the case goes there.
- The fix is the fixture's alone: `startNode` starts again while the start is refused by the pool's elapsed wait, within the class's
  bound; any other failure at once, as itself. Five new cases, no node, no sleep. Nothing under `src/main`.
- Evidence (agent/work/FIX-260-evidence.txt): red, compile, EXECUTED_FAIL; the class 17 green three times, VERIFIED_PASS; six hand
  mutants of the fixture, five killed, the survivor (the wiring of the bound's failure) stated.
- Whole reactor, one run over this change and FIX-235's together: VERIFIED_PASS, all modules 0 failures (runtime 1583 + 716 ITs), no coverage decrease.
- Not shown: that the retry would have turned run 229 green - it has not met a real refusal; a second start is read sound, not seen.
- Review PASS [B]. Its remarks go to FIX-261: a retried refusal leaves no trace; a failed start does not stop the admin server.
- Rows opened with this commit: FIX-261, BRAND-6 (the website, in flight), BRAND-7 (the repository's address in tracked files). Context compacted.

## 2026-11-15 — The owner's answers: one licence list, the mapping joins the platform — recorded

- Documents only; no code changed. The owner answered in the session, after the entry below.
- D-159: one licence list for the repository ("a) ano b) ano") - ISC, 0BSD, MIT-0, EDL 1.0 join it; GPL-2.0 with the Classpath
  Exception, CDDL and UPL at test scope and in a benchmark module only. Not covered, frozen: `aopalliance`, three dev-only npm tools.
- D-158: the mapping's Quarkus service was a development harness; the mapping runs on the platform's technology and its editor
  joins the Rail8 UI (React, PatternFly). MAP-1 is ready: the first step, then a designer draft for the owner. D-153 stands until then.
- D-157 amended: a count of handled failures beside the error rate may be there ("to by mohlo být").
- MAP-9 [C], new: one set of test rules - the comparison with SAP PO and SAP CPI stays, coverage is added; measured first, then a
  proposal the owner sees. CONSIDER-26, new: the UI earlier than the end of the plan - the owner's session first.
- `agent/patches/BRAND-3-steering.patch` rewritten for these answers (root CLAUDE.md: the `mapping/` paragraph and the licence
  list; `mapping/CLAUDE.md` points to that list). Checked in a scratch tree: it applies after the OPS-1 patch and not before.
  The owner applies both; the rule files say the old lists until then.
- In flight, not in this commit: FIX-235 (review PASS in round 3), FIX-260 (review PASS); the whole reactor over both is running.

## 2026-11-15 — The owner's answers: no replay, a handled failure is a success — recorded

- Context compacted before this entry. Documents only; no code changed. The owner answered in the session.
- D-156: replay leaves the architecture ("Replay nechci ... za 20 let praxe ani nebyl"). One id for the life of a message; retry and
  cancel are the only operations. It supersedes the replay half of D-136. ARCHITECTURE and the code still carry replay: FIX-262 [C].
- D-157: a failure the flow's handler handled is a successful run and shows in the message's log only; option C of CONSIDER-15's
  section 5 is not taken. The architect's reading - no `handled` number either - was said back and is marked for hand-over.
- CONSIDER-24 is ready ("zjisti příčinu"): the cause of the re-run window at a node's death is shown first, then measured.
- CONSIDER-25 is answered. Still open in it: the branch's D-110, the architect's, which stays the default.
- ISSUE-9: the owner will change licences where needed; the differences were given; two yes/no asked back, not answered yet.
  ISSUE-8: the owner reads it. `rail8.dev` is registered (ISSUE-7: nothing open).
- The owner's actions, a step-by-step guide given in the session: apply the OPS-1 patch now, the BRAND-3 patch after the licence
  answer; rename the repository after the two fixes in flight are pushed. Advice: no required checks for the mapping jobs for now.
- In flight, not in this commit: FIX-235 (review round 1 FAIL on two document claims, fix round), FIX-260 (with its reviewer).

## 2026-11-15 — MAIN-1 `main` is green — done, review PASS [B]

- Context compacted before this entry. Each of the row's four conditions with what was observed:
  `agent/work/MAIN-1-evidence.txt`. No code changed in this close.
- Whole-reactor runs (`clean verify -Pit`, 17 modules, every count as expected, coverage no decrease), three in a row, no red
  one between: run 1 on a61b80cd + FIX-149, run 2 on 8e13e176 + FIX-254, run 3 on 7df0fff0 clean - VERIFIED_PASS, 20:18Z-20:45Z.
  Runs 1 and 2 ran on working trees that were committed after small changes their reviews asked for; run 3 on main's head.
- `ci` on `main`: green four runs in a row (225-228); 228, on 7df0fff0, read job by job - DCO, both build jobs, the stack job.
  It had been red since run 155 (2026-11-02). `mapping-ci` last ran on 627afa86: green.
- FIX-212 to FIX-219 done, a review PASS each; the runner halves of FIX-213 and FIX-217 are `ci` runs 203 and 201. The
  BASE-1 red set's three tests are fixed by FIX-215, FIX-212 and FIX-213 and failed in none of the runs above.
- The verdicts: FIX-195's late review round 2 PASS; FIX-201's audit round 4 PASS.
- The faults that surfaced on the way, each fixed with its own review: FIX-149, 168, 183, 221, 222, 224-227, 229, 231, 234,
  254. Open rows they left: FIX-235 [C] first, then the list in `agent/HANDOFF.md`. D-148's plan is complete with this.
- The review's remarks, taken: a wrong line citation; run 228 read a second time; a run's log must name its tree (NOTES).

## 2026-11-15 — FIX-254 a flow time no longer depends on the machine — done, review PASS

- The runner's quiet sample also counts the carrier threads in state RUNNABLE (`Quiescence.runningCarriers()`, D-155): the
  JDK's mounted count leaves a carrier out while its virtual thread is in a compensated block, such as the worker's WARNING
  reaching `System.err`. One file under `src/main`, `Quiescence.java`; `STEADY`, the node and every other module unchanged.
- Red first: `aVirtualThreadInsideACompensatedBlockIsNeverQuiet` - a thread held in a `Process` pipe read - was called quiet
  by the old code in 5 of 5 runs (`agent/work/FIX-254-red.log`); the two cases of the carrier count did not compile.
- Evidence (`agent/work/FIX-254-evidence.txt` section 5): whole reactor VERIFIED_PASS, 17 modules, cli 208 + 7, coverage no
  decrease; demo reports deviating 0 of 300 (before 3 of 210); traced runs with a quiet sample inside the worker's call 0 of
  100 (before 6 of 100). The numbers cannot show the fault gone; the first case shows that its mechanism is.
- After those runs two comment sentences changed for the review's wording remark; cli ran again, 208 + 7 green. The whole
  reactor NOT_RUN on the committed tree - it is run 3 of MAIN-1's three.
- Review [B]: PASS. Open from it, row FIX-256: the steady time still guards the monitor hand-over by wall time, and the new
  reading's cost is unmeasured in the runner. The untested retry for a full thread array stays; the commit says why.
- MAIN-1: whole-reactor runs 2 of 3 (this task's); `ci` 225, 226, 227 green. Owed: run 3 on this commit and its `ci` run.

## 2026-11-15 — FIX-254 stage 1: why a flow time differs between runs — cause measured, in progress

- Context compacted before this entry; the state was re-read from HANDOFF, `git status` and `git log -3`.
- Measured, nothing under `src/` changed (`agent/work/FIX-254-evidence.txt`): the demo's report was wrong in 3 of 210 runs on
  four idle CPUs, 0 of 300 on one CPU or under load (EXECUTED, the verifier); always one "partner down" line, 2700 or 3700 ms.
- The cause, from six method-trace recordings and a counter agent: the worker's WARNING on the retry path is written to
  `System.err` on the flow's virtual thread; the JDK runs that write in a compensated block, and for its length the
  scheduler's mounted count - what the runner's quiet sample reads - leaves the thread out. When the block outlasts the
  1 ms steady time (3.9 ms recorded), the clock steps under a running thread and the retry is computed from the stepped clock.
- Refuted: the monitor hand-over the packet first suspected (no contended monitor enter in any window) and the row's three
  candidates. Recorded in the invoice-async flow too: the fault is the runner's, not the sftp mock's.
- Decided (D-155; stage 2 of the packet is with the test-author): a sample also counts carrier threads in state RUNNABLE.
  A standalone program measured it: they show the thread in every sample the count hides. D-155 names what was not chosen.
- `ci` run 226 (FIX-149) green: 2 in a row. Whole-reactor runs: still 1 of 3; the next ones follow the fix.

## 2026-11-15 — FIX-149 test ports come from outside the kernel's range — done, review PASS

- Context compacted before this entry; the state was re-read from HANDOFF, `git status` and `git log -3`.
- The fault: a probe binds port 0, closes and hands the number on; the kernel may give that number to any bind(0) or connect
  before the node binds it (`ci` run 220). Red first: `PortsTest` did not compile - `agent/work/FIX-149-red.log:170`.
- `dev.rail8.testkit.Ports.free()`: ports 20000-32767 minus the kernel's range, a cursor that wraps, a bind check per candidate.
  The eight `freePort()` helpers and the nine inline probes use it; one `new ServerSocket(0)` is left - the helper's fallback.
- The row's "no flake in 20 runs" is replaced by the construction: twenty runs cannot show the absence of a fault seen twice.
- Found on the way: `Files.readString` of a /proc sysctl file returns a truncated "3"; `readAllLines` reads it whole.
- Evidence (`agent/work/FIX-149-evidence.txt`): whole reactor VERIFIED_PASS, 17 modules, every count as expected, coverage no
  decrease, 0 "Address already in use"; then two small changes the review asked for, testkit 300 green after them - the
  whole reactor NOT_RUN on the committed tree. Review (class A): PASS, then PASS on the delta. Its remarks: row FIX-255.
- MAIN-1: whole-reactor runs green in a row 1 of 3 (the rule is in `agent/work/MAIN-1-ci.md`); `ci` run 225 green, 1 in a
  row. FIX-254 is in progress: stage 1 of `agent/packets/FIX-254.md`, a measurement, is running.

## 2026-11-15 — FIX-201 the side branch's audit, reviewed and corrected — done, review PASS r4

- Context compacted before this entry; the state was re-read from HANDOFF, `git status` and `git log -3`.
- The audit of `634983a..99d8d04` (35 commits) had left no table per commit and its totals were wrong. Rebuilt and reviewed:
  code commits 6 carried, 3 partly, 10 lost; doc commits 1 carried, 2 superseded, 6 lost or partly; the 7 "superseded" judged.
- Round 2 found the document hunks of the code commits judged by nobody. Answer: the branch's net diff per document (39 files
  outside src) - journal, backlog, notes, decisions, architecture, findings, packets, ci - each with its fate, and each of the
  48 rows the branch spawned and the 27 it changed with its fate (`agent/work/FIX-201-audit.md`; it names what nobody checked).
- Rows: corrections on FIX-54, 56, 57, 196, 197, 199, 203-208, 210, 211, 243; FIX-54, 56 and 71 closed (71 through FIX-195); new
  rows FIX-238 to FIX-253 and CONSIDER-25 (owner: the branch's D-107 contradicts D-136). The branch's ids name other things on
  main, so rows cite shas. NOTES: one paragraph restored; the branch's other 275 lines are row FIX-253.
- Review (class B): r1 FAIL in both halves; second pass INCOMPLETE; r2 FAIL (the document hunks); r3 FAIL (one sentence on
  D-056's amendment, the architect's, wrong in two facts); r4 PASS. Hand-backs: `agent/work/FIX-201-audit-review.md`.
- Also here, documents only: `ci` runs 222 and 223 green, 224 failed on a fourth fault - a flow time of 2700 ms for 2400 in
  the cli ITs, new row FIX-254; green runs in a row 0. FIX-149's work in progress is parked as `agent/wip/FIX-149.patch`.

## 2026-11-15 — FIX-234 the pool-wait fixture sends a deploy the pool refused again — done, review PASS

- Context compacted before this entry; the state was re-read from HANDOFF, `git status` and `git log -3`.
- Fault: `ci` run 219 failed in `NodePoolWaitIT`'s fixture, `startDeployed:178`: the deploy right after `Node.start` answered 503,
  "the deploy record was not written: Connection pool reached max wait queue size of 1". Not the product's: the designed answer.
- A cause, staged (test-author, class B): the case's work pool is one connection and one place to wait; the retention's first
  batch, begun by `Node.start` and not awaited, holds the connection; the deploy's own worker claim takes the place; the
  deploy's record is the third and is refused. Staged with `messages` locked by the test before the node starts.
- Change, test only: the fixture sends the deploy again while the answer is that refusal (503 BUSY, D-133), bounded by the
  class's bound; any other answer fails at once with its body. No signal says the start-up work on that pool has ended.
- A refused deploy leaves no record, nothing listed, no listener and no `work` row; its archive and its stopped worker's
  start claim stay - new row FIX-237 asks what that claim does when `work` holds rows of the flow.
- Evidence: the class 12 of 12; three mutants of the fixture killed (verifier). Nothing under src/main changes.
- Review: round 1 FAIL (the row, what a refused deploy leaves, the predicate untested, the red's shape); round 2 PASS.

## 2026-11-15 — FIX-183 a link's READs are served in the order they arrived — done, review PASS

- Fault: `ci` run 218 failed on `PeerProtocolTest.theOwnerServesAtMostPeerReadsOfALinkAtOnce:270` (the second READ served before
  the first; 1 of 7 in CORE-5a2). `PeerServer.Serving` started a virtual thread per READ and took the link's permit inside it:
  the semaphore is fair among threads that asked, and which of two threads asks first is the scheduler's.
- Red first (test-author, class B): two cases, ten writes of 48 READs on one link - with a bound of 1 the answers come one READ
  after the other in arrival order; with 2, no READ begins before all but one of the earlier ended. Red 20 of 20 each.
- Fix (`PeerServer.java` only): one FIFO per link and a count in service under one lock; `received` stays non-blocking; a READ in
  service has its own virtual thread whose `finally` hands the place to the head. The semaphore is gone; `open` counts as before.
- Evidence: runtime 1580 and 3 green; twins of the new cases and of :270, 60 of 60 green; the old order put back, both cases red.
- Review PASS (class B): no READ left waiting, the bound and `open` hold, nothing runs under the lock; the two mutants that only
  the new cases kill were run. A thread that cannot start, and the dialer's own fair semaphore, go to row FIX-236.
- The rest of the old row (the event-loop assertion in `PeerLink.send`, stale line references) is new row FIX-236.

## 2026-11-15 — FIX-195 the late review: FAIL, four findings fixed, PASS — done

- Context compacted before this entry; the tree and the last three commits were re-read first.
- FIX-195 (`BatchingWriter` under D-146) landed unreviewed in a6b12011. The verifier ran what was never run: core 763 green, scoped
  PIT 76 of 77 (the survivor, the count-only constructor's weight `item -> 1L`, is equivalent: the byte bound equals the count bound).
- Review round 1 FAIL (class B), four findings: the refused-cancel case asserted `isComplete()` only and never passed the deadline;
  the landing had dropped a flush assertion that D-146 still demands; `cancelsRefused()` was public API against D-146; a comment in
  `PgIntake` still described the behaviour D-146 removed. All four fixed; no behaviour changed.
- The tests are shown real where PIT is blind: two wrong writers fail the new assertions, four hand mutants of the episode flags die.
- The class Javadoc claimed more than the code does: it now lists what is contained and what still reaches a caller. New row FIX-235
  [C]: a sink that answers null strands its batch in flight; a throw of the caller's weight function leaves `save` by throwing.
- Decided by javap, vertx-core 5.1.10: a listener's Throwable, Error included, never escapes `tryComplete` of a context-less promise.
- Evidence: `agent/work/FIX-195-evidence.txt`. Review round 2 PASS. FIX-205 is no longer blocked.

## 2026-11-15 — FIX-224 the bound on bytes taken while the link is paused is absolute — done, review PASS

- Fault (once, in FIX-214's red run): `PeerReadHeapTest.aReadThatGaveItsHoldBackLeavesTheLinkPausedWhileAnotherReadsBufferIsFull`
  :281, "node-b read 1407 KiB more … after the first read gave its hold back" against a fixed megabyte with no recorded reason.
- Cause shown by a probe (test-author, class A; never committed): the link stays paused at the give-back - `PeerLink.unhold`
  returns while another read holds it, and no bytes reached either read in any of 12 traced runs. The megabyte was relative to
  a sample that is no settled state: in 5 of the 12 runs the count rose after it with no give-back at all, once by 1284 KiB.
- Change, test only: both waits bound the absolute count at `PAUSED_TAKES_MAX = PAUSE_AT` (4 MiB, the second buffer's own
  mark), its reason in one Javadoc. A corrected expectation, not a weaker test: a link that resumes reads on - with the guard
  taken out of `unhold` the case fails at its own assertion at 7104 KiB.
- Evidence: VERIFIED_PASS runtime 1578 and 3 (author; verifier on the tree committed). Review: round 1 FAIL (the bound copied
  the number instead of following `PAUSE_AT`), round 2 FAIL (a Javadoc sentence, the architect's wording, overstated the
  probe), round 3 PASS. The body of commit de39cc99 has one line of 74 characters.

## 2026-11-15 — FIX-231 the teardown drains what writes through the relay before it closes it — done, review PASS

- Fault (once, the whole-reactor run of FIX-229): `PgIntakeHandoffGroupLogIT.aHandOffThatThrowsInACommittedGroupStillAnswersEvery
  Member` errored with `PoolRefused … Connection refused` on the port of the case's own `PgWire` relay.
- Cause shown (scout's reading, then a staged order; test-author, class B): the fixture's, not the product's. The teardown closed
  the relay, then released the Hold step and closed the world; the running item's snapshot write and settle then asked the closed
  relay for a connection, and the store's close (`writer.flush().await()`) threw the refusal on the test thread. The case's claim
  is complete in the body; the product logs the refusals and leaves the item to its lease.
- Red: the body ends with both connections of the relayed pool busy on held frames and the Hold step released - old teardown, the
  case errors in both classes with the sighting's three log lines. Fix, tests only: the teardown releases the held frames, stops
  the worker, releases the Hold step, waits bounded for the worker's close and the store's flush, and only then closes the relay;
  every step runs, the first failure is thrown, nothing is swallowed. The staged order stays at the end of the case.
- Evidence: VERIFIED_PASS the two IT classes alone, 5 tests, three times by the author and twice by the verifier. Review PASS.
  Survey: the same order, latent, in four more IT classes, and an unawaited stop in `PgIntakeHandoffIT`: row FIX-233.

## 2026-11-15 — FIX-227 the second dir-force case holds its own persist's force — done, review PASS

- Fault (FIX-168's survey; never seen failing): `JournalCloseAndDirForceTest.aDirForceAfterAFailedOneFailsItsPersistToo` armed
  holds on "the next force of `segments/` by any thread" after a wait the open's pre-open (`journal-open-2`) was not part of.
- Red first, order forced (test-author, class A): the pre-open let go only after A's hold was armed, old holds - the case fails
  in a bounded timeout; the log names the held "A" force as `FORCE segments on journal-open-2`, and B's hold took A's own force.
- Change, tests only: one package-private fixture `OwnDirForce` serves this case and `JournalDirForceOrderTest` - `Preopen` (the
  open off the test thread, the pre-open held at its file's force, bounded) and `Persisting` (a persist gated on a latch, its hold
  armed with its thread). The forced order stays in the case. Every assertion of both classes stays.
- Evidence: VERIFIED_PASS runtime 1578 and 3, by the author and by the verifier on the tree committed. Review PASS (class A):
  `Preopen` repeats FIX-168's `Two` line for line; no other case of the class holds an any-thread force of `segments/`.

## 2026-11-15 — FIX-225 two more comparisons with the disk wait for the pre-open — done, review PASS

- Fault (never seen by chance; the race of FIX-213 and FIX-222): the journal counts the 49-byte header of the pre-opened next
  segment, and inserts its row, from the pre-open's start - before the file exists. `JournalSpillBoundTest.fillTo` :103 and
  `LargeBodyCrashTest` :166 compared with the disk without waiting for it.
- Red first (test-author, class A): with FIX-222's temporary 500 ms delay before the pre-open's `createFile`, three cases fail -
  `fillTo` twice, 49 bytes short, and "the rows with a file stay".
- Change, tests only: a fixture `SegmentFilesSeen` wraps the journal's files, notes every segment file created and every force of
  its directory; `awaitSegment(n)` waits, bounded, on a condition, until segment n's file was created and none created is without
  its directory force - by identity, no sleep. `fillTo` waits for the segment after its last record's, the restart for the one
  after its first. Every assertion and exact equality stays; `ReplicaStartTest` untouched.
- Evidence: VERIFIED_PASS the same delay with the new tests, 1578 tests, no failure (author; verifier), and the clean tree.
  Review PASS (class A); two robustness points taken (only files under `segments/` count; the package's bound). Follow-up: FIX-232.

## 2026-11-15 — FIX-226 DATA, RECEIPT and REFUSE have a derivation outside the code — done, review PASS

- Gap: `ReplFramesTest` holds seven golden frames; the tracked script `agent/work/BRAND-1-C-golden.py` recomputed four. DATA,
  RECEIPT and REFUSE came from a CORE-5b1 script nobody kept: a known answer that cannot be told from a copy of the code's output.
- Change (test-author, class A, test material only): the script writes the three frames out with `struct` from the layout the test
  documents and the inputs it names (POS 49, state 2 = WRITTEN, reason 1 = FULL, DETAIL). The Java encoder was not read.
- Shown: the three lines equal the constants at `ReplFramesTest` :48, :50, :51 byte for byte; both tracked outputs gain three
  lines and change none - the frames carry neither the name nor the magic. Kill by the script: one input changed per frame,
  three differing lines. The comments of `ReplFramesTest` and `ReplWire` (a test file) cite the script for all seven.
- Evidence: VERIFIED_PASS the script against its outputs and the test's constants, the runtime module 1578 and 3, by the author
  and by the verifier. Review PASS (class A): every byte is fixed by the documents; a wrong encoder would disagree.

## 2026-11-15 — FIX-229 a send of the recording component takes its hold before it is seen — done, review PASS

- Fault (`ci` run 213, Postgres 17.11 job): `DeploymentsRedeployTest.everyStartWaitsForEveryStop:558 c's held message: not within
  PT10S`; the first flake of row FIX-38 (`DeploymentsTest` :869-888) has the same shape.
- Cause shown (scout's reading, then a staged demo on the old order): the testkit's `RecordingComponent.send` recorded the send
  and counted `awaitSent` down before it took its hold; a test doing hold, send, awaitSent, hold, send had its holds swapped —
  "the latch armed before a's send releases a; it released c". No product path drops the answer.
- Red first (test-author, class B): `RecordingComponentHoldOrderTest`, 7 cases, staged through the package-private hook
  `afterSendVisible`. Fix (implementer): the take and the record are one synchronized step, `holdNext` arms under the same lock;
  nothing of a caller runs under it; public surface unchanged. The old order put back fails five cases.
- Evidence: VERIFIED_PASS testkit 286, runtime 1578 and 3. Whole reactor (verifier): 4629 tests, coverage no decrease, EXECUTED_FAIL by
  one IT error not of this change - `PgIntakeHandoffGroupLogIT`, a refused connect to the case's own relay: row FIX-231. The count
  of green whole-reactor runs stays 0. `ci` run 214 (FIX-168) is green. Context compacted before this entry; state re-read.
- Not pinned: `holdNext`'s lock (a non-thread-safe deque; only a stress race could show it). Survey: the same window in other
  doubles (`Doors` gates, `Ledger.delete`, `LeaseLog.hold`, the replay test's hold step): row FIX-230. Review PASS (class B).

## 2026-11-15 — FIX-219 the harness looks again for a late query-log row — done, review PASS

- Fault (once, whole-reactor run of BRAND-1 C): `ClickHouseCompressionMainIT` saw `readsLogged` 19 for 20 timed reads.
- Cause shown by probes (test-author; never committed): the docs do not say when a `QueryFinish` row is queued; 200 light cells
  and 961 real cells without load never came short; under load on both sides 1 of 44 cells counted 19 on the first look and 20
  after a second `SYSTEM FLUSH LOGS` — the last read's row was stamped 1.1 ms before the flush (agent/work/FIX-219-cause.md).
- Change: `QueryLogCount.await` looks (flush, count) until the count reaches the expected number, at most `MAX_LOOKS` = 3 times,
  no sleep, no clock; the harness counts its timed reads and its exception rows through it and reports the reads' looks in one
  new JSON field, `readsLoggedLooks`, so a late row stays visible. The IT's `readsLogged == 20` is untouched.
- Tests, red first, in three steps: the seam (11 cases); the harness's use of it, after the implementer found the expected
  number unpinned (7); the exception count, after review round 1 FAILED on it (8). Round 2 PASS (class A).
- Evidence: VERIFIED_PASS bench 239 unit tests and the IT alone three times, by the implementer and by the verifier; three
  mutants of the expected numbers killed. Inserts and optimize stay plain: at least two later flushes behind them.

## 2026-11-15 — FIX-168 a held force is its persist's own, not the first by anyone — done, review PASS

- Context compacted before this entry; the state was re-read from HANDOFF, `git status` and `git log -3`.
- Fault (three sightings, whole-module runs only): `JournalDirForceOrderTest…:98` got `JournalFailedException` for `IOException`.
- Cause shown (test-author, class B): a first open forces `segments/` twice on the opening thread, so the fixture's wait for two
  returned forces did not cover the open's own pre-open (`journal-open-2`). Its late force of `segments/` was the first after A's
  hold was armed; the hold took any thread's force and the case failed that one. Red with the order forced: the held op is
  `FORCE segments on journal-open-2` (FIX-168-red.log :43923), the reported message, runtime 1576 tests, 1 failure.
- Change (test only, +51/-7): `Two.Persisting` starts each persist gated on a latch and arms its hold by the persisting thread;
  the fixture keeps the bad order forced and opens the journal off the test thread, every wait bounded. No production change.
- Evidence: VERIFIED_PASS the final tree (verifier): the class 2, runtime 1578 and 3. Pinned to one CPU the class passes and four
  peer flow-control cases fail, in both such runs (EXECUTED_FAIL): row FIX-228. Review: round 1 FAIL (a hang), round 2 PASS.
- Survey: `JournalCloseAndDirForceTest` :168-176 has the same fault: row FIX-227. `ci` run 213 red on a new one: row FIX-229.
- Also: FIX-219's cause shown by a probe under load (19, then 20); its seam is in work. Packets FIX-224, 225, 226 written.

## 2026-11-15 — FIX-218 the node file's magic and the retention lock key are pinned — done, review PASS

- Gap (BRAND-1 C): the rename changed two persisted values and no unit test noticed — the identity file's magic `RJN1` and the
  retention advisory-lock key `Retention.LOCK_KEY`; `RetentionIT` took the rival's lock by the constant itself.
- Tests (test-author, test only): `JournalSegmentRowsTest` reads the `NODE` file a first start wrote and compares its first four
  bytes with `52 4A 4E 31`; new `RetentionLockKeyTest` compares the key with `0x7261696C38000000L`; `RetentionIT`'s rival takes
  the lock by that literal, so the IT fails when production takes another key (shown by construction, not by a run).
- The kill, since the values are right and the cases green from the start: one byte of each constant changed (temporary) —
  EXECUTED_FAIL exactly the two new cases, each with its message (FIX-218-kill.log); production restored.
- Comments: `ReplFramesTest`, `ReplWire`, `PeerKnownAnswerTest` cite what is in the tree. Found on the way: the golden script
  reproduces HEADER, OPEN, FILE_CRC, SEAL only — DATA, RECEIPT, REFUSE have no derivation in the tree: new row FIX-226.
- Evidence: VERIFIED_PASS runtime 1578 (1576 + 2) and RetentionIT 32, by the author and by the verifier. Review PASS (class A).

## 2026-11-15 — FIX-222 the other replica start test waits for the pre-open — done, review PASS

- Fault (not seen failing): `ReplicaStartTest…:145` compared `bytes()` with the disk right after the start; the journal counts the
  pre-opened segment's 49-byte header before its file exists — the race FIX-213 removed from the sibling case.
- Change (test-author, test only, +13/-5): the case starts through `PreopenSeen` and waits on its latch; the equality stays exact.
  The latch is tied to the pre-open by identity (the second segment file created, then its directory opened for the force).
- Red made by a temporary `Thread.sleep(500)` before the pre-open's `createFile` (production, never committed, FIX-222-demo.diff):
  the old comparison fails "expected: <8049> but was: <8098>" (49 apart); the new case passes under the same delay.
- Evidence: VERIFIED_PASS the clean tree three times (author) and once more with FIX-218's change beside it (verifier):
  ReplicaStartTest 2, the module without a failure. Review PASS (class A); the identity rule itself is not pinned by a case.
- The same delay failed `JournalSpillBoundTest` (two cases) and `LargeBodyCrashTest:166` — the same race, never seen without the
  delay: new row FIX-225.

## 2026-11-15 — FIX-221 a contract case tells two deliveries by their bodies, not their order — done, review PASS; closes FIX-170

- Fault: testkit `ComponentContractSelfTest.eachBrokenPropertyFailsExactlyItsCases:337` — the variant "acknowledged before the
  handler answered" failed a tenth case, `aSendCarriesTheMessageIdToThePartner`: once in four runs here, and in the 17.11 job
  of `ci` runs 209 and 211.
- Cause: the case read `seen.get(0)` as the plain send and `seen.get(1)` as the bounded one. Such a partner runs the two handlers
  on threads of their own; which records first is a race.
- Red first (test-author): a variant that holds the first handler until the second has recorded — the new self-test case fails
  with exactly that tenth case (FIX-221-red.log: 279 run, 1 failure).
- Change: the contract case finds each delivery by its body hash and asserts its ids as before; where the bodies did not arrive
  as sent it compares the two id lists without their order. Every expected set unchanged; no other case has the fault (survey).
- Evidence: VERIFIED_PASS testkit 279, seven runs (author 1, verifier 3, and 3 pinned to one CPU). Whole reactor -Pit: 4594 tests,
  every module at its count, ONE failure — `JournalDirForceOrderTest:98` (row FIX-168; a runtime unit test, no contract suite).
- Review PASS (class A). MAIN-1's count of green whole-reactor runs restarts; FIX-168 is next.

## 2026-11-15 — FIX-214 the frame relay names the side whose socket closed — done, review PASS

- Fault: `PeerProtocolTest…:767`, "expected: <DIALER> but was: <LISTENER>" (BASE-1 run 2, `ci` run 193) — a test fixture's.
  `FrameRelay`'s pump named the side it reads from for every IOException, also for a failed write to the other side; two pumps
  see one close and the first answer stays, so the answer was a race.
- Red first (test-author): new `FrameRelayTest`, 13 cases on plain sockets. An 8 MB frame parks the pump toward a peer that does
  not read, the closer closes, only the failed write can report: EXECUTED_FAIL with the same message (FIX-214-red.log).
- Change (the fixture, 13 lines): a failed write names the side written to, RELAY when the relay refused the frame; a failed read
  names the side read from, as before. `PeerProtocolTest` is not edited; nothing under src/main.
- Review round 1 FAIL: no case pinned "the first answer stays". Round 2: the cut and the write case wait for both pumps to end;
  with the guard dropped they fail, and so do PeerProtocolTest:606 and PeerLinkTest:240 (FIX-214-mutant.log). PASS (class A).
- Evidence: VERIFIED_PASS four runs of the module by the verifier (three before round 2, one after): FrameRelayTest 13,
  PeerProtocolTest 37, runtime 1576 = the baseline's 1563 + 13.
- Narrowed: an interrupt that lands in a pump's write now names the side written to; nothing interrupts a pump mid-write.
- New row FIX-224: `PeerReadHeapTest:281` failed once in the red run — a fixed 1 MiB bound on buffered bytes, not this change.

## 2026-11-15 — FIX-216 the journal-rate check compares whole bytes — done, review PASS

- Context compacted before this entry; state re-read from HANDOFF, `git status`, `git log -3` (HEAD 627afa86, clean but FIX-214's WIP).
- Fault (BRAND-1 A's verification, once): "the journal delta 2538251.9999999995 B holds the 2538252 B of records" — the report's rate
  is a whole byte count over the elapsed seconds; the test multiplied it back in doubles and compared the product with a whole number.
- Change (test-author, test only, +3/-1): `NodeBenchMainWarmupIT` rounds the product with `Math.round` to whole bytes; the assertion
  is unchanged. The rounding error is at most ~6e-8 B for 100 MB over minutes (reviewer), so no shortfall of a byte can hide.
- Red shown: with `recorded + 1` in the comparison the case fails, "the journal delta 2538252 B holds the 2538252 B" (FIX-216-red.log).
- Evidence: the IT alone, 5 tests, 0 failures — before, after (author) and once more by the verifier (FIX-216-verify.log). Maven
  exits 1 in each on the bench module's coverage floor (one IT alone); the whole-reactor run is the exit-0 check, still owed here.
- Review PASS (class A). Done in a second worktree beside FIX-214; the diff applied to the main tree byte for byte.
- `ci` run 209 (BRAND-5): the 17.11 job red after 68 s on testkit `ComponentContractSelfTest:337` — row FIX-221, first seen on a
  runner; the 18.6 job was still running. `mapping-ci` run 2 green; run 208's `stack` job green (agent/work/MAIN-1-ci.md).

## 2026-11-15 — BRAND-5 the namespace is `dev.rail8` — done, review PASS

- The owner's answer to ISSUE-7 carried out (D-154): groupId, package root, the mapping's modules, every operator-visible name. No alias (D-148).
- Red first: 13 new cases of `ci/test_name_guard.py` (test-author; 12 red, agent/work/BRAND-5-guard-red.log), then the extended
  guard on the tree: 9614 findings. Deviation from "existing cases untouched": one old case's clean-path fixture is now
  `src/dev/rail8/` — the old path is no longer clean data; its assertions are unchanged.
- Implementer: `ci/name_guard.py` (one pattern); `agent/tools/rail8_rename.py namespace` (51 directories by `git mv`, 1398 files,
  8285 lines, idempotent); `spotless:apply` in the mapping (imports of 5 files); allow-list (one moved path, two literals for
  ARCHITECTURE's changelog row 3.24). No constant or pin depended on the namespace. Architect: ARCHITECTURE 3.27, pending packets.
- Pure rename shown by agent/work/BRAND-5-pure.py: 1399 changed files compared; the two that differ are an agent's memory note
  and the CI notes; 5 hold the same lines in another order (the formatter); none added or removed.
- Evidence (verifier): VERIFIED_PASS platform reactor `-Pit`, 4580 tests, counts equal to the baseline, NO failure in any module
  (agent/work/BRAND-5-verify-platform-counts.txt); mapping 17 822, equal to its base; guard clean, 5891 files; coverage current.
- Review PASS (class B); letter case and a word character after `rail8` are not pinned. `ci` run 208 (FIX-215's commit): the first
  green build of main on a runner since 2026-11-05 (agent/work/MAIN-1-ci.md).

## 2026-11-15 — FIX-215 the takeover check allows a re-run and bounds it — done, review PASS; closes FIX-184

- Red first: `LedgerIT.aKilledNodeLosesNoAckedMessage` as it was — EXECUTED_FAIL "only what was unfinished at the kill is finished
  after it: 62 vs 61" (agent/work/FIX-215-red-2.log); the fault of BASE-1 run 3 (66 vs 60) and of FIX-184.
- Cause: a test fault. A run commits its final-step row (the trail's close) before its work row is deleted, in a separate store
  call; a node killed between the two leaves the item queued and the takeover runs it again — at-least-once (D-135, D-140).
- Change (test-author, test only): the one line becomes (a) every unfinished id is finished after the kill, (b) node 2 finishes
  only what had a work row at the kill, (c) what it finishes beyond the unfinished ids is bounded by the re-runs counted from the
  stored rows; each prints the four numbers and up to 20 ids. The no-loss assertions are unchanged. No production change.
- Evidence: VERIFIED_PASS 10 runs of the class in a row — author 5 (FIX-215-green-1..5.log), verifier 5 (FIX-215-verify-1..5.log);
  a re-run in 3 of the 10 (1, 6 and 2 ids). Maven exits 1 in each: the bench module's coverage floor, one IT run alone.
- Review PASS (class B). FIX-184 closed with its acceptance amended: a re-run of an id whose work row outlived its final-step row
  is no red; (c) is the check for the kill-boundary race it suspected.
- Measured, not a fault: a kill re-ran up to six of some sixty in-flight messages — the at-least-once window. Row CONSIDER-24.

## 2026-11-15 — FIX-212 the rerun case expects the six rows the design records — done, review PASS

- Red first: `StepBodiesIT.aRerunOfAStampingStepKeeps…` as it was — EXECUTED_FAIL "not within 20 s: fixture: 5 rows of sb-rerun"
  (agent/work/FIX-212-red.log); the same line fails both build jobs of `ci` run 203. Rows observed: six (FIX-212-rows.txt).
- Cause: a miscount in the expectation written under FIX-175, not a race. Attempt 1's crash-first row, outcome failed, is recorded
  too (Recorder, D-111; each attempt keeps its rows, D-140). `poll()` returns after the trail's close has committed every row, so a
  wait for exactly five could never be met — the packet's "holds for an instant" was wrong; the reviewer corrected it.
- Change (test-author, test only): one read after the writer's flush; the list of step/attempt/outcome equals the six rows;
  `until` takes a message supplier. No production change.
- Evidence: VERIFIED_PASS 5 runs by the author (FIX-212-it-1..5.log), 2 by the verifier (FIX-212-verify-1/2.log), 8 tests each.
- Review PASS (class A). The row's second clause — no test decides on machine tempo without a recorded reason — is row FIX-223.
- Also read: `ci` run 203 (FIX-213 holds on the runner; only FIX-212 red) and `mapping-ci` run 1, four jobs green (MAP-6, part);
  packet agent/packets/FIX-222.md added.

## 2026-11-15 — ISSUE-7 answered by the owner: the namespace is `dev.rail8` — recorded, BRAND-5 added

- The owner, in the session: "rail8.dev, not io". D-154: `dev.rail8` replaces `io.rail8` — package root, Maven groupId, the mapping's
  module names — in both areas; the draft mark of D-149 and D-151 on the coordinates ends. Registering the domain stays the owner's.
- Row BRAND-5 [B] does it with the rename tool, after the two test fixes in flight (FIX-212, FIX-215). Measured: 1417 tracked files
  carry `io.rail8` (platform and bench 977, mapping 414, elsewhere 26); 1334 files lie under `io/rail8/` directories.
- Correction to the message of commit 6779670a: the reviewed diff of BRAND-3 step 3 is `agent/work/BRAND-3-s3-full.diff`.
- Context compacted before this entry; `git status` and `git log -3` re-read. Documents only; no task closed, no review.

## 2026-11-15 — BRAND-3 step 3 one CI, one set of rules, one notice for the mapping engine — done, review PASS

- Deviation: F-54 (major) — ARCHITECTURE 3.26, section 12 says whose rules bind which area (D-153, a draft for the owner's review).
- Files (implementer): the engine's three workflows are `.github/workflows/mapping-{ci,bench,nightly}.yml` (steps run in `mapping/`
  with `../mvnw`; jobs named `mapping · …`; the trailers job replaced by the root DCO job, its script tests kept in `backend`);
  `mapping/CLAUDE.md` and `mapping/docs/WORKFLOW.md` keep the engine's technical rules and checks; the old process files are under
  `mapping/docs/archive/` by `git mv`; NOTICE merged; the platform's documented commands name `-f platform/pom.xml`.
- Documents (architect): F-54, D-153, section 12, ISSUE-9 (licences incl. GPL-2.0 with the Classpath Exception at test scope; the
  rule scope) with `agent/patches/BRAND-3-steering.patch` for the owner, rows MAP-1..MAP-8, OPS-4 and OPS-6 wait for MAP-1, NOTES, README.
- Opposed before the decision (D-137), six blocking points taken: scope by exclusion, the standstill until MAP-1, the licence wording.
- VERIFIED_PASS (verifier, staged tree): mapping suite 17 822 tests 0/0/0, every module = base; contract unchanged by the build; 14
  script tests; check-lf; name guard; 46 guard tests; root and platform validate; workflows parse (`BRAND-3-s3-verify-counts.txt`).
  NOT_RUN: the ported workflows on a runner (MAP-6); the platform reactor (only comments, documents and two script lines changed).
- Review r1 FAIL (ISSUE-9 said the patch states more than it does), r2 PASS. `ci` run 202 read: FIX-213, FIX-212 (`MAIN-1-ci.md`).
- BRAND-3 is done. Next: MAIN-1 — FIX-212; read `ci` run 203 and the first `mapping-ci` run.

## 2026-11-15 — FIX-213 the replica start test waits for the pre-opened segment's file — done, review PASS

- Context compacted before this entry; re-verified with `git status`, `git log -3` and HANDOFF.
- Cause (not the disk's usable space, as the row guessed): `Journal` counts the next segment's 49-byte header from the pre-open's
  start, before the file exists; the test compared `bytes()` with the files on disk right after the start. `ci` run 201 stopped on it.
- Test only (`ReplicaStartTest`, +38/−1, the test-author): a `JournalFiles` wrapper counts down a latch at the directory force that
  follows the second segment file's creation; the test waits on it, then compares. Exact equality kept; no production change.
- Red: the old comparison with a delay injected into the pre-open, EXECUTED_FAIL `expected: <3465> but was: <3514>`; the new test
  passes with the same delay. VERIFIED_PASS (verifier, worktree at 29379f9c + the patch): runtime module with upstream, runs 2-4
  1563 tests 0 failures; run 1 EXECUTED_FAIL on `JournalDirForceOrderTest:98` only (FIX-168). Lines: `agent/work/FIX-213-evidence.txt`.
- Review PASS (class A): the latch fires after the header is written and forced; a missing file fails at 30 s with its own message.
- New rows: FIX-221 (testkit `ComponentContractSelfTest:337` flaked once in four runs), FIX-222 (the same race at
  `ReplicaStartTest:145`, the reviewer's note). FIX-168 seen again. The runner's proof is the next `ci` run (MAIN-1).
- Next: BRAND-3 step 3 (in the tree, uncommitted: verifier, reviewer, commit), then FIX-212.

## 2026-11-15 — BRAND-3 step 2 the mapping engine takes the Rail8 names and joins the root reactor — in progress

- Context compacted before this step; re-verified with `git status`, `git log -3` and HANDOFF.
- By the tool (`--root mapping`): identifiers 23 moves, 449 files, 2198 lines; stored and wire names 314 files, 786 lines, 18 entries
  checked at the import commit. By hand (`agent/work/BRAND-3-s2-hand.diff`): `.mvn/` and `mvnw*` to the root, the root pom lists
  `mapping`, `@SuppressWarnings("module")` on eleven module declarations, 14 ADR amendment lines, `../mvnw` in documents and gates.
- Pins derived outside the engine, each first reproduced from the old bytes: 11 fingerprints, 6 ETags (`agent/work/BRAND-3-s2-canon/`);
  20 load-error columns from the input text (`BRAND-3-s2-columns.txt`). Kept as recorded: the CPI package name, the PO export zip.
- The platform does not see the mapping: `ci/coverage_check.py` skips `mapping/` (red first, 5 mutants killed); `ci.yml` builds with
  `-f platform/pom.xml`. Rulings: `agent/packets/BRAND-3.md` "Rulings on step 2's hand-back" (and those for step 3).
- VERIFIED_PASS (verifier): mapping `./mvnw -f mapping/pom.xml -Pbench clean verify` 17 822 tests 0/0/0, every module = base, twice
  (`BRAND-3-s2-verify-counts.txt`, `-verify2-mapping.log`); ui 674 + 254 + 17; platform reactor 4580 tests, counts = BASE-1, red:
  StepBodiesIT (FIX-212), LedgerIT (FIX-215), both in the base red set; coverage baseline current; guards green; ledger_check 9 known.
- Review r1 FAIL (3 ADRs without the amendment line, 2 stale wrapper comments, the guard's new case too weak), r2 PASS.
- Next: BRAND-3 step 3 (workflows, `mapping/CLAUDE.md`, archives, NOTICE; ISSUE-9 and the owner's patch; section 12; MAP rows).

## 2026-11-15 — FIX-217 the sample pipeline test no longer bounds wall-clock time — done, review PASS

- Fault: `SamplePipelineTest` required the run to take under 100 ms of wall-clock time; GitHub's runners took 162-337 ms, so `ci` has
  stopped in testkit since 2026-11-05 (`agent/work/MAIN-1-ci.md`). Not red here: the verdict depended on the machine.
- Change (test-author, the one test file): the wall-clock measurement is gone, every behavioural assertion stays; instead the run advanced
  no fake time (`clock().instant()` is `FakeRuntime.T0`) and left no timer pending (`timer().pending()` is 0). A wrong expectation.
- The new assertions fail on two mutations of the test body (the fake clock advanced by 1 ms; a timer scheduled) - the test-author's run.
- VERIFIED_PASS (verifier): `mvn -B -ntp -q -pl platform/testkit -am verify` exit 0, `agent/work/FIX-217-verify.log`; testkit 278 tests,
  0 failures, 0 errors, 0 skipped - the count unchanged.
- NOT_RUN: the `ci` workflow - the row's second half (the build job gets past testkit on the runner) is read after this push.
- Review PASS. Its note: the assertions do not catch a step that sleeps or reads the system clock; the test claims no more than it checks.
- Next: BRAND-3 step 2 (implementer). MAIN-1 goes on after it with FIX-215 (LedgerIT).

## 2026-11-15 — BRAND-3 step 1 the mapping engine imported with its history under `mapping/` — in progress

- `git merge --allow-unrelated-histories` of the clone's `rail8-import` (cf970caf: the mapping's `main` plus one commit that moves every
  file under `mapping/`), made on `main`; 1155 commits keep their SHAs. No conflict.
- The merge commit adds one line beyond the two trees, `mapping/** | * | ...` in `ci/name-allowlist.txt` (3491 findings without it; an
  entry added earlier would have been an unused entry), and these ledgers. Step 2 replaces the line by the mapping's history files.
- VERIFIED_PASS, observed by the architect before the commit: `git diff --quiet MERGE_HEAD -- mapping` (byte-identical to cf970caf);
  outside `mapping/` only the allow-list, PROGRESS, HANDOFF, BACKLOG and the packet differ from the first parent; `git rev-list
  --first-parent <before>..HEAD` lists the merge commit alone, signed; the name guard clean on 5829 tracked files, its 19 tests pass.
- Nothing builds `mapping/` yet: the root reactor lists `platform` only; the mapping's workflows lie inert under `mapping/.github/`.
- NOT_RUN: Maven (no source outside `mapping/` changed); the `ci` workflow on the push.
- Next: step 2 - the implementer renames inside `mapping/` with the BRAND-1 tool and makes the root reactor build it (packet, rulings).

## 2026-11-15 — OPS-1 ARCHITECTURE and the living documents follow the operations brief — done, review PASS. Deviation: F-38

- Designer, the advisor as opponent (13 changes, all taken), the architect's twelve decisions (`agent/work/OPS-1-decided.md`, D-152).
- ARCHITECTURE 3.25, thirteen changelog rows: each section states what is built and, in the brief's words, what is to be ("To be (OPS-n,
  pending)"). §8 "Monitoring: log records, metrics, alerts", nine sub-headings; §9 gains Teams and permissions, Agents, Callers and the
  developer portal; numbers unchanged; no id cited in the body disappeared (137 before, 146 after).
- F-38 (major, 14 points), each changed guardrail its own point: the write path's drop-and-count, the Phase 3 gate clause (the owner's
  loss contract, `draft — review at hand-over`), Prometheus/Alertmanager, the tenant level, masking before a model.
- ISSUE-8 for the owner: 16 [inferred] / to-verify items, 4 places where the two briefs meet, 7 open questions, each with its default;
  line D: `git apply agent/patches/OPS-1-steering.patch` (CLAUDE.md, two agent definitions) - no session applies it.
- Also AGENTS, WORKFLOW, HANDOVER, README, GATE, PROTOTYPE-VS-ARCHITECTURE, CORE-REWORK (two notes), NOTES, the backlog's header, a closing
  pointer in this file. Rows: OPS-12, FIX-220 new; P2-S1, P2-S2 superseded. Evidence: `agent/work/OPS-1-coverage.md`, `OPS-1.diff`.
- Review in two parts, each r1 FAIL then r2 PASS - wording only: a built claim, a marker the brief lacks, changelog rows against F-38.
- Next: the BRAND-3 import merge.

## 2026-11-15 — BRAND-3 step 1 the DCO job lists first-parent; D-151 — in progress, review PASS (r3)

- Context compacted before this entry; state re-verified with `git status` and `git log -3` (BRAND-4, e1d3dfd, then pushed).
- Step 0, measured earlier in the mapping's clone (`agent/work/BRAND-3-base-counts.txt`): 17 822 tests, none failing or skipped;
  its scripts and its ui green.
- Change: `.github/workflows/ci.yml`, job `dco`: `git rev-list --first-parent "$range"`. D-151 (the mapping's names, pins, history,
  findings numbering, one process). D-150 amended. `agent/packets/BRAND-3.md`, `agent/packets/FIX-217.md`.
- VERIFIED_PASS, observed by the architect in a scratch clone (`agent/work/BRAND-3-dco-dryrun.txt`): the import merge has no conflict;
  the old listing gives 1156 commits, 131 without the trailer; first-parent gives one, the merge commit. The name guard on the merged
  tree: 3491 findings, clean with one `mapping/**` line - the merge commit adds that line.
- NOT_RUN: the workflow job itself (it runs on the push).
- Accepted limit: what a merge brings in by a later parent is not checked, whatever its origin (D-150 amended; the packet).
- Review r1 FAIL (the comment and D-150 claimed less than the loop skips; D-151 settled a namespace D-149 marks draft); r2 FAIL (D-150's
  licence sentence against the packet's new ruling: the root CLAUDE.md is the owner's to change); both fixed; r3 PASS.
- Next: the import merge on `main` (packet step 1.2-1.3), then step 2 (implementer).

## 2026-11-15 — BRAND-4 the old name does not come back: a CI guard — done, review PASS (r2)

- Test first: `ci/test_name_guard.py` (19 methods; red observed with no guard, `agent/work/BRAND-4-red.log`) — tracked files only, any
  letter case, a path that holds the name, a file that is not UTF-8, `**` against `*`, literal entries with longest-literal crediting, a
  malformed entry (exit 2), an unused entry as a finding, the clean run's file count, the real tree with the real allow-list.
- Change: `ci/name_guard.py` (stdlib, exit 0/1/2), `ci/name-allowlist.txt` (60 entries: 19 whole files or globs — history, the applied
  migrations, the rename's own files — and 41 literals, 31 of them tokens of ARCHITECTURE's changelog rows), one step in `ci.yml`'s build job.
- Rulings (packet): the form of a finding; an occurrence is credited to the longest literal; the owner's cloud environment name and the
  brief's mention of the mapping repository are allowed by literal, not renamed.
- VERIFIED_PASS, observed by the architect with the files staged: `python3 ci/name_guard.py` — clean, 1541 tracked files; `python3 -m
  unittest ci.test_name_guard` — 19 tests. A planted occurrence in a scratch clone: exit 1, a content and a path finding. Hand mutants
  killed; the shortest-literal one only by the test added after it was found. NOT_RUN: the workflow step itself (it runs on the push).
- Review r1 FAIL (four behaviours without a test; a tracked symbolic link followed to its target), fixed red-first; r2 PASS.
- Next: BRAND-3 — the DCO job lists first-parent, D-151 and the packet in the tree, then the import merge. OPS-1's fix round is running.

## 2026-11-15 — BRAND-1 C the persisted and wire names become Rail8 — done, review PASS (r4); Deviation: F-37

- Context compacted before this entry; state re-verified with `git status` and `git log -3`. Commit C of three (D-149), the rename's last.
- By `rail8_rename.py wire` (137 entries): 105 files, 407 lines — KDF and digest labels, the journal's magics (`RJS1`, `RJSE`, `RJN1`), the
  retention lock key, schema URNs, the NOTIFY channel, WAL and Valkey prefixes, `application_name`, `X-Rail8-Actor`, JFR names, NodeConfig's
  defaults, the image, both compose files, CI and bench defaults. By hand, 30 lines: the 16 known-answer constants of PeerKnownAnswerTest
  and ReplFramesTest, from `agent/work/BRAND-1-C-golden.py` (stdlib only; it first reproduces the old 16), run by the architect too.
- No alias, no fallback, no migration (D-148, amended): no rolling upgrade across this commit; data written before it is not read; a node on
  the defaults starts without its earlier state; a compose stack from before it needs `down -v --remove-orphans` once (README, QUICKSTART).
- Whole reactor in BASE-1's mode (`agent/work/BRAND-1-C-verify.log`): 4580 tests, none skipped, every module's count equal to BASE-1;
  ArchUnit 48. EXECUTED_FAIL 2: StepBodiesIT (FIX-212, red set) and ClickHouseCompressionMainIT — new, 19 of 20 reads logged in one cell,
  untouched by C, VERIFIED_PASS three times alone (`agent/work/BRAND-1-C-ch-rerun-*.log`) → FIX-219. VERIFIED_PASS: `--check-baseline`,
  the guards' tests, `deploy/postgres` untouched, the tool's second run 0 changes and 0 stale.
- Review r1 FAIL (reused `pg-data` volume; the break stated nowhere; golden script run by its author only), r2 FAIL (four inaccuracies in
  the architect's texts), r3 FAIL (a wrong round number in a draft), fixed; r4 PASS. `translate` removed from the tool. New: FIX-218, FIX-219.

## 2026-11-15 — BRAND-1 B the identifiers become Rail8 — done, review PASS; Deviation: F-37

- Commit B of three (D-149): groupId `io.rail8`, artifacts `rail8-*`, the package root `io.rail8`, build properties `rail8.*`, environment
  keys `RAIL8_*`, the CLI `bin/rail8` with its `rail8: ` prefix, thread and temp-file prefixes, the product's name in text.
- By `agent/tools/rail8_rename.py identifiers`: 29 `git mv`, 1042 files, 8609 lines; one hand edit by the implementer (a memory note) and
  four by the architect (README and ARCHITECTURE no longer call Rail8 a working name; changelog 3.24). A second run changes nothing.
- Left for C on purpose: 321 lines of persisted and wire names in 100 files (`agent/work/BRAND-1-B-report.txt`) and 69 fixture lines of
  `ci/test_coverage_check.py`. The architect's eight rulings on what is B and what is C are in `agent/packets/BRAND-1.md`.
- Whole reactor in BASE-1's mode, `~/.m2/repository/io/flottila` moved away first so an unrenamed coordinate could not resolve
  (`agent/work/BRAND-1-B-verify.log`): 4580 tests, none skipped, every module's count equal to BASE-1; ArchUnit 48. EXECUTED_FAIL 1:
  StepBodiesIT (FIX-212, red set). VERIFIED_PASS: `coverage_check.py --check-baseline`, the guards' tests, `deploy/postgres` untouched.
- Review PASS: no wire name renamed early, tests are renames only, the ArchUnit rules change only their package root.
- Found on the way: `ci` has been red on `main` since 2026-11-02 — first an unsigned commit, since 11-05 a 100 ms wall-clock bound in
  testkit's SamplePipelineTest that the runners miss (`agent/work/MAIN-1-ci.md`). New row FIX-217 under MAIN-1.
- Next: C (wire names, golden values recomputed outside the code), then BRAND-4. The mapping's inventory: `agent/work/BRAND-3-scout.md`.

## 2026-11-15 — BRAND-1 A the directories move under platform/ — done, review PASS (r3); Deviation: F-37

- Context compacted twice in this task; the state was re-read from the tree, `git status` and `git log -3` each time.
- Commit A of three (D-149), no identifier changes: 1184 renames by `git mv` (1146 untouched), 60 files edited for a path the move broke.
  Modules in `platform/`, the bench module in `bench/`, `examples/demo`, prompts and cloud files under `agent/`, the old root journal in
  `docs/archive/`. Parent pom `platform/pom.xml`; the root pom is a thin aggregator (`flottila-all` until B).
- By `agent/tools/rail8_rename.py moves` plus hand edits. A container restart cut the implementer before its hand-back; A was judged from the tree.
- Others' tests edited, each only for a path the move broke: 25 `Path.of("..")` sites; `PostgresImageSourcesTest` (`moduleOf` helper);
  `BuildHygieneTest` (depth 5, skips `mapping/` by name, D-149); `SimVerdictTest` with `Verdict` (`-pl :flottila-sim`).
- Whole reactor in BASE-1's mode (`agent/work/BRAND-1-A-verify.log`): per-module counts identical to BASE-1 (4580, none skipped), ArchUnit 48.
  EXECUTED_FAIL 2: StepBodiesIT (FIX-212, known), bench `NodeBenchMainWarmupIT` (a double against a whole byte count, not the move; FIX-216).
  VERIFIED_PASS: `coverage_check.py --check-baseline`, the guards' tests, `git diff -- deploy/postgres` empty; after the fix sim 61, archtest 48.
- Review r1 FAIL (sim's replay command named a directory that is gone), r2 FAIL (§12 described `mapping/` as present), r3 PASS.
- F-37: ARCHITECTURE §12 to the new layout, changelog 3.23. Corrected: the FIX-196 row and HANDOFF cited a parked diff that never existed.
- Next: B (identifiers), prepared but not verified, parked as `agent/work/BRAND-1-B-*.patch`; then C and BRAND-4.

## 2026-11-15 — BASE-1 the baseline of main before the rename — done: 4580 tests, a red set that moves

- Why: nothing had been built since FIX-195 landed, and the rename needs a baseline to be compared against.
- Three runs of `clean verify -Pit` on a6b1201, one Maven at a time, Docker and Postgres up throughout, no test skipped.
  Run 1 (plain): EXECUTED_FAIL, stops in runtime — `StepBodiesIT.aRerunOfAStampingStep…` ("not within 20 s: fixture: 5 rows", FIX-212).
  Run 2 (`-fae`): EXECUTED_FAIL in runtime's unit tests — `PeerProtocolTest.aLinkPausedByAnAbandonedConsumer…` and
  `ReplicaStartTest.theStartRoomCheckCountsTheReplicas`; both passed in run 1.
  Run 3 (whole reactor, going on past failures so every module runs): EXECUTED_FAIL, 17 modules built, 4580 tests, 3 failures —
  StepBodiesIT again, ReplicaStartTest again with the same numbers, and bench `LedgerIT.aKilledNodeLosesNoAckedMessage` ("66 vs 60").
- So main is not green and its red set differs from run to run: four tests, rows FIX-212..FIX-215 under MAIN-1. LedgerIT is a no-loss
  check and is looked at first.
- cli, archtest (48 tests) and bench ran for the first time on this tree in run 3; ArchUnit passed; no coverage or enforcer error.
- Evidence: `agent/work/BASE-1-verify.log`, `-fae.log`, `-all.log`; the red set `BASE-1-red-set.txt`; per-module counts
  `BASE-1-counts.txt`. The rename is verified in run 3's mode against these two files.

## 2026-11-15 — BRAND-2 the Rail8 layout and coordinates — done (decided D-149, opponent: sound with changes)

- Class C: the designer drafted (`agent/work/BRAND-2-designer.md`), the opponent attacked it (`agent/work/BRAND-2-advisor.md`), the
  architect decided. The owner's fixed points held: directories lose the product prefix, `core` stays `core`, Apache Iggy is the model.
- Layout (D-149): the platform's modules under one area, `platform/{api,core,runtime,...}`; the root is `agent bench bin ci deploy docs
  examples issues mapping platform`; today's parent pom moves to `platform/pom.xml`, the root pom becomes a thin aggregator.
- Coordinates: `io.rail8`, artifactIds `rail8-*` (jar names must stay unique beside the mapping's), packages `io.rail8.*`.
  `draft — review at hand-over`: no `rail8.io` domain is registered (ISSUE-7).
- The rename is three commits — A moves, B identifiers, C persisted and wire names — done by one committed script and each verified
  against the BASE-1 red set and the per-module test counts. Packet `agent/packets/BRAND-1.md`; inventory `agent/work/BRAND-1-scout.md`.
- The opponent found what the draft missed: the mapping would stay `io.flottila.mapping` after BRAND-3; 131 of its 1154 commits carry no
  sign-off, so a history import turns the DCO job red; its workflows' upload paths; a baseline `--write` would hide a coverage drop.
- D-150: the mapping comes in with its history (a move commit, then a merge of unrelated histories); the DCO job lists `--first-parent`.
- New row BRAND-4: a CI guard with an allow-list keeps the old name from coming back.
- Checks: NOT_RUN mvn (documents only). EXECUTED_FAIL `python3 ci/ledger_check.py` — the 9 pre-existing shape problems (FIX-197).

## 2026-11-15 — the operations brief and the consolidation plan recorded — BASE-1 in progress

- Context compacted in this session; the state was re-verified with `git status` and `git log -3` before anything was written.
- The owner closed a two-day brainstorm on the log, monitoring, permissions and alerts: "write it down, nothing more is discussed".
  The result is `docs/OPERATIONS-BRIEF.md` (D-147). It supersedes the CONSIDER-13 message-log draft; Vector (CONSIDER-11) is rejected.
- The owner's plan, in this order (D-148): rename to Rail8; clean the monorepo after Apache Iggy (module directories lose the prefix);
  bring flottila-mapping into the repository; get main running and fix its faults; work the brief into ARCHITECTURE and every document.
- BACKLOG: section "Consolidation on main — priority 0" (BASE-1, BRAND-2, BRAND-3, MAIN-1, OPS-1; BRAND-1 set ready); section
  "Operations brief" (OPS-2..OPS-11); the parked brainstorms as CONSIDER-19..23 (custom code, masking, operating modes, dry replay,
  counted summaries); CORE-8 and CORE-10 amended: they carry records, and a full queue waits and alerts, it never drops.
- BASE-1, the first full `clean verify -Pit` since FIX-195 landed, is running; its result gets its own entry.
- Checks: NOT_RUN mvn (documents only). EXECUTED_FAIL `python3 ci/ledger_check.py` — 9 problems, the pre-existing shape problems
  (FIX-197), none on a row of this commit.
- Two journals exist: this file (newest first, the live one) and the root `PROGRESS.md` (append-only, ends 2026-11-03). OPS-1 unifies.

## 2026-11-15 — FIX-195 landed unreviewed, FIX-196 parked — state recorded on the owner's instruction

- The owner stopped the test work ("žádné testy, kašli na to, pořádek si uděláme na main") and asked only that the state be written down.
- FIX-195 is in the tree: BatchingWriter's D-146 policy, the three widened catch sites, the contained cancels, and the refused-cancel
  episode throttle with one flag per site. The implementer reported the module green and all seven mutants killed; its last step, the
  whole-module run and coverage, was cut short when the work was stopped.
- NOT done by this project's definition: no verification build of the architect's own, no reviewer pass. The BACKLOG row says
  `landed, unreviewed` and the D-146 row says the code, not the row, is the authority on what ships.
- FIX-196 never got past one partial test file; it is parked at `agent/work/FIX-196-partial-lane3.diff` and its packet still stands.
- Durability, on the owner's earlier instruction that everything reach main even against the rules: `agent/work/*.md` and `*.diff` are
  force-added past the ignore rule, the scratchpad design extracts are in `agent/archive/branch-design/`, and FIX-195's two intermediate
  states are on the remote branch `wip/fix195-snapshot` (f546913, 65403e1), both taken through a temporary git index so the agents at
  work in those lanes were never interrupted.
- Deviation: this entry records a task landed without its gate. That is the owner's call of 2026-11-15, not a precedent.

## 2026-11-15 — FIX-209 the ledger guard reports duplicate ids — done, review PASS (r2)
- Test first: 5 red cases in ci/test_ledger_check.py (agent/work/FIX-209-red.log, 26 tests, 5 failures), each failing for its own reason.
- Why: three id collisions in one night (D-101/D-102, D-144, CONSIDER-11). An allocation race between parallel sessions, not staleness -
  both read the same highest id correctly. The merge is the first moment a machine can see it and the last cheap moment to renumber.
- Rule: an allocation is the first cell of a line inside a table that is neither the header nor the separator, compared whole and stripped.
  So D-020 and D-020a never collide, a depends-on cell is not an allocation, and the repeated `| Id |` header of six tables is not one.
- The reds found a live collision: CONSIDER-11 on two different rows. Renumbered to CONSIDER-18 in the FIX-201 commit, 4 references repointed.
- Review r1 FAIL, three unpinned behaviours, all tests not code: file scope vs table scope (every fixture had both rows in one table, which is
  exactly the case the row exists for), the header negative too weak at two tables, and the summary count line asserted nowhere. r2 PASS.
- Each pin proven non-vacuous: the mutant applied to a byte copy, the failure observed, the restore proven with cmp.
- Checks: VERIFIED_PASS python3 -m unittest ci.test_ledger_check, 29 tests, OK, exit 0 (agent/work/FIX-209-green-r2.log).
  EXECUTED_FAIL python3 -I ci/ledger_check.py exit 1 - the 9 pre-existing shape problems (FIX-197), byte-identical in text and order, and
  no duplicate id. Nothing runs this guard in CI yet; that is FIX-199.
- Note for anyone running the suite: `python3 -I -m unittest ci.X` cannot import the ci package; -I strips the cwd from sys.path.

## 2026-11-14 — merge origin/main into the work branch, pushed to main (owner) — done
- Owner: 119 commits lived only on claude/brave-hopper-5vwaju; new sessions start from main and saw none of it. Owner chose "merge and push to main".
- main brought dc6a683 flottila-sim (P2-10a), the P2-10b WIP patch, CONSIDER-10/11 (owner 2026-11-10). Colliding ids renumbered on main's side:
  D-101 → D-143, F-25 → F-36, FIX-120 → FIX-193, CONSIDER-11 (top priority, flow error handling) → CONSIDER-15, CONSIDER-10 → CONSIDER-16.
- flottila-sim ported to the reworked core (implementer): ClaimCap/SimIntake/SimRun API moves, `maxAttempts=5` → `NO_RETRIES`; Oracle expects a
  park at step 1 (D-135; main's expectation was written against the pre-D-135 worker), noted in D-143.
- WORKFLOW §3: every push also goes to main; a session starts with `git fetch origin main` and checks HEAD contains it.
- Checks: reactor clean verify -Pit 4530 tests, 1 failure = LedgerIT:132 (FIX-184, tree-dependent: passes 2/0 in a copy of the merged tree);
  run 1 stopped on ReplicaStartTest:159 (new flake → FIX-194); sim 61/0 incl. pinned seeds and golden digest; archtest 48/0; coverage no
  decrease (sim line 97.2 / branch 95.9); demo 6/6. No reviewer run (a merge, no BACKLOG row). Logs agent/work/MERGE-main-*.

## 2026-11-13 — CORE-5b1 holder side of journal replication — done, review PASS (r5)
- Context compacted earlier in the session (worker restarted once; Docker and Postgres restarted per NOTES). Deviation: F-35 (changelog 3.21).
- Repl channel (the dialer's HELLO byte, handshake bytes and order unchanged, C1); frames OPEN 3, DATA 4, SEAL 5 (end, crc32c), RECEIPT 6
  (OPENED/WRITTEN/SEALED), REFUSE 8 (FULL/FAILED/MISMATCH); replicas `replicas/<owner>/<journal>/<n>.part` → `.seg` after force, rename, dir
  force; WRITTEN after pwrite with the force on its own thread; replicas refused past half the intake edge; a failed replica keeps its file;
  epoch bound at proof and every OPEN; supersede = abandon → stop → reset (PeerLink.reset via NetSocketInternal, D-139 bracket); start scan.
- Red: ReplFramesTest (python golden bytes), PeerReplHandshakeTest, PeerReplEpochTest, ReplicaHolderTest, ReplicaBackpressureTest,
  ReplicaIdentityIT, ReplicaStartTest, PgMembershipEpochsIT; fix rounds added NodeReplEpochIT, ReplicaSupersedeTest and the r1 reds.
- r1 FAIL (u64 overflow, count to file size, unbounded receipts, epoch max, docs), r2 FAIL (bound, fail-fast, reset in docs, internal API
  D-row), r3 FAIL (no lower bound), r4 FAIL (floor overlap), r5 PASS.
- Evidence: whole reactor 16/16, 4463 tests 0/0/0 (CORE-5b1-reactor4.log; reactor3 failed only FIX-168); later test-only edits run per class;
  48 hand mutants (M25 equivalent, M46 near-equivalent, known weak pin); demo 6/6; coverage no decrease.
- Filed FIX-192; FIX-183 gains the FrameRelay race; FIX-180 gains netty/internal confinement; CORE-5c gains close-by-owner.

## 2026-11-13 — CORE-5a2 peer link changes before replication (D-141) — done, review PASS (r2)
- Context compacted earlier in the session. Deviation: F-35 (peer link; D-139 amended by D-141, ARCHITECTURE §4, changelog 3.20).
- HELLO: version >= 1, the link speaks the lower, bytes after the v1 fields tolerated up to the cap; channel byte (1 read; 2 repl, 3 bulk
  refused until CORE-5b, an unknown one named); peer subkey K_peer = HKDF(empty salt, master, "flottila-peer-v1 subkey") keys the fingerprint
  and the session keys; FLOTTILA_PEER_READS 1..64; 8 handshakes per source address, the entry removed at zero (sourcesHeld probe).
- Red: PeerHandshakeVersionTest, PeerHelloLinkTest, PeerConfigTest, NodeConfigPeerTest, PeerProtocolTest:243, PeerHandshakeSourceBoundTest,
  PeerKnownAnswerTest (KAT recomputed in python, 12/12, agent/work/CORE-5a2-kat-compare.txt); r1 red PeerServerProbe (compile).
- r1 FAIL (M14 survived; KAT provenance; F-35, D-139 brackets, clear list, master-key sentence), r2 PASS.
- Evidence: runtime verify -Pit 1468 + 706 tests 0/0/0 (CORE-5a2-r1-verify2.log); clean whole reactor 15/16, 4362 tests, 2 failures, both
  in bench and both pre-existing: LedgerIT:132 fails on HEAD as well (A/B 1 of 3 vs 2 of 3, FIX-184), NodeBenchMainWarmupIT:112 float
  rounding (FIX-185, 6 of 6 pass in A/B); 20 hand mutants killed; demo 6/6; coverage no decrease.
- Filed FIX-183 (READ permit order race, PeerLink.send off the event loop), FIX-184, FIX-185.

## 2026-11-13 — FIX-175 each attempt keeps its own snapshots (D-140) — done, review PASS (r4)
- Context compacted earlier in the session; worker restarted once (Docker and Postgres restarted per NOTES "Build and VM").
- V19 (stop-all): `messages.attempt` (props' integer attempt copied, else 1; check >= 1), PK (id, step, attempt); Snapshot 17-arg ctor with
  `attempt`; timeline lists every attempt in (attempt, step) order with `attempt`; step body = latest attempt, `?attempt=k` (404 / 400);
  a replay from n takes the latest attempt whose step n−1 row is OK; the worker's rerun reads attempt 1's ingress. Bench statement follows.
- Red: InMemoryMessageStoreTest :68 :83 :87 :97, PgMessageStoreIT :125 :165 :211, MigrationsIT :615 :646 (+ pins 18 → 19), AdminServerTest
  :238 :357 :379 :401 :415, ReplayTest:224, FlowWorkerRetryTest :820 :845, NodeIT :1801 :1846.
- r1 FAIL (V19 not stop-all, pre-V19 rerun rows got attempt 1, §7 + D-rows stale), r2/r3 FAIL (D-row brackets D-044/045/047/057/096), r4 PASS.
- Evidence: whole reactor verify -Pit 16/16, 4316 tests 0/0/0 (agent/work/FIX-175-verify6.log; verify3–5 blocked by disk space and a stopped
  Docker, not evidence); 20+ hand mutants killed (M13 lock order unobservable, accepted); demo 6/6; coverage no decrease.
- Filed into FIX-174 (CLI steps count sees every attempt) and FIX-47 (a non-int `attempt` prop is refused when its snapshot is built).

## 2026-11-12 — FIX-176 rename leftovers after FIX-138a — done, review PASS (r3)
- core `Replay.retry` → `replayParked`; javadocs and messages that called the new copy a "DLQ retry" now say "DLQ replay" (RowNotRemovedException
  500 body, ReplayRefusedException, FlowBuilder, Deployments, Dlq, Parked, Trail, BodyLostException, flow.schema.json `delivery.timeout`).
- 176 remaining `retr(y|ies)` hits in src/main sorted by sense, all kept (agent/work/FIX-176-retry-grep.txt). ARCHITECTURE :288/:335/:341 reworded.
- r1 FAIL (four javadocs missed by the singular grep), r2 FAIL (ARCHITECTURE :288 still "replay or retry" for the 409 text), r3 PASS.
- Filed: FIX-182 (test texts by the test-author; an IT for the /retry pool 503). Doclint errors at WorkQueue:106/127/128 are older (not run by the build).
- Evidence: whole reactor verify -Pit 16/16, 4295 tests 0/0/0 (agent/work/FIX-176-verify.log; run 1 hit flake FIX-168, 5/5 alone); schemas 304/0
  after r2; compile + javadoc (doclint none) green; coverage no decrease. Also in this commit: CORE-5 packet notes (advisor on D-139, 5b draft + advisor).

## 2026-11-12 — CORE-5a peer link + node-to-node body read (D-139, draft) — done, review PASS (r3)
- Context compacted once (re-read HANDOFF, git status, git log -3). Deviation: F-35 (an internal node-to-node link beside the Component SPI).
- `journal.peer`: framing v1 + HMAC under the env master key (W7 proof, fingerprint choice of current/previous), READ → BYTES/MISS by
  (owner, journal id, segment), FLOTTILA_PEER_READS per peer, pause-based flow control; V18 `nodes.peer_address` in the join and the renew;
  TieredBlobStore reads own journal → live owner; FLOTTILA_PEER_* and FLOTTILA_JOURNAL_COPIES read, KEY_GENERATE refused above 1 copy.
- Red: PeerLinkTest, PeerReadIT, PeerReadHeapTest (-Xmx48m), PgMembershipPeersIT, NodeConfigPeerTest, MigrationsIT:587, archtest
  ONLY_THE_PEER_LINK_OPENS_RAW_TCP (packet "Red approved 5a"); r2 PeerProtocolTest/PeerIdentityTest/NodePeerReadIT; r3 PeerKnownAnswerTest.
- r1 FAIL: pre-auth 4 MiB allocation, a throwing dial hung, paused link unbounded, heap test vacuous, no Node IT, toString key hash, docs.
  r2 FAIL: no known-answer vector for the pre-auth fingerprint / HKDF. r3 PASS (proof literals recomputed by the architect).
- Filed: FIX-177 (head-of-line), FIX-178 (previous key + rotation), FIX-179 (peer port clash), FIX-180 (JDK sockets in ArchUnit), FIX-181.
- Evidence: whole reactor verify -Pit 16/16, 4295 tests 0/0/0 (agent/work/CORE-5a-r3-*); 30+ hand mutants killed (CORE-5a-*mutants.txt,
  -r3-mut-*); demo 6/6; coverage no decrease. D-054 data: class C (test-author ×4, implementer ×2, reviewer ×3); review rounds 3.

## 2026-11-12 — FIX-138a retry keeps the id, replay mints one (D-136, draft) — done, review PASS (r2)
- `POST /admin/dlq/{id}/retry` is now the same-id restart (CORE-14b handler; 422 texts name `/replay`); `/restart` removed (404);
  `POST /admin/dlq/{id}/replay` is the former new-copy retry, `?from=` → 400. F-29 F4.1: the documented route no longer escapes dedupe.
- Red: AdminDlqRestartIT :369 (/restart 404), :412 (failed restart tx twice, then the same id once at a deduping partner);
  AdminServerReplayTest :725; NodeIT :1682, :1716; `namesReplay` (A5). Moved tests renamed per route (commit body).
- r1 FAIL: docs still used the old route sense (changelog 3.11 half-renamed, D-057(c)/D-133/D-135 brackets, :210); fixed by the architect.
- Filed: FIX-138b (durable copy claim), FIX-175 (a same-id rerun's snapshots dropped: `on conflict do nothing`), FIX-176 (javadoc leftovers),
  CONSIDER-14 (no same-id path for replay or step-n rows).
- Evidence: whole reactor verify -Pit 16/16, 4171 tests 0/0/0 (agent/work/FIX-138a-*); 11 hand mutants killed; demo 6/6; coverage no decrease.

## 2026-11-12 — CORE-14b same-id DLQ restart (D-135 [CORE-14b]) — done, review PASS (r3)
- context compacted (one line, CLAUDE.md); re-verified with git status / git log -3.
- `POST /admin/dlq/{id}/restart` → 202 {id, attempt}: one transaction deletes the DLQ row (re-checked: step 1, attempt k, no replayOf) and inserts a
  step-1 work row with the same id, the copied ingress ref, `max_attempts` = k + 1 and the kept partition key; 404/409/422 per D-135; wakes and announces.
- V17: `work.max_attempts`, `dlq.partition_key`. API: `WorkQueue.restart`, `Item.maxAttempts`, `Parked.partitionKey`.
- Red: FlowWorkerRetryTest :679/:716/:745/:780, WorkQueueContract :261/:288, DlqRestartIT :162/:237, AdminDlqRestartIT, MigrationsIT:555;
  r1 PgWorkQueueRestartIT :119/:129/:140 (stale-read re-check).
- Found: CORE-14a broke the CLI flow tests (partner-down cases past the 10m horizon under 3 × PT5M; gate check 8 4/6). Fixed (O9): demo and
  flowtest at-least-once flows name `delivery.retries {count: 1, interval: 1s}`; expectations 700ms→2400ms, retries 3→7, sent 4→8. FIX-174 filed.
- Evidence: whole reactor verify -Pit 16/16 modules, 4167 tests 0/0/0 (agent/work/CORE-14b-r4-*); demo 6/6; 46 hand mutants killed; PIT 95 %.

## 2026-11-12 — CORE-14a retries on a schedule, then park (D-135, draft) — done, review PASS (r3)

- Red first: FlowWorkerRetryTest (15 cases + T1/T2), RetrySchedulePropertiesTest, FlowDefinitionRetriesTest, WorkQueueContract
  defer cases, PgWorkQueueDeferIT, SegmentSweepIT.aDeferredItemAlonePinsItsSegment, DeploymentsRetriesTest, NodeConfigRetriesTest,
  FlowRetriesSchemaTest (red observed: CORE-14a-red-{core,runtime,schemas}.log).
- A failed at-least-once attempt defers its row (same id, not-before in DB time, no thread waits); park the ingress after
  count + 1; MALFORMED/INVALID/final NACK park at once; `delivery.retries` + FLOTTILA_RETRIES_COUNT/INTERVAL (3 × PT5M).
- maxAttempts gone; the worker pipeline has no per-step Parking; dead fenced Parking removed. Existing tests rewritten per the
  packet's (a)–(d), reasons in the commit body.
- Evidence: verify -Pit exit 0 (CORE-14a-r2.log), coverage no decrease, 22 + 5 hand mutants killed, scoped PIT 97 %.
- Review r1 FAIL (docs, Error exemption, evidence), r2 FAIL (two PIT survivors) → fixed; r3 PASS. M21 race-only, unpinned.
- Next: CORE-14b same-id restart. Owner: D-135 defaults, CONSIDER-13 (resume from the failed step).

## 2026-11-12 — CORE-12 ARCHITECTURE rewritten to the core-rework brief (F-34) — done, review PASS (r2) · Deviation: F-34

- Docs task: the red was the scout's contradiction list (agent/packets/CORE-12.md); residue grep agent/work/CORE-12-residue.txt.
- Built mechanisms in present tense (journal body store, no Kafka on core paths, refs in Postgres); pending ones as
  "(CORE-n, pending)": mover into ClickHouse (Q-3, CORE-10), retries on a schedule (Q-7, CORE-14), levels (Q-9, CORE-13),
  sync recording off the hot path (Q-6, CORE-8), copies (O-7, CORE-5), S3 cold tier (CORE-13), bodies drop (CORE-11).
- New §4 "Body journal" paragraph incl. the interim FLOTTILA_JOURNAL_KEEP_MAX_AGE pin (D-105); changelog 3.14; F-34.
- Review r1 FAIL (interim pin stated as end state; S3 tier on two rows) → fixed. runtime pom description updated (validate exit 0).
- For the owner (F-34): deploy refusal above FLOTTILA_BLOB_DURABLE_MAX vs D-110; S3 narrowed to CH cold tier + archives.

## 2026-11-12 — CORE-9 Kafka out of the core (D-134, draft; CORE-REWORK O-1) — done, review PASS (r3)

- Context compacted before this task; re-verified with git status / git log -3.
- Red first: ComposeStackTest kafkaOnlyUnderTheKafkaProfile, theDefaultUpStartsNeitherKafkaNorClickhouse; DeploymentsQueueTest.kafkaRefusedWithOneLine
  (red observed, agent/work/CORE-9-red-head.log); NoKafkaClientTest in api, core, runtime, components http/mock/sftp, steps xslt/validate
  (green pins, red under pom hand mutants M1 runtime / M2 core, CORE-9-red-mutants.log).
- Compose: `kafka` only under profile `kafka`; a plain `up` no longer starts it; CI teardown and READMEs carry `--profile kafka`.
- `queue: kafka` refusal and the flow schema name Kafka the optional add-on. No Kafka client existed in src/main; none may reach any core classpath.
- CORE-GATE condition 6 evidence: `verify -Pit` with no Kafka container — NodeIT sync, replay, DLQ-retry cases, InvoiceAsyncIT, AtLeastOnceIntakeIT green
  (CORE-9-xml.txt; run 1 hit known flake FIX-168, rerun alone green; run 2 -rf :flottila-runtime exit 0). Coverage no decrease.
- Docs: ARCHITECTURE §2/§3 + changelog 3.13, GATE check 1, D-134 draft (compose topology) amending D-085, HANDOVER drafts, QUICKSTART.
  The remaining Kafka text in §2/§3/§7/§8 stays with CORE-12.

## 2026-11-12 — FIX-173 work-queue answers no longer depend on flow order on deploy and undeploy (D-133 [FIX-173]) — done, review PASS (r2)
Test first: DeploymentsRedeployTest:1065, DeploymentsUndeployTest:914 aRealQueueFailureOutranksAPoolRefusalInEitherFlowOrder,
DeploymentsUndeployTest itemsOfOneFlowOutrankAPoolRefusalOfAnotherInEitherFlowOrder (red FIX-173-red.log, -r2-red*.log); CountingQueue per-flow hook
Change: Deployments check() keeps a real failure apart from a pool refusal: 422 problems, then the real IOException (500), then BUSY;
queued() a real failure at once, then held items 409, then BUSY. §4 + changelog 3.12, D-133, AdminServer and DeployRefusedException javadocs
Checks: verify -Pit 3762/0/0/0 fresh XML, coverage no decrease VERIFIED_PASS; M1–M9 killed (r1-M9 the gap the r2 test closed)
Review: r1 FAIL (docs stated the FIX-172c precedence), r2 PASS

## 2026-11-12 — FIX-172c a deploy's own work-queue question refused by the pool is BUSY 503 (D-133 [FIX-172c]) — done, review PASS (r2)
Test first: DeploymentsRedeployTest:994 aQueueQuestionThePoolRefusedIsBusyAndChangesNothing (red FIX-172c-red.log)
Change: Deployments:1170 a pool refusal of the queue question → Kind BUSY after the problems check (422 first); :622 validate rethrows
a BUSY as the node's IOException (an empty problem list would read as valid); javadocs and §4 / 3.11 / D-133 no longer name a 500
Checks: verify -Pit 3759/0/0/0 fresh XML, coverage no decrease VERIFIED_PASS; M1–M5 killed (FIX-172c-mutants.log)
Review: r1 FAIL (two Kind.BUSY source lists in javadoc), r2 PASS. Filed FIX-173 (queue answers depend on flow order)

## 2026-11-12 — FIX-172b a typed pool refusal on the transaction path (D-133, amends D-132) — done, review PASS (r3)
Test first: PgTxTest (9), NodePoolWaitIT discard/replay/retry/deploy/intake-queue/undeploy, AdminServerReplayTest :891/:922,
NodeConfigPoolWaitTest :188, DeploymentsRecordTest delete-refused (red FIX-172b-red-*.log, -r2-red-*.log; NoStackTraceThrowable observed)
Change: io.flottila.runtime.pg.PgTx.inTransaction / acquire: a failed acquire is PoolRefusedException (by where it failed); every store
transaction and the intake's acquire go through it; PgTx.refusedByThePool (exact types) in run(), ran() (replay/retry run 503), deploy
record write, undeploy queue question and record delete (Kind BUSY → 503, the refusal's own line); queue env refusal names 1..65536
Checks: verify -Pit 3756/0/0/0 fresh XML, NodePoolWaitIT 10/0, bench 255/0, coverage no decrease VERIFIED_PASS; M1–M18b killed, M11 equivalent
Found: no DLQ discard route exists, DLQ/deploy reads were already 503 — FIX-172's route list was wrong, docs now enumerate routes
Residual → FIX-172c: a deploy's own work-queue question still 500. Not done: the replay 503 body keeps no replay id
Review: r1 FAIL (undeploy 500, DLQ-retry row-removal exception, PgTx begin/rollback cases, D-132 marker), r2 FAIL (javadoc), r3 PASS

## 2026-11-11 — FIX-172 finite wait and queue for a pooled Postgres connection (CORE-17 O2, D-132) — done, review PASS (r2)
Test first: NodeConfigPoolWaitTest (17), NodePoolWaitIT A2 intake 503 within the wait, A3 admin read 503, A4 claim survives, A5 queue
(red FIX-172-red-config.log, -red-it.log, -r2-red.log); AdminServerTest:1013 by the implementer, approved (only kill of exact-class)
Change: Pg poolWait/poolQueue; FLOTTILA_PG_POOL_WAIT PT0.001S..PT1M (PT5S), FLOTTILA_PG_POOL_QUEUE 1..65536 (1024), refused naming
the key in the NodeConfig ctor; Node sets connectionTimeout + maxWaitQueueSize on both pools; admin read refused by the pool → 503
"the database pool is busy; retry" (exact VertxException or ConnectionPoolTooBusyException, no message match). Client default was 30 s, unbounded
Checks: verify -Pit 3735/0/0/0 fresh XML (FIX-172-xml.txt), coverage no decrease VERIFIED_PASS; mutants M1–M7 + 5 range/body killed
Found: the wait covers the connect (cold first connection 140–380 ms) — docs warn below ~1 s; NodePoolWaitIT warms one node per JVM
Residual → FIX-172b: transaction-path admin routes 500 (NoStackTraceThrowable), replay 502, intake queue bound unpinned, queue message range
Review: r1 FAIL (docs claimed every admin request, D-132 cause, BACKLOG done-when, XML evidence, unapproved test), r2 PASS

## 2026-11-11 — CORE-17 Postgres pools by role: intake apart from work, sessions named by role (F-27 I4, D-130 draft) — done, review PASS (r3)
Deviation: F-33 (the D-130 split of 16 = 5 + 11 starved the workers; default total 21 = 5 intake + 16 work, 23 connections per node)
Test first: NodeConfigPoolsTest, NodeConfigIntakeGroupTest, NodeConnectOptionsTest:78, NodePoolRolesIT :170/:197 (red CORE-17-red*.log);
r2 NodeConfigPoolsTest:126/:144, NodePoolRolesIT:198 listen; r3 default 21 (CORE-17-r3-red*.log); InProcess + JournalRowsIT by implementer
Change: NodeConfig.Pg intakePoolSize, FLOTTILA_PG_POOL_INTAKE, NodeConfig ctor refuses a split leaving either pool none; Node builds
intake (PgIntake, journal segment rows, durability check) and work pools, application_name flottila-<role>@<node>; bench JSON intakePoolSize
Checks: verify -Pit 3713/0/0/0 fresh XML, NodeTakeoverLedgerIT 6/6 alone (after FIX-156), bench 255/0, coverage no decrease VERIFIED_PASS
Mutants: split refusal both terms, formula, bound, names, close, default 21 killed; M8 equivalent, M12/M14 accepted
Bench (indicative, RESULTS top): at 21/5 ACK p99 51–63 ms vs one pool 81–104, backlog flat; 16/5 grew +25–45 k at r2000 all
Review: r1 FAIL (in-process split, no measurement, §4), r2 FAIL (takeover flake 10/11, RESULTS, P5), r3 PASS. CONSIDER-12 warm intake
Owner: F-33 asks whether 23 connections per node is acceptable against max_connections

## 2026-11-11 — FIX-156 NodeTakeoverLedgerIT ack() backs off on a 503 instead of five back-to-back tries — done, review PASS
Red: the flake itself, "message N was not ACKed: 503 admission refused … 4/4 requests", 2/6 alone on 32840d4 (agent/work/CORE-17-flake/)
Change: test fixture only. ack() retries a 503 with 10 ms doubling to 200 ms until 10 s, then fails with tries and last answer;
any other status fails at once (before: any status got 5 tries). Every IT assertion unchanged. The wait is client backoff (D-082)
Checks: 10/10 green alone on 32840d4 + the IT (worktree), 6/6 on the CORE-17 tree (agent/work/FIX-156/*-summary.txt) VERIFIED_PASS
Coverage baseline not rewritten: test-only change, and the working tree carries uncommitted CORE-17 code (found while measuring CORE-17)

## 2026-11-11 — FIX-169 Postgres 18.6 as the test image, one source for the version, 17.11 CI floor (owner, D-129) — done, review PASS (r2)
Test first: PostgresImageTest (5), PostgresImageSourcesTest (scan + compose tag + volume, 6) (red FIX-169-red.log, -scan-after.log)
Change: testkit PostgresImage (postgres:18.6, property flottila.it.postgres); 97 test classes switched; ledger fixture uses $PGDATA;
compose files on 18.6 with the volume at /var/lib/postgresql (QUICKSTART: `docker compose down -v` once); CI matrix main 18.6 +
floor 17.11; bench DbSampler/NodeBenchMain read WAL syncs from pg_stat_io on 18 (PG 18 dropped pg_stat_wal.wal_sync)
Stale expectations fixed: BenchMainIT:39-41, NodeBenchMainIT:78-80 asserted "17." (now the image's major)
Checks: verify -Pit on 18.6 3677 tests, 1 failure ComponentContractSelfTest:337 (6/6 alone → FIX-170) EXECUTED_FAIL · runtime -Pit
on 17.11 3361/0 VERIFIED_PASS · bench -Pit 255/0 on both · coverage no decrease · M1, M2, M4, M5 killed; M3 accepted (init fsyncs)
Review: r1 FAIL (RESULTS method note, D-129 claimed a green run, BACKLOG done-when), r2 PASS. Follow-ups: FIX-169b, FIX-170, FIX-171

## 2026-11-11 — FIX-137 WAL barrier for a duplicate-only intake group under ha (review F3.2, D-128) — done, review PASS (r2)
Context compacted once (re-read HANDOFF, git status, git log -3 before changing anything).
Test first: PgPromotionLedgerIT.aRetriedDuplicateAfterATerminatedSyncWaitIsNot202UntilReplicated and
.haRefusesARoleThatMayNotRunTheDuplicateBarrier; PgIntakeIT 5 ha/local barrier cases; PgDurabilityTest B5 cases + properties
(red FIX-137-red*.log, FIX-137-b5-red*.log)
Change: under ha a group with nothing written runs `pg_current_xact_id(), pg_logical_emit_message(true,'flottila','')` and
commits (an xid alone writes no WAL, so xact.c skips the SyncRep wait — B1 amended); local keeps the rollback. Owner decision:
EXECUTE on pg_logical_emit_message(boolean,text,text,boolean) is an ha start condition, refused naming the GRANT; ha needs PG ≥ 17
Checks: targeted 29+2+7+26+38 /0 VERIFIED_PASS · verify -Pit EXECUTED_FAIL only on flakes NodeTakeoverLedgerIT:246 (FIX-156),
FlowWorkerOrderingPropertyTest:182 (FIX-155), JournalDirForceOrderTest:98 (new → FIX-168) · coverage no decrease · M1–M5, M1–M7 killed
Review: r1 FAIL (D-127 residual wording, 9-arg ctor javadoc pre-amendment), r2 PASS. Follow-ups: FIX-168, FIX-169 (PG 18/19, owner)

## 2026-11-11 — FIX-136 Postgres durability profiles local | ha (review F3.1, D-114/D-115, D-127 draft) — done, review PASS (r2)
Test first: NodeConfigTest durability cases, NodeConnectOptionsTest pin, PgDurabilityTest (+2 jqwik), PgPromotionLedgerIT (two
postgres:17 with streaming replication, SIGKILL + pg_promote: every 202 survives under ha; async standby loses exactly the
unreplicated ids under local), NodeIT rewrite of the D-061 refusal (red FIX-136-red*.log)
Changed: FLOTTILA_PG_DURABILITY (dev/ci → local, deployed → ha); every session pinned synchronous_commit=on,
statement_timeout=0; PgDurability start check replaces requireSynchronousCommit (recovery, names, pg_read_all_stats, a sync or
quorum standby streaming); notices on /health. No runtime watch (D-115) → CONSIDER-11; managed PG → ISSUE-5
Stale expectations fixed: NodeConfigTest:102, NodeIT/NodeJournalIT deployed nodes set local. Finding: PG 17 statement_timeout
does not cut a SyncRep wait
Checks: verify -Pit (no bench) 3652/0/0/0 (run 2; run 1 hit /tmp space) · bench parts green · coverage no decrease · mutants
M1–M13, M11b, X1–X5 killed
Review: r1 FAIL (stale start javadoc; residuals F3.2 and the later-reload case unwritten), r2 PASS

## 2026-11-11 — FIX-141 lost work listed with what it blocks, discard with an audit row (review F7.2, D-126 draft) — done, review PASS (r2)
Test first: PgWorkQueueLostIT (8), LostWorkAdminIT (5), MigrationsIT V16 (audit columns not null, work_lost index) (red FIX-141-red*.log)
Changed: V16 `audit` (append-only, all not null) + partial index work_lost; PgWorkQueue lost listing and `blocks` on
held/lost; DISCARD one statement (delete while lost_at is not null, key advisory lock, audit insert), next-of-key node read
in the same tx; GET /admin/work/lost, POST /admin/work/:seq/discard (actor = X-Flottila-Actor ≤ 128, else unknown; remote);
wake + announce shared with release. No restore (bytes gone, D-105). Stale expectations fixed: V16 counts, held `blocks`
Checks: verify -Pit (no bench) 3599 tests, only FIX-155/FIX-156 flakes (pass alone) · coverage no decrease · mutants M1–M10 +
r2 A–C killed · generic plan: Index Scan using work_lost (200k rows, 5 lost)
Found: FIX-167 (a held+lost row is listed twice and its release answers 200 though it can never run; blocks count uncapped)
Review: r1 FAIL (not-null unchecked, next-of-key predicates and audit time unpinned, no index), r2 PASS

## 2026-11-11 — FIX-143 an ordered flow states onFailure; block refused until a durable barrier (review F7.4, D-125) — done, review PASS (r2)
Deviation: F-32 — ARCHITECTURE §4 said an ordered queue blocks by default; it parks and continues (both park sites complete)
Test first: FlowDefinitionTest (O2 missing key, O3 block, O4 without partitionBy, enum, park readable), FlowWorkerTest
.aKeyedItemParkedPastMaxAttemptsLetsTheNextItemOfItsKeyRun, SchemasTest, DemoFlowsTest, invalid fixtures (red FIX-143-red*.log)
Changed: FlowDefinition.onFailure (park | block) with load refusals; flow.schema.json enum + dependentRequired both ways;
demo flows invoice-async, invoice-sync, sftp-invoice state park; FlowWorker unchanged. Test fixtures with partitionBy gained
`onFailure: park` (O6, a new required key — reason in the commit body)
Checks: verify -Pit (no bench) 3577 tests; recount found 3 stale mutant-run failures, unmutated reruns core 631/0, schemas
293/0 · coverage no decrease · PIT FlowDefinition 59/60 (survivor unchanged :190) · mutants M1–M5, S1, H1, H2 killed
Review: r1 FAIL (F-32 evidence false, one park site unnamed, D-055 marker, mutant and count evidence missing), r2 PASS

## 2026-11-11 — FIX-146 Postgres TLS with CA and hostname verification (review F9.1, D-124 draft keys) — done, review PASS (r2)
Test first: NodeConfigPgTlsTest, PgTlsIT (ssl=on hostssl-only servers + a plaintext one, keytool certs at test time; right CA,
wrong CA, name mismatch, disable refused, verifying mode vs plaintext refused, start's migration verified via a forwarder),
ComposeStackTest explicit disable; NodeConfigTest/LedgerMainPartsTest/StreamHeapMainPartsTest set disable (red FIX-146-red*.log)
Changed: FLOTTILA_PG_SSL_MODE disable|verify-ca|verify-full (loopback → disable, else verify-full), FLOTTILA_PG_SSL_ROOT_CERT
(missing/unreadable/empty refused naming both keys); one PgConnectOptions for pool, membership, LISTEN; Flyway URL with
sslmode + encoded sslrootcert; both compose files set disable; implementer test NodeJdbcUrlTest
Checks: verify -Pit (no bench) 3566/0/0/0 fresh XML · bench 257/0 · coverage no decrease · mutants r1 16/17 + r2 5/5 killed;
survivors equivalent (VERIFY_CA keeping HTTPS; REQUIRE — one Vert.x branch with the given trust and algorithm)
Found: FIX-165 (IPv6 host unbracketed in JDBC URL), FIX-166 (bench's own PG links carry no TLS)
Review: r1 FAIL (migration call site and plaintext-server fallback unpinned, tautological URL test, javadoc, bench wording), r2 PASS

## 2026-11-11 — FIX-144 readiness and liveness endpoints (review F4.4, D-123) — done, review PASS (r1)
Test first: AdminServerTest liveAnswers200WithoutReadingAnyCheck, readyIsDownNamingEachFailedCheck, readyAnswersWithoutAwaiting,
liveAndReadyNeedNoTokenWhileAdminDoes; NodeIT fencedNodeIsNotReadyButRemainsLive, drainDropsReadinessBeforeListenersStop,
journalInOutageNodeIsNotReady, failedFlowNodeIsNotReady, liveDoesNotTouchPostgres (red: compile + 404 vs shim, FIX-144-red*.log)
Changed: AdminServer GET /live (constant) and GET /ready (lifecycle, fence, journal UP, deployments; in-memory, no await, no
token), Readiness record, 4-arg HealthProbe (2/3-arg = ALWAYS); Node ready flag set last in start, cleared first in close
Checks: verify -Pit (no bench) 3530 tests, 0 failures, 1 error = FIX-156 flake · coverage no decrease (runtime 99.4/99.6) ·
hand mutants M1, M3–M5 killed; M2 (ready set early) survives, accepted (visible only with FIX-164's leaked admin port)
Found: FIX-164 (start failure leaves a started admin server open). Docker healthchecks still use /health (unchanged)
Review: r1 PASS

## 2026-11-11 — FIX-160 test race: COW subList views read under concurrent append — done, review PASS (r2)
Test-only. NodeShutdownWaitsForWorkerSettlementIT:363 streamed a live CopyOnWriteArrayList subList the log handler appends to (CME)
Changed: snapshot (`List.copyOf`) before every subList; lazy failure messages via until(BooleanSupplier, Supplier<String>); same
fix in JournalKill9IT, OrderedDeliveryIT, ReplayRetentionIT, LostBodyAdminIT, PgWorkSignalsIT, DeploymentsUndeployTest
Checks: NodeShutdownWaitsForWorkerSettlementIT 20/20 runs exit 0, 4/0/0/0 each (agent/work/FIX-160-v.summary, v1..20.xml) ·
touched classes 33/0/0/0 (FIX-160-touched.log) · coverage baseline not rewritten (no production change; partial-run reports)
Review: r1 FAIL (per-run counts missing from -q logs), r2 PASS on the XML evidence

## 2026-11-11 — CORE-7 batched completion of unkeyed work items (D-122 draft, F-31) — done, review PASS (r2)
Context compacted mid task (re-verified git status/log). Deviation: ARCHITECTURE §4 fence (D-101 (2) amended), §7, changelog 3.7, F-31
Test first: FlowWorkerCompleteUnkeyedTest, PgWorkQueueBatchedCompletionIT (100 completions → 2 statements, red 101),
PgWorkQueueBatchedCompletionPropertyIT, WorkQueueContract unkeyed case, PgBatchedWorkQueueContractIT, JournalBlobStoreIT and
NodeShutdownWaitsForWorkerSettlementIT cases (red agent/work/CORE-7-red*.log); implementer added PgWorkQueueCompletionEdgesIT
Changed: core WorkQueue.completeUnkeyed, FlowWorker.settle routes unkeyed items to it; runtime PgWorkQueue batch over BatchingWriter
(256 / PT0S / 2 in flight / 8192 / PT5S, constants), one autocommit unnest DELETE fenced by attempts, held and keyed rows excluded,
absent = STALE, failed statement fails all, no retry; Node closes the queue after flows, before signals and pool
Checks: verify -Pit (no bench) 3520 tests, 0 failures, 1 error = FIX-156 flake (passes alone 2/2) · BenchMainIT 2/2 · coverage no
decrease · PIT FlowWorker/WorkQueue 195/204 (survivors on untouched lines) · hand mutants 13/13 killed; M14 (start-failure close)
survives, accepted (rows left to their lease) · generic plan one work_pkey probe per member (CORE-7-plan.txt)
Found: FIX-163 (close wait unbounded). F-31 (2): every row is still deleted; keyed rows keep one transaction per message
Review: r1 FAIL (delay-bound wording false, D-101 marker missing, start-failure close unpinned), r2 PASS

## 2026-11-11 — P2-16b work events between nodes, PT30S reconciliation (D-121 (b) draft, F-30) — done, review PASS (r2)
Container restart mid fix round (tree intact, no mutant left: cmp against .r3.bak). Deviation: ARCHITECTURE §4/§7, changelog 3.6
Test first: FlowWorkerEventsTest, PgWorkSignalsIT, TwoNodeWorkEventsIT, IdleNodeStatementsIT (red agent/work/P2-16b-red*.log)
Changed: core WorkSignals, WorkEvents (leading edge, one folded statement per 50 ms, cap 64 → `* *`), LocalWorkSignals; runtime
PgWorkSignals (dedicated LISTEN connection, backoff 100 ms → 5 s until close, wakeAll after every LISTEN); NOTIFY only after commit:
keyed completion (fast path and plain completeRow) naming the next row's node, admin release (also wakes locally), kept ref on
another node; WorkerPolicy DEFAULT PT30S, first reconciliation at P + floorMod(hash(flow), P); announce throws isolated and logged
Test edits (wrong expectations, named in the packet): ~17 tick tests (B5), PgWorkSignalsIT:365, PgIntakeHandoffIT.Claims forward
Checks: verify -Pit (no bench) 3495 tests, 0 failures, 1 error = FIX-160 test race · coverage no decrease · PIT 465/479 core ·
hand mutants M1–M24, R1–R8, V1–V6, N1/N5, O1–O3 killed; N2–N4 Node wiring survive → FIX-161
Review: r1 FAIL (reconnect cap, LISTEN-error close, failed next-row read unpinned), r2 PASS
Opened: FIX-160 (shutdown IT CME), FIX-161 (Node wiring IT), FIX-162 (spilled wake not isolated)
Next: CORE-7 (batched completion, class C: designer first)
## 2026-11-10 — P2-16a local hand-off: a saved item runs on its node without a claim (D-121 (a) draft, F-30) — done, review PASS (r3)
Context compacted once mid-task (re-verified with git status / log -3). Deviation: ARCHITECTURE §4/§7 + changelog 3.5 (no accept poll)
Test first: FlowWorkerHandoffTest, PgIntakeHandoffIT, HandoffKeyOrderProperties, WorkQueueContract (red agent/work/P2-16a-red.log);
after review: FlowWorkerHandoffEdgeTest, FlowWorkerHandoffRefusedRunTest, PgIntakeHandoffGroupIT/LogIT, +R1–R14 IT cases
Changed: core Workers + FlowWorker.Slot; WorkQueue.enqueueLeased; the intake writes a heading item leased (attempts 1) in its
transaction; per-key advisory lock on keyed inserts and completes (READ COMMITTED); V15 work(flow, partition_key, seq); accept poll
gone, lease-end wake instead; claim-room fix (`asking`, found by jqwik); slot calls after the answers, isolated, cleanup suppressed
Test edits (wrong expectations by design, named in the packet): 12 key-lock ITs, SegmentSweepIT, 17 lease-end core tests, V15 counts
Checks: verify -Pit (no bench) VERIFIED_PASS 3428 tests, 375 fresh XML · BenchMainIT 2/0 · coverage no decrease · PIT 199/206
(FlowWorker*/SequentialIntake) · hand mutants M01–M34, R01–R15, O01–O12, r4/r6 logs; survivors argued in the packet
Review: r1 FAIL (untested paths R1–R3, slot-call order R4), r2 FAIL (lease left renewing N1, primary lost N2), r3 PASS
Opened: FIX-156 (ledger ack flake), FIX-157 (M20 lost-lease wake), FIX-158 (claimed() loop leak), FIX-159 (open-save flake, base)
Next: P2-16b (events between nodes, PT30S reconciliation), then CORE-7
## 2026-11-10 — P2-15c kill -9 ledger and SIGSTOP acceptance (D-112, F-28) — done, review PASS (r2)
Test: NodeTakeoverLedgerIT (child JVM nodes, Testcontainers PG) — acceptance, green on today's code; red by hand mutants
Case 1 kill -9 A at 1000 ACKs: 2000 ACKed, partner distinct 2000, raw duplicates 8 = A in flight 8, Σdoubts 8 = reruns 8, none
before A's old lease_until, B never superseded (59 s). Case 2 SIGSTOP A with 4 held sends: reset after SIGCONT, nothing after the
fence, no queued item taken, A exits SUPERSEDED (9 s)
Mutants (agent/work/P2-15c-mut.log): a send ignores the fence — killed; c rejoin does not wait — killed; d claim ignores the fence —
killed; b TAKE without liveness — survives by construction (a node claims only its own body_node items until CORE-5)
Review: r1 FAIL (negatives behind three gates, mutant d not run), r2 PASS · No production change
Next: P2-16 (local hand-off, no claim), then CORE-7
## 2026-11-10 — FIX-145 a pg_xact_status failure pins only its own save (review F7.3, F-29) — done, review PASS (r1)
Test first: SegmentSweepIT .futureXidDoesNotBlockUnrelatedSegmentCollection, .aFailingStatusQueryLeavesEveryInDoubtSaveOpenAndThe
SweepRuns, .oneBadXidDoesNotHoldBackAResolvableOne + InDoubtSeam (red agent/work/FIX-145-red.log, FIX-145-red-scratch.log)
Changed: PgIntake.settle — batched lookup first, per-xid on its failure; a failed xid stays in doubt (never an outcome), one WARNING
per xid until it settles; openSaves never throws for a lookup; package-private inDoubt seam (also the production path)
Checks: runtime -am verify -Pit VERIFIED_PASS 3054 tests, 338 fresh XML · coverage no decrease · hand mutants M1–M3 killed
Review: r1 PASS · Opened: FIX-153 (xid reused after a failover, class C), FIX-154 (stop the fallback on a connection failure)
Next: P2-15c (kill -9 ledger, SIGSTOP)
## 2026-11-10 — FIX-142 node shutdown awaits bounded worker settlement (review F4.3, F-29) — done, review PASS (r2)
Test first: NodeShutdownWaitsForWorkerSettlementIT (3; red agent/work/FIX-142-red.log, redeploy case a green control); after the red,
FlowWorkerUnsettledTest (test-author) and DeploymentsProducersTest close case reversed (wrong expectation: close now waits)
Changed: Deployments.close stops flows, then drains every worker's close within the one stopDeadline (node timer); past it one
WARNING per flow names its unsettled items, rows stay claimed; redeploy/undeploy unchanged (D-063 amended); FlowWorker.unsettled()
Checks: verify -Pit (no bench) VERIFIED_PASS 3367 tests, 364 fresh XML · coverage no decrease · PIT FlowWorker 105/108 (survivors
pre-existing :318/:342/:361) · hand mutants M1–M3 killed, M4/M5b equivalent (agent/work/FIX-142-mut.log)
Review: r1 FAIL (D-114..D-120 not recorded; M5 run against a fixture without a non-worker flow), r2 PASS
Decisions: D-114..D-120 (owner answers to F-29) · Opened: FIX-152 (Node javadoc)
Next: FIX-145 (pg_xact_status failures isolated per xid)
## 2026-11-09 — FIX-139 durable creation of the journal, segments and key directories (review F7.5, F-29) — done, review PASS (r2)
context compacted (tree re-verified with git status/log)
Test first: JournalIoTest (4), JournalDurableDirTest (4) (red agent/work/FIX-139-red.log); implementer's JournalSegmentsEntryTest
.aFirstStartForcesTheJournalDirectoryOnceSegmentsIsThere approved with M6r1 as its red
Changed: JournalIo.createDirectoriesDurably — creates each missing level and forces its parent, forces the directory last; used for
the journal dir, segments/ and the master-key dir; spill keeps plain createDirectories (comment, loss changes nothing durable)
Checks: verify VERIFIED_PASS 3047 tests (FIX-139-verify.log) · coverage no decrease · hand mutants M1–M7 killed · PIT helper 5/5
Review: r1 FAIL (implementer's test not approved in the packet), r2 PASS · Opened: FIX-151 (javadoc on a not-retried force; `..`)
Next: FIX-142 (node shutdown awaits bounded worker settlement)
## 2026-11-09 — FIX-140 recovery pins agree with sweep pins (review F7.1, F-29) — done, review PASS (r3)
Test first: SegmentSweepIT.aNormallySweptKeptRefDoesNotRefuseRestart (red: ACCEPT_LOSS refusal), controls
.aRegisteredSegmentWhoseFileVanishedStillRefuses, .aDlqHeldRefStillPinsItsSegment (agent/work/FIX-140-red.log)
Changed: PgSegmentRows.PINS_BEYOND — a kept ref pins at recovery only while its segment row is registered; work rows always pin;
guard comment per branch (key share / active segment + open-save set / FIX-150 window); SegmentRows javadoc; CORE-3 item 9 amended
Checks: runtime -am verify -Pit VERIFIED_PASS 3035 tests (FIX-140-verify3.log) · coverage no decrease · hand mutants per predicate killed
Review: r1 FAIL (node predicate unpinned, guard comment, docs), r2 FAIL (comment missing the FIX-150 window), r3 PASS · Opened: FIX-150
Next: FIX-139 (journal parent-directory fsync)
## 2026-11-06 — P2-15b send bounded by the fence; x-message-id; split ids (D-113 amends D-100) — done, review PASS (r2) · Deviation: F-28
Test first: FlowBuilderSendDeadlineTest (7), SendFencePropertiesTest (jqwik), NodeLeaseTest.openNanos…, ProducerTest, ComponentContract
(3 cases, http + mock + recording), HttpComponentTest (2), SplitTest (jqwik); red agent/work/P2-15b-red.log
Changed: api Deadline, Producer.send(e, d), Envelope.H_MESSAGE_ID / outboundId(); core Fence, FencedWait abandon, TargetLimit permit
follows the raw future, Split ids <parent>.<i>; http Timer constructor, cut by reset, timeoutMs → retryable Nack; mock/recording deadline
Checks: reactor verify -Pit VERIFIED_PASS 3601 tests (P2-15b-reactor2.log; run 1 NodeIT:1425 freePort race → FIX-149) · coverage no
decrease · PIT api 188/189, core 613/622 · hand mutants 39/40 killed (C3 equivalent)
Review: r1 FAIL (null answer leaked the fence timer; NodeLease:165 boundary unpinned), r2 PASS
Decisions: D-113 (draft) · Opened: FIX-148, FIX-149 · owner: benchmarks paused until a dedicated machine; message-id model low priority
Next: core fixes from F-29 (FIX-140, 139, 142, 145), then P2-15c ledger, P2-16, CORE-7
## 2026-11-06 — P2-15a node lease and self-fencing (D-101 → D-112, F-28) — done, review PASS (r3) · Deviation: F-28
context compacted (twice, plus two container restarts; tree re-verified with git status/log before continuing)
Test first: NodeLeaseTest, NodeLeaseFencePropertiesTest (jqwik), MembershipContract + PgMembershipIT, PgWorkQueueIT/InMemoryWorkQueueTest
liveness/doubt/rejoin cases, FlowWorkerFenceTest, PgWorkQueueStatementsIT, SegmentSweepIT, NodeIT notices (red agent/work/P2-15a-red.log, r1 P2-15a-r1-red.log)
Changed: V14 (nodes, work.doubts); core node (Membership, InMemoryMembership, NodeLease, FencedException); PgMembership; claim/TAKE
liveness + doubts; fence on claim, send, park, settle; renew bounded at L/2 with reconnect; rejoin waits for the old lease end
Checks: reactor clean verify -Pit VERIFIED_PASS rc 0 (P2-15a-r2-reactor.log) · coverage no decrease VERIFIED_PASS · PIT core
node/queue/flow/Parking 633/642 VERIFIED_PASS · hand mutants M-A..M-K killed
Review: r1 FAIL (rejoin with another lease, unfenced park/claim, docs), r2 FAIL (F-28 "never" vs FIX-135), r3 PASS
Decisions: D-112 (draft) · Opened: FIX-133, FIX-134, FIX-135
Next: P2-15b (message-id header, split ids, send bounded by the fence, kill -9 ledger); r2000 rerun to show the lease costs nothing
## 2026-11-05 — FIX-127 sweeper's pinned check linear in work + candidates (F-27 I3) — done, review PASS
Test first: PgSegmentRowsSweepPlanIT (generic + custom, 49 920 work rows, 200 candidates; red agent/work/FIX-127-red.log);
implementer's PgSegmentRowsSweepRecheckIT (a pin committed between lock and delete keeps the segment).
Change: PgSegmentRows PINNED = one materialized CTE (live work segments ∪ fresh or DLQ-held kept refs), LOCK / DELETE anti-join
against it; no index, no migration; the later-snapshot re-check kept. EXPLAIN 74 k → 670/690 buffers, ~900 → 18–37 ms.
Checks: reactor verify -Pit exit 0 (3455, 392 fresh); coverage no decrease; M1, M3, M4 killed, M2 (`materialized`) equivalent.
Preview rerun (moved from FIX-130): RESULTS "FIX-130 + FIX-127 preview" — r2000 drains (≈ 1 890 completes/s, was ≈ 375), ceiling
≈ 1 800–1 900 msg/s, CPU-bound; ACK p50 79 ms at r2000.
Review: FAIL r1 (rerun not recorded), PASS after the run. Found: FIX-132 (jqwik log line).
Next: CORE-17 / D-111 for ACK latency, node CPU split; P2-15 → P2-16 → CORE-7 for commits per message.

## 2026-11-05 — FIX-130 CLAIM bounded by its room, node sessions without JIT (F-27 addendum) — done, review PASS
Test first: PgWorkQueueClaimPlanIT (generic + custom, 50 k rows), NodeConnectOptionsIT.theNodesPooledSessionsRunWithoutJit
(test-author; red agent/work/FIX-130-red.log); implementer's PgWorkQueueNextOfKeyPlanIT (red agent/work/FIX-130-M4-next-in-form.log).
Change: Node.connectOptions adds startup property jit=off; PgWorkQueue CLAIM and CLAIM_NEXT_OF_KEY take rows by
`w.seq = any(array(<eligible … limit … for update skip locked>))` — no seq scan of `work`; fence, ordering, SKIP LOCKED unchanged.
Checks: reactor verify -Pit exit 0 (3451, 390 fresh); coverage no decrease; mutants M1..M4 killed. EXPLAIN figures in the commit body.
Review: FAIL r1 (rerun clause unmet → moved to FIX-127; fast-path test unapproved → approved), PASS r2. Found: FIX-131 (keyed claims).
Next: FIX-127 sweeper, then the r2000 rerun.

## 2026-11-05 — CORE-15b the open-loop record measures the database (F-27 I1) — done, review PASS
Test first: OpenLoopSamplerTest, DbSamplerTest, DiskSamplerTest, CpuSourceClusterTest, NodeBenchMainSettleProbeTest,
NodeBenchMainOpenIoOptionsTest, BenchRunScriptOpenDbTest, NodeBenchOpenDbSeriesIT (test-author; red agent/work/CORE-15b-red.log);
implementer's DbSamplerCasesTest, CpuSourceClusterCasesTest, NodeBenchMainSamplersTest, DiskSamplerCasesTest, OpenLoopOffloadedTest, DbSamplerPgIT.
Change: per-second `db` series (commits, pg_stat_wal, `work` tuples/vacuum, top statements by window time) on the sampler's own
connection; `io` from /proc/diskstats; `pgCpu` over the whole cluster; samples in order; settle probe work-first, 1 s pause.
Checks: reactor verify -Pit exit 0 (3442, r0); bench verify -Pit r1 exit 0 (3135; earlier runs FIX-121 ×2, PgIntakeJournalIT:351 ×1 → FIX-128);
coverage no decrease; mutants M1..M9, M11 killed, M10 survives (memory only, accepted).
Review: FAIL r1 (sample order unpinned; RESULTS lag sentence wrong), PASS r2. Found: FIX-128, FIX-129. The PROGRESS entry was
missing from its commit aa4a717 (a script error) and is added with FIX-130's ledger fix.
Preview rerun (c4b197a, RESULTS "CORE-15b preview"): CLAIM 171 ms per call at r2000 → FIX-130.

## 2026-11-05 — FIX-124 open-loop record kept when the settle misses, recording level option — done, review PASS
Found by the owner's preview run (ack 5 000 msg/s, recording all: settle timed out, no JSON; ~470 k of ~3.15 M accepted, work 376 k left).
Test first: NodeBenchMainOpenSettleTest, NodeBenchMainRecordingLevelTest (test-author; red agent/work/FIX-124-red.log); implementer's
NodeBenchMainOpenSettleCasesTest, BenchRunScriptOpenLevelTest, NodeBenchOpenRecordingLevelIT, NodeBenchOpenSettleCountsIT.
Change: settleOpen writes settled/settleDeadline/settleTarget/messages/work/receipts, exit 4 when unsettled (wins over 3);
--recording-level none|all (none → flow level off; sync + none refused); run.sh BENCH_RECORDING_LEVEL, BENCH_MAX_IN_FLIGHT, level
in the run name, summary prints level and settled; RESULTS note updated.
Checks: reactor verify -Pit exit 0 (3387, run 2; run 1 FIX-121 flake); bench verify r2 exit 0 (199); coverage no decrease; M1..M11 killed.
Review: FAIL r1 (RESULTS note stale, all/none runs shared a file), PASS r2. Found: FIX-125.
Next: stepped open-loop run, recording none (owner 2026-11-05), then CORE-8 at-least-once half, P2-15 → P2-16 → CORE-7.

## 2026-11-05 — CORE-15 open-loop load harness (GATE §CORE-GATE condition 2, P2-14 (b)) — done, review PASS
Test first: OpenLoopScheduleTest, OpenLoopTest (FakeClock/FakeTimer), NodeBenchMainOpenOptionsTest, CpuSourceTest, NodeBenchOpenEchoIT,
NodeBenchOpenAckIT, BenchRunScriptOpenTest (test-author; red agent/work/CORE-15-red.log); implementer's OpenLoopAccountingTest,
NodeBenchMainOpenRunTest, BenchRunScriptOpenDefaultsTest, NodeBenchOpenEchoCpuIT.
Change: NodeBenchMain --rate/--ramp/--window/--max-outstanding/--pg-cpu-source, scenario echo; OpenLoop (fixed schedule, latency
from the scheduled send time, saturation invalidates, backlog = accepted − partner receipts per second); CpuSource (cgroup or pid,
null + reason when unreadable); LatencyStats rank ceil(p·n/100); run.sh scenario `open`; RESULTS method note.
Checks: reactor verify -Pit exit 0 (3368, 367 fresh reports); coverage no decrease (bench 97.8/97.2); mutants M1..M8 killed.
Review: PASS r1. Found: FIX-123 (sample budget, monotonic clock, clamp pin, timeout label, pg CPU scope).
D-054 data: class B (scout ×1, test-author ×1, implementer ×1, reviewer ×1); rounds 1.
Next: preview run at 5 000 msg/s (owner 2026-11-05), then CORE-9/12.

## 2026-11-05 — CORE-6b intake group commit (D-109, FIX-95a) — done, review PASS
context compacted; re-verified with git status / git log before changing anything.
Test first: PgIntakeGroupIT (19), PgIntakeGroupPropertyIT (jqwik), NodeConfigIntakeGroupTest, BatchingWriterTest +5, BatchingWriterPropertyTest +1,
PgWire holdNextConnection / fatalReplyTo (test-author; red agent/work/CORE-6b-red.log, -r1-red, -r2-red); implementer's PgIntakeGroupEdgeIT (8), BatchingWriterWeightTest.
Change: core BatchingWriter weight + batchBytes; NodeConfig.IntakeGroup + FLOTTILA_INTAKE_GROUP_* (draft until CORE-GATE); PgIntake gathers
saves into one transaction (unnest claims sorted by flow/key, ON CONFLICT DO NOTHING, failed group re-runs survivors one by one, ERROR
reply to COMMIT = known abort, FATAL/PANIC/lost = in doubt until pg_xact_status); groupStats(); ARCHITECTURE §4, D-082, D-084, D-071 amended.
Checks: reactor verify -Pit exit 1 (3317, 1 failure = FIX-121 NodeBenchMainWarmupIT:111 float flake, not intake); class rerun exit 0, 5/0/0
(agent/work/CORE-6b-r2-bench-rerun2.log); coverage no decrease; PIT core batch+flow 529/535; mutants r1 R1..R7, r2 S1..S3 killed.
Review: FAIL r1 (re-run order, keyed duplicate in a poisoned group, SINGLE by value, refused COMMIT in doubt), FAIL r2 (FATAL reply as
known abort could lose a committed body; R1 only a hang), PASS r3.
Found: FIX-122 (PANIC unpinned). D-054 data: class C (test-author ×1 resumed ×3, implementer ×1 resumed ×2, verifier ×1, reviewer ×1 resumed ×2); rounds 3.
Next: next ready CORE row (CORE-5 waits for P2-15).

## 2026-11-04 — CORE-6a intake round trips: prepared-statement cache, pipelined turns (D-109, F-26, FIX-95b) — done, review PASS
Deviation: F-26 (the node pool did not cache prepared statements as §4 Writer says; code brought to the document).
Test first: PgIntakeRoundTripIT (8), NodeConnectOptionsTest, AtLeastOnceIntakeIT.theNodesPoolParsesEachIntakeStatementOncePerConnection,
PgWire turn counting (test-author; red agent/work/CORE-6a-red.log); implementer's NodeConnectOptionsCacheLimitTest.
Change: Node.connectOptions (cache on, SQL limit 8192: KEEP is 2514 chars); PgIntake turns BEGIN | claim + first-id | rows | abandoned
check | COMMIT alone; PgBatchSink pipelined write(SqlClient, List). Warm connection: unkeyed 3 waits, keyed 4 (was 8+).
Checks: reactor verify -Pit exit 0 (3310 testcases); coverage no decrease; mutants M1..M12, V1..V3 killed, M8/M9 equivalent.
Review: FAIL r1 (claim turn's own cause and the sink's statement order unpinned), PASS r2.
D-054 data: class B (designer ×1 for CORE-6, test-author ×1 resumed ×1, implementer ×1, verifier ×1, reviewer ×1 resumed ×1); rounds 2.
Next: CORE-6b [C] packet (group commit, D-109).

## 2026-11-04 — CORE-4d segment sweeper, journal bound with 503, open-save outcomes, 4c carries (D-105) — done, review PASS
context compacted; re-verified with git status / git log before changing anything.
Test first: SegmentSweepIT (+r1/r2 cases), JournalBoundIT, IntakeAppendWaitIT, GiveUpRaceIT, LargeBodyCrashTest, JournalSpillBoundTest,
SegmentSweepUnlinkTest, NodeConfigJournalSweepTest, RetentionIT +1, NodeJournalIT +1 (test-author; red agent/work/CORE-4d-red*.log, -r1-red, -r2-red).
Change: SegmentSweeper/SweepConfig (interval, grace, keepMaxAge), JournalFullException; bound on segments + spill + reservations
(intake edge max − max/16, spill counted per chunk, 503 + Retry-After, 80 % WARNING); pg_xact_status settles in-doubt commits;
core Intake.save takes Future<?> abandoned; dir-force generations; giveUp from the cut `for update`; per-extent bytes, unlink
pending set, NoSuchFile = unlinked; stored() swept fallback keeps corrupt/key failures; Retention BODIES limited batch.
Checks: reactor verify -Pit exit 0 (3265, r2); coverage no decrease; reviewer and new-guard mutants killed (r1, r2 logs).
Review: FAIL r1 (spill unbounded while streaming, stored() race, sweeper wiring untested), FAIL r2 (stored():442 rethrow unpinned), PASS r3.
Found: FIX-121 (bench float flake); IntakeAppendWaitIT wait fixed by its author in r1.
D-054 data: class C (test-author ×1 resumed ×4, implementer ×1 resumed ×2, reviewer ×1 resumed ×2); rounds 3.
Next: CORE-5 per BACKLOG.

## 2026-11-04 — CORE-4c3 pinned-cut start refusal, ACCEPT_LOSS, read channels, start carries (D-105) — done, review PASS
Test first: api BodyLostExceptionTest, core ReplayTest (+2), JournalReadChannelsTest, StoreReadKeyCheckTest, JournalFreeSpaceTest,
JournalCloseAndDirForceTest, StoreAppendWaitTest, LostBodyReadIT, NodeJournalIT (+13), LostBodyAdminIT, NodeConfigJournalLimitsTest,
MigrationsIT V13 (test-author; red = test-compile + shimmed semantic reds, agent/work/CORE-4c3-red.log).
Change: V13 (work.lost_at, journal_losses); JournalStart (room, identity, pins, digest; refusal changes nothing), Recovery plan/apply,
ReadChannels (LRU), record key check, ACCEPT_LOSS give-up in one tx, lost reads (BodyLostException, 409 BODY_LOST, GET 410),
lost sources feed no reference/enqueue/park, MASTER tmp cleanup, generated-key notice, quiet close, dir-force re-check.
Checks: reactor verify -Pit exit 0 (3194); coverage no decrease; 41 + 10 hand mutants killed.
Review: FAIL r1 (append-wait wiring store-only → deferred to CORE-4d; lost sources fed new pins; digest sort unpinned), PASS r2.
D-054 data: class C (test-author ×1 resumed ×2, implementer ×1 resumed ×1, reviewer ×1 resumed ×1); rounds 2.
Next: CORE-4d [C] packet (sweeper, disk bound 503, pg_xact_status, carries in its BACKLOG row).

## 2026-11-04 — CORE-4c2 body store on journal references: intake, DLQ, reads, claim, ledger (D-105) — done, review PASS
context compacted (twice); re-verified with git status / git log before changing anything.
Test first: JournalBlobStoreIT, PgIntakeJournalIT, JournalReadIT, PgDlqJournalIT, PgBodyRefsIT, WorkClaimIT, SchemaIT,
ReferenceEnqueueIT (test-author; red = test-compile 95 errors + semantic reds, agent/work/CORE-4c2-red*.log).
Change: TieredBlobStore on the journal (forced → keepIn(tx)/committed/rolledBack, LocationIndex, RemoteBodyException), SpoolSink
gone, PgBodies read-only, PgIntake on refs with openSaves(), PgDlq/PgWorkQueue on refs (claim by body_node), Retention on body_refs,
core Intake.save keepInput, LateBlobStore; exists() asks Postgres only, admin GET checks it first (r1 decision on the index).
Checks: reactor verify -Pit exit 0 (2711 + 422; run 1 hit the JournalKill9IT test race → FIX-120); coverage no decrease; mutants killed.
Review: FAIL r1 (6: keepInput unpinned, BEGIN-before-force unpinned, failed spill persist set, kept CTE locks, retired twins
missing, exists() from the index), FAIL r2 (admin open() catch removed → 500), PASS r3. Stale CORE-2 worktree removed.
D-054 data: class C (test-author ×1 resumed ×4, implementer ×1 resumed ×4, reviewer ×1 resumed ×2); rounds 3.
Next: CORE-4c3 [C] packet (pinned-cut refusal, ACCEPT_LOSS, channel cache, start carries).

## 2026-11-04 — CORE-4c1 V12, journal master and tenant keys, identity, segment rows, node wiring (D-106, D-107) — done, review PASS
Test first: MasterKeySourceTest, JournalLifecycleTest, JournalSegmentRowsTest, JournalKeysIT, PgSegmentRowsIT, NodeJournalIT,
NodeConfigJournalTest, MigrationsIT V12 (test-author; red = test-compile exit 1, agent/work/CORE-4c1-red.log). CORE-4c split in 4c1/4c2.
Change: deploy/postgres/V12__journal.sql; MasterKeySource (file/env/opt-in generate, hard-link publish), JournalKeys (GCM wrap),
PgSegmentRows, Journal (row before every file, injected id, identity refusal incl. fresh database, close waits, fail-stop for
spill/persist, pre-open insert retried, health); NodeConfig keys; Node loads the key first; compose data volume; api DEGRADED (D-107).
Checks: runtime verify exit 0 (2326); reactor verify -Pit exit 0 (3102); coverage no decrease; 74 + 29 hand mutants killed; PIT api NOT_RUN.
Review: FAIL r1 (fresh database erased the journal; compose without data volume; MASTER generation race; tier < 4096 unstartable;
one failed row insert fail-stopped the journal), PASS r2. Additions in CORE-3 "Added in CORE-4c1".
D-054 data: class C (scout ×1, test-author ×1, implementer ×1 resumed ×1, reviewer ×1 resumed ×1); rounds 2.
Next: CORE-4c2 [C] packet (store on refs, intake, DLQ, reads, claim, ACCEPT_LOSS, ledger; carries in CORE-3).

## 2026-11-04 — CORE-4b large bodies in journal format; S3 blob tier removed (D-105 amended) — done, review PASS — Deviation: format
Test first: LargeBodyTest (12), LargeBodyHeapTest (-Xmx48m), JournalStartTest.spillIsClearedAtStart (test-author; red =
test-compile exit 1, 70 errors, agent/work/CORE-4b-red.log); S3TierIT/S3HeapIT and S3 cases deleted with the tier.
Change: journal Spill (streamed 1 MiB AEAD chunks, hashed once, persist = trailer → force → SegmentRows.insert → ATOMIC_MOVE →
force dir), Journal.stream, JournalRef.length long, LargeRecord, SegmentRows; blob S3 tier, S3Sink, Sink, preferS3 gone;
runtime pom `it` profile (empty heap-bounded-it) removed. Format deviation: index length u64, kind-2 record (CORE-3 "Added in 4b").
Checks: runtime verify exit 0; reactor verify -Pit exit 0 (2988 tests); coverage no decrease (runtime 99.5/99.6); 64+18 hand mutants.
Review: FAIL r1 (int cut of a long ref length; no outside decrypt of kind 2; format changes unrecorded), PASS r2.
D-054 data: class C (scout ×1, test-author ×1 resumed ×1, implementer ×1 resumed ×2, reviewer ×1 resumed ×2); rounds 2.
Next: CORE-4c [C] packet (store over Postgres V12, D-106 key, the 4b/4a carries in CORE-3 "Added in" sections).

## 2026-11-04 — CORE-4a body journal core, no Postgres (D-105) — done, review PASS
Test first: JournalWriterTest, JournalSegmentTest, JournalRecoveryTest, JournalCipherTest, JournalRecordPropertiesTest,
JournalConfigTest, JournalKill9IT (test-author; red = test-compile exit 1, agent/work/CORE-4a-red.log). Container restart mid-task.
Change: flottila-runtime io.flottila.runtime.journal (17 main files: Journal, Recovery, Record/SegmentFormat, ChunkedAead, …);
blob/SpoolCipher delegates to ChunkedAead, behaviour unchanged; implementer's JournalStart/Read/Append/RecordFormatTest.
Checks: runtime verify exit 0 (2206), journal 84/0/0/0, JournalKill9IT exit 0, reactor verify -Pit (no bench) exit 0, coverage
runtime 99.5/99.6 (baseline written for runtime); 69 hand mutants (results.txt), 2 unkillable without a seam (commented).
Review: FAIL r1 (reserve() changed state before Timer.schedule → position hole and close hang; per-record key derivation unpinned),
PASS r2. Design additions in agent/packets/CORE-3.md "Added in CORE-4a".
D-054 data: class C (test-author ×1, implementer ×1 resumed ×2, reviewer ×1 resumed ×1); review rounds 2.
Next: CORE-4b [C] packet (large bodies, sealer index read paths per CORE-3); owner: CORE-2 inline bound.

## 2026-11-04 — CORE-2 ClickHouse payload compression and size bound — done, review PASS — Deviation: parallel worktree
Test first: InvoiceGeneratorTest (generator) and ClickHouseCompressionMainIT (harness on the pinned image); a measurement row.
Change: flottila-bench ClickHouseCompressionMain, ClickHouseHttp, InvoiceGenerator; bench/run.sh `BENCH_SCENARIOS=clickhouse`;
bench/RESULTS.md §CORE-2 (48 cells × 3 runs, 0 failures; proposal inline ≤ 10 MiB, ZSTD(1), merge memory decides; fan-in limit).
Run in a git worktree beside CORE-4a on the owner's order (one-writer rule of AGENTS.md set aside, disjoint paths). Container
restart mid-run: 5 cells re-run on the new VM (2.10 GHz, marked †); recheck with the committed harness reproduced ratio and merge peak.
Checks: verify -Pit exit 0 (2 572 tests), coverage bench 97.4/96.6 (baseline written for bench only), mutant 'MergeParts' red.
Review: FAIL r1 (harness edited after the run, IT did not pin merge/insert numbers, fan-in limit, restart wording), PASS r2.
D-054 data: class B (implementer ×1 resumed ×3, reviewer ×1 resumed ×1); review rounds 2.
Next: the owner decides the inline bound; CORE-4a review r2.

## 2026-11-03 — CORE-3 body journal design (D-105) — done, review PASS — Deviation: F-25
Test first: none (design packet); the red tests of CORE-4a..4d are named in agent/packets/CORE-3.md.
Change: agent/packets/CORE-3.md (segments, per-record AEAD, one group-fsync writer, fail-stop, refs in V12, retention by
reference, disk bound, ACCEPT_LOSS per digest); D-105; F-25; ISSUE-4 (journal key, blocks CORE-4c); CORE-4 split 4a..4d.
Checks: ledger-only change, no build run (NOT_RUN).
Review: FAIL r1 (12: write-order hole, fsyncgate, sweep races, unbounded wait, FIX-35 compare, start checks, tests), FAIL r2
(large-body grace, sealer hand-off), FAIL r3 (ACCEPT_LOSS), FAIL r4 (wording), PASS r5.
D-054 data: class C (designer ×1 resumed ×1, reviewer ×1 resumed ×4); review rounds 5.
Next: CORE-4a [C] — acceptance spec, test-author red tests.

## 2026-11-03 — CORE-0/CORE-1 core-rework brief approved, CORE-GATE conditions (D-103) — done, review PASS
Test first: none (docs only); acceptance = the owner's approval (CORE-0) and a measurable GATE.md §CORE-GATE (CORE-1).
Change: docs/CORE-REWORK.md (owner's brief, approved), GATE.md §CORE-GATE (VM, pass rule = median of 3, 7 conditions),
D-101 (4) amended, D-102, D-103; BACKLOG §Core rework CORE-0..14 + CORE-GATE, P2-14..16, CONSIDER-10/11; ARCHITECTURE status note.
Checks: ledger-only change, no build run (NOT_RUN).
Review: FAIL r1 (18 points: no pass rule, gameable throughput condition, brief contradictions), FAIL r2 (5), PASS r3.
D-054 data: class B (reviewer ×1, resumed ×2); review rounds 3.
Next: D-104 (no measurement of the old core); CORE-3 [C] designer, CORE-2 [B], CORE-9 [B], CORE-15 [B].
## 2026-11-02 — FIX-111 agent work files out of target/ (D-093) — done, review PASS
Test first: none (docs and workflow only); acceptance observed by the verifier (below).
Change: `agent/work/` (gitignored, `.gitkeep`) replaces `target/` for logs, diffs, harnesses, bisect worktrees and `.batch-in-review` in CLAUDE.md, AGENTS.md, WORKFLOW §1/§10, NOTES, HANDOFF, verifier/reviewer/implementer/test-author definitions, memories, diagnosing-bugs, the gated Stop hook; SessionStart install checks the new marker path and keeps the original's backup (owner-sanctioned §10 D exception).
Checks: root `mvn -B -ntp -q clean` exit 0 and `clean -pl flottila-runtime -am` exit 0 leave agent/work/ intact, target/ probe gone (verifier, agent/work/FIX-111-clean-am.log); done-when grep 0 hits; SessionStart install tested in a fake HOME (original → backup; old gated copy → replaced, backup kept; rerun idempotent; failing backup (dangling symlink) → exit 1, hook unchanged); check-ignore -v agent/work/x.diff → .gitignore:12, ls-files agent/work = .gitkeep.
Review: FAIL r1 (installer `;` could overwrite the original hook after a failed backup; ledger stale), PASS r2. WF-2 patch rebased (D-093 moved its context).
D-054 data: class A (verifier ×1, reviewer ×2); review rounds 2.
Next: owner decides; a new session picks up the Stop-hook marker path.

## 2026-11-02 — WF-1 workflow skills from mattpocock/skills (D-091) — done, review PASS — Deviation: hook dropped
Test first: none (docs and agent workflow only). Owner request ("aplikuj patch WF-1"); applied and committed by the architect, an owner-sanctioned §10 D exception. Context compacted.
Change: .claude/skills/ (retro, writing-for-agents, diagnosing-bugs, grill-handover, MIT licence); reviewer, test-author and implementer definitions (tdd anti-patterns, FIX-row pointer); memories; NOTICE.
Hook: the command guard (force push, history rewrite, discarded work, reactor-root `mvn clean`) failed review r1 (regex), r2 (blanking parser) and r3 (lexer: `#` comments, `mvn -e`, `-pl X -am`, dir/glob pathspecs, untested `or` arms); owner dropped it; kept unapplied in agent/patches/guard-commands-hook.patch.
Found: `clean` with `-am` cleans the root `target/` too; the root cause of the P1-08 r2 loss goes to FIX-111 [A] (agent files out of target/).
Checks: no production code; licence header matches upstream (curl raw LICENSE, observed); `git apply --check` of the hook patch clean.
Review: FAIL r1–r3 (hook), FAIL r4 (retro still named a PreToolUse hook; NOTICE missed the tracked patches), FAIL r5 (FIX-111 grep could not come back empty), PASS r6.
D-054 data: class B (raised from A after r2); reviewer ×6; review rounds 6.
Next: owner decides; then FIX-111 [A].

## 2026-10-29 — P2-08b durable ACK measurement after one transaction — done, review PASS — Deviation: F-18
Context compacted (once, mid-review); re-verified with git status / git log -3.
Test first: bench NodeBenchAckCommitsIT, BenchRunScriptTest, NodeBenchMainIT settings case (red: target/P2-08b-red.log); implementer-authored BenchRunScriptCommitDelayTest, NodeBenchAckCountersIT approved after the fact.
Change: NodeBenchMain records six pg_settings, poolSize, commitsPerMessage, walSyncsPerMessage; run.sh ack loop c=1/8/64, BENCH_COMMIT_DELAY via ALTER SYSTEM + EXIT-trap reset. bench/RESULTS.md "P2-08 — durable ACK, one transaction".
Result: c=1 p99 10.58 → 8.80 ms; FLOOR (pg_wal on tmpfs, not durable) 8.04 ms → 5 ms target missed, storage not the limit; c=64 msg/s 833 → 733 (−12 %); commit_delay < 10 % on all runs, not in compose. No ISSUE-3.
Docs: F-18 (major), ARCHITECTURE §7 Sizing restated + changelog; FIX-95 [C] (recover load throughput), CONSIDER-6 (jqwik alternative, owner).
Checks: `mvn -B -ntp verify -Pit` VERIFIED_PASS exit 0 (target/P2-08b-verify.log); BenchRunScript* 6/6 after the comment fix (target/P2-08b-r2-script.log); cleanup state target/P2-08b-cleanup.log; pg_test_fsync target/P2-08b-fsync2.log; coverage no decrease.
Review: FAIL r1 (−18 % approximation, FLOOR not labelled, overclaim, c=64 overlap, commit_delay rule, cleanup unevidenced, pg_test_fsync path unlogged), PASS r2.
D-054 data: class B (designer ×1, test-author ×1, implementer ×1 + 1 fix, verifier ×2, reviewer ×2); review rounds 2.
Next: P2-02L [C].

## 2026-10-29 — P2-02L poller lease held by the node (D-083) — done, review PASS
Test first: runtime PollerLeasesContract (in-memory + ValkeyPollerLeasesIT on valkey:8), InMemoryPollerLeasesPropertyTest, LeasedConsumerTest, DeploymentsPollerLeaseTest, TwoNodeSftpLeaseTest, NodeIT P2-02L (Valkey / in-memory + WARNING), NodeConfigTest, api ComponentTest, SftpComponentTest (red: target/P2-02L-red*.log).
Change: api `Component.polls` (default false, sftp true); runtime lease.PollerLeases + InMemory/Valkey (Lua EVAL, PX); deploy.LeasedConsumer (hold per tick on a virtual thread, validity from t0, clock re-read after start, fresh consumer on regain, stop → release); Deployments wraps polling consumers; NodeConfig Leases (FLOTTILA_VALKEY_URI redacted to scheme://host[:port][/db], TTL/RENEW refusals); Node wires Valkey or in-memory. vertx-redis-client 5.1.10 (Apache-2.0/EPL).
Docs: D-083, §4 pollers sentence, §7 Poller leases row, changelog; BACKLOG Done-when restated (≤ ttl + renew); FIX-96 (standby visibility), FIX-97 (rediss TLS).
Checks: `mvn -B -ntp verify -Pit` VERIFIED_PASS 2380/0/0/0 with report dirs cleared (target/P2-02L-verify3.log), +1 adopted NodeConfig case after it (NodeConfigTest 70/0, target/P2-02L-r3-adopt.log); coverage no decrease; hand mutants 43 + 44 + 11 (target/P2-02L-mutants*.log), PIT api Component 1/1; lease classes 20/20 ×3.
Review: FAIL r1 (`?password=` printed; delay from a clock read before the start; failed start not stopped; docs overlap), FAIL r2 (hostless URI leaked the password), PASS r3.
D-054 data: class C (designer ×1, test-author ×1 + 6 follow-ups, implementer ×1 + 2 fixes, verifier ×2, reviewer ×3); review rounds 3.
Next: P2-02D [C].

## 2026-10-29 — P2-02D ingress dedupe by a consumer-supplied key (D-084) — done, review PASS
Test first: PgIntakeIT (12 dedupe cases incl. two lock-held races, own scope/key, row gone), DeliveryTest (duplicate answers M1, prop as arrived, absent/empty, wrongly typed → Nack), SequentialIntakeTest.refusesADedupeKey, SftpComponentTest, DeploymentsDedupeTest, SftpIngressDedupeIT (file delivered twice = one message, one item), MigrationsIT V8, PollingConsumerContract dedupe case (red: target/P2-02D-red*.log).
Change: api `Component.dedupeBy` (sftp `sftp.fileKey`), Ack javadoc; core Intake.save(…, dedupeKey) → Future<String>, Delivery reads the prop guarded and answers Accepted(saved id); V8 processed_ids(scope, sha256 key_hash); PgIntake claims the key first in the tx, a duplicate rolls back, evicts its local body, answers the first id; sftp move suffix from the accepted id.
Docs: D-084, §4 two sentences, §7 Component block (`polls`, `dedupeBy`), changelog; P2-07 retention note; FIX-98.
Checks: `mvn -B -ntp verify -Pit` VERIFIED_PASS 2415/0/0/0, report dirs cleared (target/P2-02D-verify2.log); coverage no decrease; hand mutants 20/21 + 5/5 (target/P2-02D-mutants*.log; X4 later killed).
Review: FAIL r1 (FIRST_ID's WHERE unpinned; typed-prop read unguarded), PASS r2.
D-054 data: class C (designer ×1 (resumed at the turn limit), test-author ×1 (resumed) + 1 fix, implementer ×1 + 1 fix, reviewer ×2); review rounds 2.
Next: next ready row (FIX-95 [C] or a FIX row).

## 2026-10-29 — P2-05 gate compose stack (D-085) — done, review PASS
Test first: runtime ComposeStackTest (9 cases; red: target/P2-05-red.log, secret case target/P2-05-red2.log).
Change: deploy/compose/docker-compose.yml — postgres:17.11, valkey 8.1.11 (persistence off), SeaweedFS 4.50 (S3 identity from env), apache/kafka 3.9.1 (not wired), atmoz/sftp (digest), node (dev, S3 + Valkey, token `:?` from env), echo, one-shot `flottila deploy`, clickhouse under `history`; README; CI `stack` job; D-085 with image licences.
Observed on this VM (target/P2-05-stack2.log): `up -d` 13.49 s, /health green 13.56 s, deploy exit 0, demo listed, POST :8080/in 200 from the echo, clickhouse healthy, `down -v` clean.
Checks: `mvn -B -ntp verify -Pit` VERIFIED_PASS (target/P2-05-verify.log); ComposeStackTest 9/0 (target/P2-05-test3.log); coverage no decrease.
Review: PASS r1. Raised FIX-99 (Kafka not-wired assertion, CI up seconds, HANDOVER env note).
D-054 data: class A (scout ×1, test-author ×1 + 1, implementer ×1 + 1 fix, verifier ×1, reviewer ×1); review rounds 1.
Next: P2-06 [A].

---

End of this file. The older entries are in `docs/archive/PROGRESS-until-2026-11-03.md`, oldest first. Both files hold entries dated 2026-10-29 to 2026-11-03, because two branches were worked in parallel.
