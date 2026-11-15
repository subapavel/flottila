# The editor saves conditionally, sees refused fields on the wire, and refreshes on open

Status: accepted (2026-11-05, spec-5 grilling); refines ADR-0008 (refusals on the wire), ADR-0010 (the editor calls refresh), ADR-0015 (compile endpoint) and ADR-0024 (`If-Match`, a new problem slug). New rather than amended, because spec 4 is still amending those ADRs.

## Conditional save

- The editor sends `If-Match` with the baseline's `ETag` on every Save (`PUT /api/mappings/{id}`).
- When the tag differs from the stored file's canonical tag, the service answers 409
  `urn:flottila:mapping:problem:mapping-changed` and writes nothing. This is not ADR-0024's
  `mapping-exists` 409, which answers a create.
- A `PUT` without `If-Match` stays unconditional (RFC 9110 allows it), so scripts, curl and the
  existing service tests keep working. Only the editor writes in milestone 1, and it always sends
  the header.
- On 409 the editor offers two choices:
  - **Reload**: drop the working copy and its undo history, load the file from disk;
  - **Keep editing**: keep the working copy; the next Save answers 409 again.
- There is no "Overwrite" in milestone 1. Git merges two edits of one mapping.

## Refused fields on the wire

ADR-0008 kept refusals off `/api/structures` "until an editor story asks for them". Spec 5 asks:

- `FieldDto` gains an optional `refusal {code, file, line, column}`, the same refusal the
  structure carries (#92). A field below a refused field carries the refusal nearest the root.
- The structure overview marks a refused row and shows the reason and position on hover.
- The store's link and drop commands refuse a refused source or target field. A model that
  already uses one (after a refresh, or written by hand) still loads; compile reports it.

## Compile

- `POST /api/mappings/{id}/compile` is built as ADR-0015 lists it: the working copy in the body,
  200 with diagnostics, never a 4xx for stages 1–3.
- The `unreachable-box` warning (ADR-0011, ADR-0013) ships with it.

## The packed catalogue is contract

Refines ADR-0026. The build writes the packed function catalogue to
`contract/function-catalogue.json`, committed next to `openapi.json`; the existing empty-diff check
on `contract/` guards it. `GET /api/catalogue` serves the same bytes. The fake `ApiClient`, the
component tests and the default e2e suite read this file, so `ui/` needs no Java build to get the
palette.

## Refresh on open

- The editor calls `POST /api/mappings/{id}/refresh` when a mapping opens, and again from a
  "Refresh structures" action.
- When a fingerprint changed, the returned model replaces the working copy as one undo step; the
  mapping is then unsaved and a notice names the number of orphaned references. When no
  fingerprint changed, the working copy is untouched.
- Save stays byte-faithful (ADR-0010): only refresh re-derives a fingerprint.

## Considered options

- **`If-Match` required (428 without it)**: rejected. It breaks every non-editor caller for no gain
  while the editor is the only writer.
- **An "Overwrite" choice on 409**: rejected for milestone 1. It silently drops the other edit;
  git already merges.
- **Refused fields marked only by compile diagnostics**: rejected. The author would draw a link
  that can never compile and learn it 500 ms later; the schema, not the mapping, must change.
- **Refresh only on request**: rejected. A changed schema would show first as a
  `fingerprint-mismatch` that blocks compile and test, which only refresh can clear.
