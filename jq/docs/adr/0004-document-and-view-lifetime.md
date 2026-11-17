# Document and view lifetime: a new JsonDoc per read, detach() for what is kept

Status: accepted (2026-11-10, owner, issue #5 "What are the value model and the tape layout?"); amended by ADR-0007 (2026-11-10, issue #6), ADR-0008 (issue #7), ADR-0015 (issue #11) and ADR-0016 (issue #13); amended by ADR-0026 (staleness on close and on a failed read); amended by ADR-0054 (element documents, spec 5)

A view is valid while its document is alive. Every read makes a new `JsonDoc` (~32 B) over the
Workspace's reused tape, and the previous `JsonDoc` drops both its tape and its bytes. A view of a
dropped document raises the stale-view error and never reads a later document's bytes. Only the
document is allocated per read, a forgotten view pins nothing, and the tape can still be reused
(Spike B: a fresh tape per read costs 1.2–1.8× more).

- **`detach()`** deep-copies every view it reaches into built values. It promises a result that
  holds **no document** and **no backing array more than twice its own size**, so a kept 10-byte
  substring cannot pin a 100 MB string. A flag on built containers ("holds a view or a slice of a
  larger array") makes it O(1) when clear. One shape cache per call lets copied records share
  shapes.
- **Values from `input` / `inputs` are detached by default**, so memory stays flat over a stream as
  in jq. jq-ref went from 13 to 14 MB RSS while its input grew from 1.2 to 24 MB. "What shape does
  the IR have, and which analyses run on it?" may later let a value proven not to be kept stay a
  view.

This ADR fixes only what the value model needs. "What are the public API, the host SPI and the
sandbox?" will add the rule hosts follow (sink call, next read or explicit close), sharing a
`JsonDoc` between steps or threads, and which budget `detach()` charges. "What is the execution
protocol?" will add who ends a document during automatic streaming.

## Considered options

- **A generation number in every view**: nothing allocated per read, but each view carries an
  extra int, each access does a compare, and a forgotten view keeps the current document reachable.
- **Keeping every `input` document until the evaluation ends**: no copy, but
  `reduce inputs as $x (…)` grows with the stream until a budget stops it.

## Amendment (ADR-0007, issue #6)

`input` and `inputs` documents get their own Workspace tape, so a read inside an evaluation never
makes the main document stale (`. as $o | input | [$o, .]` → `[1,2]` on jq-ref). Instead of
"detached by default", a value that may view an input document is detached when it enters a hold
that spans a later read. Detaching at every read remains a legal first implementation.

## Amendment (ADR-0008, issue #7)

During streaming that a host enables, the engine ends an element's document when `rest` returns
for that element. A streamed result view is valid only during its sink call. The stale-view error
is a `Termination`, so no `try` catches it.

## Amendment (ADR-0015, issue #11)

`-R` lines, `-Rs` and `--stream` events are built values, not documents on a tape. A slurped
stream is one document with a synthetic root array.

## Amendment (ADR-0016, issue #13)

The host rule: a result is valid during its sink call, and longer only if it views a document the
host holds (a Workspace document until that Workspace's next main read or close, an owned document
until close). An owned document has its own read-only tape and may be shared between steps and
threads. `detach()` inside an evaluation charges bytes built and ticks per KiB; called by the host
after a run, it charges nothing.

## Amendment (ADR-0054, spec 5)

During host streaming each element is read into an element document on the Workspace's reused
element tape, with its root at index 0. Only that document is allocated per element, and it goes
stale when `rest` returns for that element.
