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
