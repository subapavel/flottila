# ISSUE-13 — one set of test rules for the platform and the mapping: the proposal
Task: MAP-9
Date: 2026-11-16 · answered and closed 2026-11-16 (D-164)

## What is needed
The owner's answer to the five questions at the end. The owner asked for coverage rules for the
mapping as well, and for a proposal of how the two areas' test rules become one ("určitě chci taky
pokrytí, navrhni jak to sladit", 2026-11-15). Nothing tightens and no rule file changes before the
answer.

**Words used below.** The *engine* is the mapping's library that runs a mapping; the *service* and
the *editor* are the development harness around it (D-158). The *corpus* is the set of comparison
cases: an input, a mapping and the output that is expected. The *ratchet* is a set of committed
files under `mapping/ratchet/`: lists with one line per comparison case that must pass, and tables
with one row per module holding its coverage figures and its count of surviving mutants. The
mapping's rule is that a line never leaves a list and a figure never gets worse. *GitHub's checks*
are the workflows GitHub runs on a push (CI).

**What was measured** (2026-11-16, commit f1be875c; the table is `agent/work/MAP-9-numbers.md`).
- The engine: every measured module is above the platform's strictest floor, 90 % of lines and of
  branches. The lowest line figure is `engine/structure` at 93.22 %, the lowest branch figure
  `engine/mapping` at 98.92 %. The service measures 98.56 % and 96.26 %.
- Mutation testing (a tool breaks the code on purpose, one small change at a time, and a test must
  notice; a change no test notices is a surviving mutant): the seven engine modules that run it
  score 98 to 100 %. The platform's floor is 80 %.
- The editor: its unit tests reach about a third of its code (36 % of lines); the two directories
  that have a floor are at 100 %.
- Two engine modules, `udf-api` and `udf`, have no code and no test yet.

**What the measurement found beside the numbers.**
1. The ratchet is checked by none of GitHub's checks. Its check ran in the gate of the mapping's
   old repository, which the root's process does not run. Today only the fixed floors stand
   between the mapping and a drop.
2. Run on GitHub as it is, that check would compare a commit with the ratchet files the same
   commit carries: a push that lowers a row together with the code would pass. On the old
   repository an integration branch that did not move gave the comparison its other side; pushes
   to `main` have none.
