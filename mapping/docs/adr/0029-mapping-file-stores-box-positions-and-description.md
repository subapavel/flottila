# The mapping file stores box positions and a description, and may lack its messages until they are chosen

Status: accepted (2026-11-09, owner, spec #152).

This ADR supersedes these parts of earlier ADRs:

- from ADR-0010:
  - "the file holds no editor layout" and its rejected option "Layout in the file";
  - "Values are strings. formatVersion is the only number.";
  - "Any schema change raises it", for these optional members;
  - the required `source` and `target`;
- from ADR-0012: "positions are browser state only";
- from ADR-0011: its stage-1 code list, for `minimum`, `maximum` and `maxLength`.

## Decision

- `source` and `target` become optional. A new mapping is created with neither; the author picks them
  on the studio's Signature tab (owner, 2026-11-09). Save (stages 1–2) accepts such a model.
  Compile, test and export give the stage-3 error `missing-structure` at `/source` or `/target`
  before any bundle is read: compile answers 200, test is refused, export is refused. Refresh
  loads only the sides present.
- `MappingModel` gains an optional `description` (a string of at most 4,000 characters).
- `TargetFieldMapping` gains an optional `layout`:
  - its form is `{ "target": {x, y}?, "boxes": { <authoringId>: {x, y} }? }`;
  - `x` and `y` are integers in [-1,000,000, 1,000,000]. The studio rounds before it writes, so a sub-pixel drag never changes the diff.
  - The writer leaves out an empty description, an empty layout and an empty `layout.boxes`.
  - `description` counts code points. Stage 1 maps `minimum`, `maximum` and `maxLength` to `invalid-value`.
- A position whose box no longer exists is dropped by the canonical writer. A box without a position is placed by the studio next to its consumer.
- **"Lay Out Mapping"** runs dagre on the selected target field mapping and writes the positions, as one undo step.
- **Opening a file without `layout`** runs the same dagre layout in memory. The working copy is not marked unsaved, so old files stay byte-identical until edited.
- **The engine ignores both fields.** Compile, test and portable export give the same result with or without them. Stage-1 validation accepts them, and stages 2 and 3 do not read them.
- `formatVersion` stays 1. All three changes only relax or add optional fields, and no reader outside this repo exists yet.
- **API changes.** `MappingSummary` gains:
  - `package`, the folder of the id;
  - `description`;
  - `source` and `target`, the root element names, absent while not chosen.

  `CreateMappingRequest` needs only `id`; `description`,
  `source` and `target` are optional.

## Consequences

- A drag is a model edit: it enters undo and marks the mapping unsaved, as in the prototype. Diffs show moved boxes. The owner accepts that cost for a stable layout.
- The Java records, the schema, `gen:types` and `openapi.json` change in one ticket. The contract empty-diff check guards them.

## Considered options

- **Browser-only positions (ADR-0012)**: rejected by the owner. A colleague opening the mapping sees a different picture.
- **A sibling layout file**: rejected. Two files per mapping must move, rename and merge together.
- **formatVersion 2**: rejected for now. That bump is reserved for the semantic additions ADR-0010 lists.
