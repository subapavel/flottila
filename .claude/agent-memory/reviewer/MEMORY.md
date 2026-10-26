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
- Testkit twin streams (ChunkedReadStream / GeneratedReadStream): when one twin's guard gets a pinning case, grep the other twin for the same case (FIX-13).
- A "seen-with-X" flag the test sets just before the call proves only "after this line": a second witness at most, never proof of internal state.
- BACKLOG and spec test-class names can be wrong (ChunkedReadStreamTest vs ChunkedReadStreamEdgeCasesTest); grep the method name, not the class. Quiet `-q` logs have no Surefire counts, so ask for the XML count.
- A contract case asserting a negative ("receives nothing") becomes vacuous when its target cannot be reached at all; pin it with a broken-variant self-test, not only a green fixture (FIX-8, ComponentContract:110).
- A red observed once proves the fixture discriminates today; only a permanent self-test (the red kept as an expected failing set, EngineTestKit) pins it (FIX-8, EphemeralAddressComponent:59).
- A null guard ahead of the mutated call makes the mutant equivalent for the null input: mutate the decision, not the call it guards (FIX-8 M10).
- testkit `ComponentContract` hooks: check `ComponentContractSelfTest` has a variant per hook. Nested suites that fail by design rely on Surefire's `**/*$*` exclude, so ask for "no `TEST-*$*.xml`" after verify.
- Take mutant line numbers from the live file, never from `target/<task>.diff`: the diff's hunk numbering drifts from the file (FIX-8 M9: 47 vs 36).
- Before naming the case that kills a mutant, check whether that fixture reads the value at all: `ComponentContractSelfTest.Variant` ignores its config, so a null endpoint passes it (FIX-8 M8).
- A check on a derived value (an HKDF output is always a 32-byte AES key) is an equivalent-mutant site; before asking for a pinning test, check what the input gate (`masterMaterial`) already refuses (FIX-9, SpoolCipher:62).
- When code creates a file and then can fail: check every throw after the create, not only the one the task names (SpoolCipher:68 `write(salt)` still leaves a `.part`; the SpoolSink constructor fails, so drain's `sink` stays null and `abort()` never runs — FIX-14).
- `SpoolCipher` open: only the cause chain ("master key") is asserted, never the message. Treat message mutants there as unpinned until FIX-14.
- Map each clause of a spec rule to a test case. The "added as suppressed" catches are the clause that goes unpinned (FIX-14, SpoolCipher:76/:81).
- A cleanup catch limited to IOException misses unchecked failures between the file create and the hand-back, such as an allocation in a constructor (FIX-14, SpoolCipher:71/:148 — FIX-15).
- To make a delete fail deterministically without root tricks, put a non-empty directory at the path: `deleteIfExists` throws `DirectoryNotEmptyException`.
- JaCoCo marks a line missed when the call on it always throws in tests (no probe after the call), so look at the callee's coverage before asking for a test (FIX-14, SpoolCipher:65).
- When a time conversion like `sec * 1e9 + nano` is new code, ask for a case whose interval crosses the unit boundary; otherwise the multiplier goes unpinned (FIX-2, BlockedPlatformThreadDetector:93).
- A "may block" negative on a thread kind that is never registered is vacuous: pin the thread kind itself (FIX-2, `watchedVirtualThread` → `assertTrue(vt.isVirtual())`).
- When a fix gives each case its own database, mutate the URL and the pool separately — each is pinned by a different case (FIX-5: the pool only by the fresh-database case).
- Read the testcase order in each random-order XML: one of FIX-5's three random runs repeated the committed order and was no new evidence.
- With a resolver plus a deny-all setting, a post-compile "failure recorded" check makes the setting unpinnable by message tests; pin "not fetched / not read" with a counting local server (FIX-6, XsdStepFactory ACCESS_EXTERNAL_SCHEMA).
- XML steps: check compile-time reads besides include/import (DTD entities, XSLT `static` / `use-when` calls to `doc()` / `unparsed-text()`) against the decision's "every file" wording (FIX-6).
- A compile cache keyed by the main file's hash skips any per-flow check on a cache hit; ask what a cache hit bypasses (FIX-6 → FIX-17).
- A thread-scoped hook on a shared object needs two cases: the scope's effect, and its absence after close on the same thread; mutate `close()` and the no-scope branch (FIX-6, CompileConfinement / XsltStepFactory).
- XSLT compile-time reads: the resource resolver covers `doc` / `unparsed-text` / entities; check `collection()` / `uri-collection()` separately — they go through the collection finder (FIX-6).
- A hook installed into a shared Configuration in a constructor: ask what happens if someone later replaces the resolver (FIX-6 → FIX-16).
- A hook that implements two SPIs needs the "no scope, delegate" case for each one; FIX-6 round 4 pinned the resolver and missed the finder (CompileConfinement).
- A delegation test where the delegate answers null cannot tell "delegates" from "returns null"; pick an input the delegate answers non-null (CompileConfinement).
- Saxon 12.4: static `collection()` throws a ClassCastException before any finder; re-probe compile-time collection reads (catalog files) on every Saxon upgrade.
- An unreachable checked-exception catch in a small module costs pp: in validate (~80 lines) two lines are 2.5 pp, beyond D-050's band. Ask early for the module's line count against new catch blocks, and prefer code with no checked exception (FIX-6, ConfinedSchemaResolver:33).
- When a JDK SPI value is built by hand (an `LSInput` with throwing setters), the getters' null/false values are behaviour (Xerces reads character data before bytes). Each one needs an assertion, and the hand-built class needs its own test (FIX-6, SchemaInput).
- Xerces reads an `LSInput` the resolver hands over through its system id even when the byte stream is null and `ACCESS_EXTERNAL_SCHEMA=""`. The resolver's own system id is the confinement, not the belt (FIX-6 M28).
- A cleanup helper run from a `finally` that catches only `IOException` lets an unchecked cleanup failure replace the primary and skip later steps — check the helper's own catches too (FIX-15, SpoolCipher `discard`).
- A driver's failure paths (the follow-up round after a failed claim, the bookkeeping after a failed settle) cannot be mutated by PIT — check that some test fails the queue call there (P1-08b, FlowWorker:179/:198).
- Tests that read a round through `otherwiseEmpty()` leave the round's answer and that path's log unpinned (P1-08b :222/:224).
- A design-rule default (a finite limit) pinned only by `assertDoesNotThrow` is unpinned; ask for the value to be asserted (P1-08c, http `shutdownTimeoutMs`).
- A D-xxx rationale saying ARCHITECTURE is "not yet true" needs F-n + changelog line + PROGRESS `Deviation:` (WORKFLOW §7), not only the decision row (P1-08c).
- `pipeTo` a response after content-length is set: a source failure mid-body leaves a short body on keep-alive; ask for 500 before the head, reset after, WARNING both (P1-09a AdminServer; FIX-22 http).
- A send stage outside the route's catch (encode, header validation on the loop) can leave a request unanswered; check what runs after the try (P1-09a).
- A blanket IAE → 400 also catches IAEs a store throws for its own state (missing object) — check which IAEs reach the mapping (P1-09a).
- An amended in-memory clause tested only by the Pg IT twin is unpinned in core (P1-09a InMemoryDlq cursor).
- Predict the failure mode but let the red decide: the PgDlq out-of-range cursor was predicted 500 and in fact succeeded silently (P1-09a).
- A row naming two failure sites is met only if the diff touches both; "accepted uncovered" must point at code that exists (FIX-21, Delivery.java:247).
- A wrong-expectation fix that waits for an async step before acting removes the window the old synchronous test pinned; re-run the mutant of what that window guarded (P1-09b, Drain count).
- `catch (Throwable)` around a harness call swallows the harness's own timeout `fail()` (P1-09b, NodeIT).
- Ledger splits: grep migration numbers (Vn) across BACKLOG rows and findings when a D-row renumbers them (P1-09d3: P1-09c and F-5 kept V6).