3. What "behaves the same as SAP" rests on. The build never runs SAP PO or SAP Cloud Integration.
   It compares the engine's output with recorded outputs. Of the 360 corpus cases, 148 carry an
   output observed on a real system, 40 are taken from documentation, 171 are decided by the
   project and 1 is assumed.
   - The observed cases: 147 of the 148 name a recorded output of both systems; one
     (`target/max/002`) rests on PO alone.
   - The assumed case (`target/minOccurs/004`) expects an error. PO 7.5 and Cloud Integration both
     give an output and no error. The case file says so: the rule is undecided (the mapping's
     F-38) and the owner deferred the case past milestone 1 (its F-53; #220 and #222 of the old
     repository). It is a listed case: today the build holds the engine to a behaviour that
     knowingly differs from both systems.
   - The observation logs of 14 of the 15 sets hold 190 requested runs (the fifteenth lists none;
     its cases name their recorded outputs themselves). Read by the latest entry of each run,
     none is waiting. Eight are marked as done on neither system, each with its reason: seven
     cannot be drawn in either system's editor (the counter function takes no input there), and
     one, a JSON null case, the owner excluded.
     Corrected 2026-11-16 (MAP-14, its review): this count linked the entries of a run by case,
     and several mappings test one case. Linked by mapping and input the logs hold 193 requested
     runs, and one more of them is not done on Cloud Integration: `mc-opt` on `minOccurs-004.xml`,
     blocked there by a lock and never made again (PO's run is recorded). That qualifies "none is
     waiting" above: this run's latest entry on Cloud Integration is a blocked one. It is on the
     follow-up list (`mapping/corpus/observations/follow-up-2026-11.md`, part 4). The eight stand;
     no item of the proposal rests on the count.
   - Marks on recorded Cloud Integration outputs, by the latest entry of each run: 28 say "runtime
     provenance unverified" (21 on observed cases, 7 on decided ones), 3 "partial, time zone",
     1 "input normalization unverified".
   - A second comparison, 323 cases, checks that the runtime agrees with Saxon-HE 12.5 on the
     exported XSLT.

   (Counted from the corpus and the observation files on 2026-11-16, the logs by a script that
   links the entries of one run; no build counts it.)

**The proposal.**
1. **Floors.** Every engine module that has code takes 90 % of lines and 90 % of branches - the
   floor of the platform's two core modules, `rail8-api` and `rail8-core`. Today seven of them
   have 90 and 80, and two (`structure`, `structure-import`) have 80 and no branch floor. It costs
   nothing today; `engine/structure` keeps three lines of margin (110 of 118 covered). The service
   keeps its floors until it goes (D-158), and so does the editor, as the owner was told on
   2026-11-15 (ISSUE-9); the comparison harness and the benchmark stay outside the floors, as
   today. Marked `draft — review at hand-over` until the design of how the mapping joins the
   platform (MAP-1) says where the engine's modules live.
   The other way: the platform's general 75 % as a lower bound only. Then nothing rises except the
   two modules without a branch floor, which get 80 and 75.
2. **No decrease, checked on every push.** GitHub's checks run the ratchet's check after the
   mapping's build, with two repairs that make it a real check: the commit's ratchet files are
   compared with those of the last commit the check ran on - no coverage figure lower, no row and
   no corpus case removed, no committed count of surviving mutants raised -, and a report the job
   must produce and does not is a failure, not a pass. The files are regenerated at every task
   commit that touches the mapping, and the reviewer reads their diff. Before the check may fail a
   push it runs for a week reporting only: the ratchet has no tolerance and has never run on
   GitHub's machines, and one timing-dependent branch (the service has 103 of 107) would be a
   false alarm.
3. **Mutation testing.** One threshold for both areas, 80 %, every night, as both already have
   (the platform on `api` and `core`, the mapping on its seven modules). That is all that holds on
   a push: a push runs no mutation testing, so item 2 sees a committed count that someone raised,
   never a new surviving mutant. The mapping's stricter bar - no new surviving mutant in a module -
   is kept in two places: a task that touches one of the seven modules has that module's mutation
   run made by the verifier before the commit, against the committed count (the mapping's old gate
   did this; the script is `mapping/scripts/pit-summary.sh`); and the nightly job compares its
   fresh result with the committed counts by the same script and fails on a new one.
4. **The sentence about SAP** in the rules says what is true: "The mapping's behaviour on the
   listed cases is held by its comparison tests and the ratchet over them: a corpus of cases whose
   expected output is observed on SAP PO 7.5 or SAP Cloud Integration, documented, decided or
   assumed - each case says which - and the agreement of the runtime with Saxon-HE on the exported
   XSLT. The build compares with recorded outputs; it runs no SAP system. A listed case may never
   fail, be skipped or go missing." No counts in the rule file: they change with every corpus
   task. The comparison and the ratchet themselves are not touched.
5. **The rule files.** The root rules name the tier by role ("the mapping engine's modules") and
   point to the mapping's own decision record for the table of modules, so that MAP-1 can move
   modules without a new patch. The lines that are one area's own stay marked so: jqwik, "a
   blocked platform thread is a failure" and `bench/RESULTS.md` are the platform's; the comparison
   with recorded outputs, the ratchet and the benchmark rows in it are the mapping's. A decision
   row records what replaces today's list of the platform's own lines (D-153). Two of the
   mapping's decision records are amended (Q3). The root file's change comes as a patch for the
   owner to apply, after `agent/patches/BRAND-3-steering.patch`, which changes the same file and
   moves these lines.

**The questions.**
- **Q1.** Item 1: 90 % of lines and branches for every engine module with code (recommended), or
  the 75 % as a lower bound only?
- **Q2.** Items 2 and 3: the ratchet's check on every push with the two repairs, after a week of
  reporting only, and the two places that keep "no new surviving mutant"? Recommended: yes.
- **Q3.** May a session amend two of the mapping's own decision records - ADR-0019 (its floor
  table and the ratchet) and ADR-0017 (its build, its checks on GitHub and its gates) - for items
  1, 2, 3 and 5? The standing rule (D-153) is that the mapping is governed by its own decision
  records until the design of how it joins the platform (MAP-1) is accepted, so a session changes
  them only on the owner's word. Recommended: yes.
- **Q4.** Item 4: is the sentence acceptable as worded - with "assumed" in it, and the promise
  limited to the listed cases? And should the open items of finding 3 go on the list for the
  owner's next observation session: the 32 marked outputs, and the one assumed case that both
  systems contradict? Only the owner can run SAP.
- **Q5.** The mapping's workflow (`mapping-ci`) as a check GitHub requires before a push lands.
  Recommended: not now - a workflow that runs only for some paths never reports on the other
  pushes and would block them (ISSUE-9 said the same).

