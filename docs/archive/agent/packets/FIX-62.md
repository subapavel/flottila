# Packet FIX-62 (class A) — `unstarted` leaves discarded flows in `built`

Module: `flottila-runtime`. Row: `agent/BACKLOG.md`, FIX-62, audited LIVE on 2026-11-14.

## The defect

In `Deployments` (restore path):
- `built` is filled at `:797-801`.
- `unstarted(...)` at `:849-857` handles the flows that did not start: it discards them and
  replaces them in `now`, and logs an ERROR at `:853`. It is called at `:806`.
- It never clears them out of `built`. So `built` still holds flows that have already been
  discarded, and the catch at `:829` would discard the same flows a second time.

The invariant the row cites is "a list it stopped is cleared". Double-discard is the bug class
FIX-69 was about in another component: an object whose lifecycle is ended twice.

## Acceptance

1. After `unstarted` has handled a flow, that flow is no longer in `built`, so a later throw on
   the restore path cannot discard it again. The test must make the second discard observable —
   a flow whose discard is counted or which records being closed, so a double discard is a
   different observation from a single one, not an invisible no-op.
2. The ERROR log of a flow that does not start on restore is pinned: the message at `:853` is
   asserted, text and all, the way `TrailTest` pins its WARNINGs after FIX-54. Filter the captured
   log by this case's own identifiers, never by a bare id substring — `"m-w"` being a prefix of
   `"m-wait"` is the trap FIX-54 hit, and FIX-121 hit it twice more.
3. The existing restore behaviour is unchanged otherwise: a restore in which every flow starts
   behaves exactly as before, and the flows `unstarted` did replace in `now` stay replaced.

## Red first

The red for (1) is the double discard: drive a restore where a flow fails to start **and** the
path then throws, and assert the flow is discarded once. If the current code makes that
unreachable, say so in the hand-back with the reason rather than inventing a path — then the red
is (2) alone and (1) becomes a guard test.

## Allowed paths

- `flottila-runtime/src/main/java/io/flottila/runtime/deploy/Deployments.java`
- the matching test files under `flottila-runtime/src/test/java/io/flottila/runtime/deploy/`

## Out of scope

FIX-75 (the unguarded `Schemas.check` at `:1029` and `listensOn` at `:1275`) and FIX-58
(bounding the `records.save(..).await()` at `:810-811`). Same file, different defects. Do not
touch them, and do not "tidy" the surrounding catch clauses.
