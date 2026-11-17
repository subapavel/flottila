# Host streaming: element documents on one reused tape, a validating skip, a whole-read fallback

Status: proposed (2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14, spec 5 round 1); amends ADR-0004 (element documents), ADR-0007 (root use) and ADR-0016 (`stream`); decides the hand-out ADR-0023 left to spec 5's grilling (map #18); DEVIATIONS "Streaming" gains a line; the root-use test and its two values by spec 5 round 2 (grilling-adviser for the owner under the delegation of 2026-11-14)

ADR-0007 makes root use an eligibility fact and host streaming a switch the host turns on (F-4).
ADR-0016 gives the switch as `stream(MemorySegment, Bindings, JqSink, Workspace, RunOptions)`,
which throws `IllegalStateException` when `needsWholeDocument()`. ADR-0023 left two questions to
this grilling: how elements are handed out, and whether each gets its own tape (founding §6.7:
"each with its own small tape"). Spike B read 10 MB in 22–24 ms validating only, against 33–35 ms
building the tape.

## Decision

- **One reused element tape.** `stream` reads the target array's elements one at a time, each into
  an element document on the Workspace's reused element tape, with its root at index 0, and runs
  `rest` on that root. The element document goes stale when `rest` returns for that element
  (ADR-0004). A streamed result is valid only during its sink call (ADR-0016), and the bytes-built
  budget restarts at each element (ADR-0017).
- **Outside the target the reader only validates.** The bytes before and after the target array
  are checked against jq's grammar and ADR-0015's limits, and leave no tape.
- **Positions continue from the enclosing document.** An element's depth, line and column count
  from the start of the document: the depth limit counts the levels around the element (F-22), and
  an error's line and column are the whole document's.
- **A target that is no array, or a missing path, falls back** to a whole read of the segment and
  `run` on its root, which gives jq's result: `{"a":{"x":1}} | .a[]` → `1`; `{} | .items[]` →
  "Cannot iterate over null (null)". So host streaming saves memory only on an array target.
- **A stop reads no further.** When the sink stops, `stream` returns false and the rest of the
  segment is not read, so a syntax error after that element is not seen (DEVIATIONS "Streaming").
- **Eligibility is a syntactic test.** Root use, ADR-0007's `<fixed path> | .[] | rest` where `rest`
  uses no outer slot and does not read input, is a test over node kinds and spec 3's Operation
  facts (`path`, `reads`). A filter it does not prove eligible has `needsWholeDocument()` true.
- **Spec 5b builds it**: root use with `needsWholeDocument` (after spec 3's facts), then `stream`
  with the fallback, DEVIATIONS' `test:` rows and `streamAllocationFlat`. 5b's round 2 fixes the
  root-use test. No stream cell joins the gate scenarios (ADR-0019).

## Considered options

- **A fresh tape per element**, founding §6.7 read literally: in Spike B a fresh tape per read cost
  1.2–1.8× a reused one (ADR-0004).
- **Taping the whole document and handing out views of its elements**: no memory is saved.
- **An error for a target that is no array**: a filter jq runs would fail on data no compile-time
  test can see.
- **Reading on after a stop to report a late error**: it reads bytes no result needs, and results
  before a late error already differ from jq (DEVIATIONS "Streaming").
- **A root-use proof beyond the syntactic form**: more filters would stream, but an over-claimed
  eligibility gives a wrong value, while an unproven filter only reads whole.

## Amendment (spec 5 round 2)

Proposed 2026-11-14, grilling-adviser for the owner under the delegation of 2026-11-14.

Root use is tested on `main` after specialisation, over its flattened top-level Pipe chain. The
spine is a run of steps, each KEY with a constant string or INDEX with a constant non-negative
integer, quiet or not, up to and including the first ALL; `rest` is what follows. `rest` must not
may-read (ADR-0007) and must use no slot bound outside it other than a parameter slot, which is set
from the Bindings and cannot view the document; this replaces 'uses no outer slot'. Any other
top-level form reads whole (PathOf, Update, Bind, Reduce, Foreach, Label, Collect, Comma, Try,
Call); PathOf and Update inside `rest` are allowed. The fact has two values, ELEMENTS and WHOLE;
the conservative compile gives WHOLE.

A spine step that meets null or a value of the wrong kind counts as a missing path: `stream` falls
back to the whole read, which gives jq's result or error (`{"a":[1]} | .a.b[0][]` → "Cannot index
array with string ("b")").
