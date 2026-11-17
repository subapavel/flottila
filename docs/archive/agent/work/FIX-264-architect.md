# FIX-264 - the architect's notes for the review (2026-11-16)

What happened after step 2, in order. Evidence: `agent/work/FIX-264-evidence.txt` (run 1, then "Run 2 (after fix round 1)").

1. Run 1 was not green. `DeploymentsSecretsTest.producerOptionsResolvedToo` (:314) failed: it expected five producers, two
   of them replay's (D-156). The packet's acceptance 1 had missed the case. Fix round 1 (`agent/work/FIX-264-fix1.md`)
   corrected the expectation to three and named them in the message - a wrong expectation in a test this row did not
   write, so the reason goes into the commit message (CLAUDE.md, Tests).
2. Coverage, run 1: core and runtime read lower by exact ratio (fully covered code had left; `-3-covtable.txt`). The
   packet's acceptance 8 wants a case, never an exception, so fix round 1 added cases on the weakest surviving branches:
   `StepCpuTest` (two cases; `StepCpu`:233 was run-dependent and is now pinned by fixed intervals), `FlowBuilderTest`
   (the unknown delivery mode, :333), `CpuSamplerStartTest` and `PeerReadMalformedAddressTest` (new files, runtime).
   Run 2: core and runtime are higher on both figures (`agent/work/FIX-264-4-covtable.txt`).
3. Mutation: `agent/work/FIX-264-pit-match.txt` holds every unkilled mutant after beside its status before, matched by
   class, method and mutator (line numbers shift). No mutant is unkilled after and killed before. One had lost its only
   covering test with replay (`Parking.lambda$park$1`, :88); fix round 1's two `ParkingTest` cases kill both lambdas of
   :88 (SURVIVED before, KILLED in run 2). Both mutants the test-author showed red by hand: `-fix1-mutant-drop.log`,
   `-fix1-mutant-always.log`.
4. testkit's branch figure is lower in run 2 (397 of 417, against 400 before). The packet's acceptance 8 is amended for
   it, in the packet, dated - an amendment, not a reading: testkit builds on api alone, neither is in the diff
   (`git diff --stat HEAD -- platform/api platform/testkit` is empty; no commit touches either since 750ba7b9), and
   the figure read 401, 400, 398, 397 in four whole-reactor runs on that same code, all of it inside
   `ComponentContract`'s polling lambdas (:279, :393, :510, :515, :550: `soon(() -> ...)`, whose false branch is
   taken only when the first poll precedes the delivery). The row for deterministic cases there, and for `Journal`:1013
   (runtime; covered in run 2 - were it missed, runtime would read 2829 of 2845 branches, 99.4376 %, still above the
   99.4029 % before, so runtime's figure does not lean on it), is FIX-280 [B], written at this close and put first in
   the order. Judge whether this amendment is honest or the row must wait for FIX-280.

Flags for the reviewer (none pre-decided):
- `CpuSamplerStartTest` sets the system property `os.name` and restores it in `finally`; no parallel test execution is
  configured in the reactor (the test-author looked), so no other test reads it meanwhile.
- Reported unreachable, left uncovered: `FlowBuilder`:658; `PeerReads`:304-305, :315-316; `FlowHash`:88-89;
  `PeerSession`:35-36; `SpoolCipher`:185-186.
- Left for FIX-269 (its row gets the note at this close): `Recorder.start(policy, stepName)` at a later step,
  `Delivery.admission()`, the short `PgWorkQueue.enqueue` overload - no main caller after replay.
- The dead `DlqBodiesIT` fixture `inputs`/`Input`; the double gate on Delivery close: say whether either must go here.
- Evidence C's second grep: the word "replay" still in `src/main` (api `Props`, `Body`, `Envelope`, `BodyLostException`;
  core `WorkQueue`:276; runtime `AdminServer`, `PeerSession`, `TieredBlobStore`, `PgWorkQueue`; sim `Verdict`,
  `SimConfig`, `SimSeed`) - hold each against the packet's "Stays" and "Not this row's" lists (FIX-265, FIX-276).
- D-165 (the check `REPLAY_YIELDS_RECORDED` leaves the oracle's enum by deletion) is written at the close; its text is in
  the packet's decision paragraph.
- `.claude/agent-memory/test-author/MEMORY.md` and `implementer/MEMORY.md` carry these agents' own updates from this row
  and from MAP-12a (in flight beside it); they are committed with whichever lands first.