**Not asked** - the architect's, reversible, done with the rest unless the owner objects: the
service gets no new floor (with a yes to Q2 its ratchet row, 98 and 96, binds harder than a floor
would; with a no it keeps today's); the two engine modules without a test get theirs with their
first code; mutation testing on `structure` and `structure-import` is measured first, score and
minutes (MAP-10); a count of surviving mutants for the platform's `api` and `core`: not now; the
mapping's workflow also starts when the nightly workflow's file changes, so that the test of the
wiring sees a removed step.

**An older question on the same rules.** CONSIDER-6, open since 2026-10-29: a replacement for
jqwik, whose engine prints a sentence addressed to AI agents into the test output (FIX-132). The
mapping rejected jqwik for that reason (its ADR-0018); the platform still uses it. An answer there
would make the property-test line common to both areas. It is not part of this proposal.

## Why the agent cannot decide it
The owner asked to see the proposal before anything tightens. Raised floors go one way. The rule
text lives in a file only the owner edits. The sentence about SAP states what the product promises
to someone who migrates a mapping. The runs on SAP are the owner's.

## What was tried
The numbers were measured by the verifier and the mapping's present checks read by the scout; the
designer drafted the rules and the architecture advisor opposed the draft; the architect decided
what is proposed. The reviewer checked the module figures, the corpus counts and the observed and
assumed cases against their files; the counts of the observation logs are the architect's, by
script, and the reviewer checked them in part. The files:
`agent/work/MAP-9-numbers.md`, `agent/work/MAP-9-scout.md`, `agent/work/MAP-9-designer.md`,
`agent/work/MAP-9-advisor.md`.

Where the proposal departs from the draft, each on the advisor's evidence: the ratchet's check on
GitHub gets the comparison with an earlier commit and the failure on a missing report (the draft
ran it as it is, which checks a commit against itself); the stricter mutation bar is kept by the
verifier's run before a commit and the nightly comparison (the draft left only the nightly 80 %);
the sentence about SAP says "or", not "and", and carries no counts; the root rule names the tier
by role; the service gets no new floor; `mapping-ci` is not proposed as a required check; the week
of reporting only is added.

One correction on the way. The observation logs only grow: a later entry of a run replaces an
earlier one, and the entries of one run do not always carry the same key. The advisor and the
architect first counted entries as if they were runs and read 116 "not run" entries as pending
runs; the reviewer showed, in two rounds, that those runs were done later or cannot be done.
Finding 3 links the entries of one run and reads the latest.

Not verified by a run: that GitHub's machines reproduce the measured figures (the week is for
that); the Windows job under a branch floor; how long mutation testing takes on `structure-import`,
the largest module; the nightly comparison step on GitHub.

Measured since (MAP-10, 2026-11-16, commit 71c3b69e, 4 cores; `agent/work/MAP-10-numbers.md`): one
mutation run per module, the time the tool itself prints. `structure`: 33 mutants, all killed,
12 s. `structure-import`: 625 mutants, 598 killed (96 %; 2 of them by time-out), 27 survive in
eight classes, 78 s. `structure-import` ran only with the corpus location handed to the mutation
tool's own JVMs on the command line: its tests read `rail8.corpus`, which the pom gives to the test
plugin alone, and without it the tool stops before the first mutant. Not measured: the same run on
GitHub's machine. The question the numbers carry is the owner's, row MAP-16.

## Default if the owner does not answer
Nothing tightens and no rule file changes. One measurement proceeds in its turn, because it changes
no rule: mutation testing on `structure` and `structure-import` (MAP-10). On a yes the work is
three rows: the floors with the amended table; the ratchet's check on GitHub and in the nightly
job, with its script tests, red first; the patch of the root rules.

## Owner's answer
2026-11-16, in the session, to the five questions as they were put to him in Czech: "souhlas se
všemi doporučeními, pokračuj" - agreed with every recommendation, go on. Recorded as D-164.

- Q1: 90 % of lines and of branches for every engine module that has code.
- Q2: yes - the ratchet's check on every push, repaired, after a week of reporting only, and the
  two places that keep "no new surviving mutant".
- Q3: yes - a session amends the mapping's ADR-0019 and ADR-0017 for items 1, 2, 3 and 5.
- Q4: the sentence about SAP was the proposal, he read it in Czech translation, and it is taken as
  agreed. The second half - whether the 32 marked outputs and the assumed case go on the list for
  his next observation session - carried no recommendation. The architect's reading, told to the
  owner in the session: they are listed; a list obliges no one and the runs stay the owner's.
- Q5: not now.

Not part of the answer and not decided by it (D-164 says so). Whether the mapping's floors and
its nightly mutation threshold are conditions of a phase gate: the proposal did not ask, the
gate's texts stay as they are, and the question comes to the owner with the design of how the
mapping joins the platform (MAP-1). And whether `structure` and `structure-import` join the
nightly mutation run: that is a floor, so it comes to the owner with MAP-10's numbers.

Closed. The work is four rows: MAP-11 (the floors), MAP-12 (the ratchet's check on GitHub and in
the nightly job), MAP-13 (the patch of the rule files, for the owner to apply), MAP-14 (the list
for the next observation session). MAP-10, the measurement, keeps its turn.
