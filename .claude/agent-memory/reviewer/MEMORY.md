# reviewer — recurring findings by area (seeded from PROGRESS.md at the role split; prune when you add)

- Streams: a stream handed over paused that nobody resumes (TieredBlobStore, P1-06 round 2); an
  attach-time resume that undoes the store's own back-pressure pause (round 3). Look at every
  `pause()` / `resume()` pair across the component ↔ store boundary.
- Bounds: an error-path body or reply buffered without a limit (http producer non-2xx, P1-06
  round 1); a reply above the limit leaving the client response paused until the timeout.
- Event loop: parsing untrusted input (`Long.parseLong` on content-length) or doing I/O on the
  event loop thread; the virtual-thread rule is per message, verticles own sockets only.
- Tests that pass for the wrong reason: timing observed against wall time (P0-04); two fresh id
  generators making an identity assertion vacuous (P1-06 round 5); a mock answering every
  endpoint from one handler (P1-06 round 1); `off`/`yes`/`no` read as YAML booleans (P1-03).
- Rebuild per call where one build was meant (`FlowBuilder.replay` lambda, P1-05).
- Failure paths that forget state: `TieredBlobStore` sink failure not setting `failure` (P1-06).
  Since FIX-7 the order is the rule: the failure is recorded under the monitor before the pause
  and before the promise fails; check that order on every new catch in the store.
- A test that "covers" a branch is not a test that fails when the branch is wrong: name the
  mutants (`sed -i '<line>d'`-style edits of the production file) and have the verifier run them
  with the file restored by `cp`/`touch` (an `mv` restore leaves the mutant class compiled —
  FIX-7 round 2). A PASS without a mutant or a PIT figure on the touched class is a weak PASS.
- Ask first for: the scoped PIT report of the touched classes (a test that kills no mutant is
  fake), the `git diff --stat`, and the packet's named red tests — check they are the ones in the diff.
- Decision under the monitor, call after it: every post-monitor in.resume() (sink, re-check, start()) needs a
  post-check (`if (x && failed()) in.pause()`) and a "what lands in between" question (FIX-10 round 2).
- Comments about failure precedence: check every entry point (fail()'s over() early return drops, FIX-10).
- TieredBlobStore: test a pause after the first one, and the attach and failure paths of the re-check (FIX-10).
- A new entry into a guarded section (start()'s re-issue into pauseAndReCheck) breaks "one at a time" unless every
  setter checks the flag; check each setter, not the packet's invariant (FIX-10 round 3).
- A hold before a call takes effect cannot pin "undone by a later call"; the hold must come after the effect
  (FIX-10 round 3, the again path).
- "Decided" is not "resumed": a guard reading a flag the sink cleared (`!sourcePaused`) does not mean the sink's resume has taken effect (FIX-10 round 4, TieredBlobStore:285).
- A resume in flight (the hand-over) plus a second party's resume decided during it makes two resumes for one pause. Check every resumer against every resume that may still be in flight.
- Read JaCoCo's missed branches in new choreography code before the mutants. An uncovered branch there has twice hidden the bug (D-050).
- A baseline drop beyond the 2.0 band in the task's own new code is a FAIL, even when it was measured and `--write` recorded it.
- A flag set in two places is pinned only by a case where the other setter does not run. Check that each setter has a case of its own (FIX-10 r5, TieredBlobStore:426 vs :339).
- Full JaCoCo coverage of a redundant write proves nothing; trace whether the effect already happened on another path.
- "Only X calls the source" usually means only X resumes it; pauses and failure pauses stay direct. Check that the docs say which.
- One flag for two facts (a pause maybe undone vs a resume owed) forces one remedy for both; round 6's one flag re-paused where a plain resume was owed. One flag per fact, one action each (FIX-10 r6→r7).
- pauses==resumes holds only where no pause is re-issued; an own-thread re-issue adds an idempotent pause call. Assert event order, not counts, in those cases.
- A compound guard (`!pausing && resumeDeferred`) is pinned only by a case where both terms are live together; R11 covered the branch without the other flag (FIX-10 r7, TieredBlobStore:286).
- Before calling a mutant equivalent, find the reachable state where the two versions differ; "covered" is not the test.
- Packet-prescribed comment text needs checking too: test every branch an "otherwise" covers against the code (FIX-10 r8, TieredBlobStore:251 named `again`, dropped `repause`).
- Rewording a half-true comment tends to swap which half is missing; diff it against its sibling comment (TieredBlobStore :250 vs :251).
- Do not read pass/fail from target/surefire-reports while the verifier runs mutants in the same tree.
- Staging that only shifts where an event lands is fine for order-relative assertions; flag it only if a hold may never be reached.
- A fixture that signals "reached" before taking a pending one-shot latch hands that latch to the hold already observed. Take it first, then signal (FIX-10 r9, OwnThreadStream.block).
- A load loop at 29/30 is a finding: match the lost seconds to a 10 s fixture hold, then decide fixture or store.
- An endless re-check loop that records events dies of heap exhaustion, not by timeout. That is a kill (PIT: MEMORY_ERROR) if the restore is proven by `cmp`.
