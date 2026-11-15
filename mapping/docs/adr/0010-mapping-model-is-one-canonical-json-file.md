# A mapping model is one canonical JSON file that references its schemas and UDF sources

Status: accepted (2026-10-30, grilling #6); refines ADR-0009; amended by F-2 (`result` may be `null`); refined by ADR-0020 (how UDFs are written, compiled, verified and isolated; `deterministic` lives in the annotation)

This ADR records `docs/FOUNDING-ARCHITECTURE.md` §6 ("mapping model is data, never generated
code: declarative, versioned, diffable, validated by a schema") and fills its gaps. Flottila is
git-first and versions shared packages, so a mapping is one file in git, `<name>.mapping.json`.
It holds everything that decides the mapping's result, as SAP PO's `<mappingtool>` and CPI's
`.mmap` do. Schemas and UDF sources are files it references. They are not copied into it.

## Document

- `formatVersion` is an integer and starts at 1. Any schema change raises it, and a reader
  refuses a newer version. A migration is a pure function from vN to vN+1 over the JSON tree. It
  runs on load, before validation, and its result is written on the next save. Each migration
  has one golden input/output pair.
- `source` and `target` each hold:
  - `bundle`: the schema files, as references; a non-empty set, written sorted (#24);
  - `root`: the root selector, one of `{element: EQName}`,
    `{wsdl: {interface, operation, direction: input|output|fault, fault?}}` or `{json: "$"}`;
  - `fingerprint`: `sha256:<hex>` (ADR-0009).
- `targetFieldMappings` is an object keyed by target field path (ADR-0007). Each entry is
  `{id, result, boxes}`. `result` is the id of the box whose output feeds the field, or `null`
  while it is not wired yet (F-2). An unmapped field has no entry.
- `boxes` is an object keyed by `AuthoringId`. A box has one `kind`:
  - `source {field, context?}`: a source-field box;
  - `function {fn, inputs?, params?}`: a standard function from the function catalogue;
  - `udf {udf, inputs?, params?}`: a UDF declared in `udfs`;
  - `unresolved {name, origin: po|cpi|editor, inputs?, params?}`: an unresolved function.
- Boxes belong to their target field mapping. A reference to a box of another target field
  mapping is refused in milestone 1.
- An edge has no id. It is an input slot of the consuming box. `inputs` is a positional array in
  the catalogue's input order. Each entry is a box id, or `null` for an unconnected input. One
  output may feed several inputs. The graph must be acyclic. The form `<id>#<n>` is reserved for
  output port n of a multi-output function and refused in milestone 1.
- A constant is a `function` box with `fn: "constant"` and `params.value`. `params` are keyed by
  the catalogue's parameter name. A value is a string or a table (an object from string to
  string, for `FixValues`).
- `udfs` is keyed by UDF name. Each entry is
  `{executionKind: single|context|queue, inputs?: [name], params?: {name: string|int}, source}` (`inputs` optional, F-6).
  `source` points at a Java method, for example `udf/Udfs.java#formatPartner`. Compiling checks
  that the method matches the declaration. Java source is never inline. How UDFs are written,
  compiled and isolated is #16's decision.

## References and versions

- A reference is either a path relative to the mapping file (`../schemas/order.xsd`), or a file
  in a shared package at a fixed version (`common-schemas@1.4.0:/order/order.xsd`). The engine
  resolves relative paths. The host (Flottila) resolves package references through a resolver it
  supplies.
- A git commit and pinned package versions make a deploy reproducible. A compile never reads a
  file outside them. The files carry no hash of their own, because git already addresses content.
- A fingerprint mismatch stays a design-time error (ADR-0009). That is how a changed schema in
  the same repo, or a raised package version, shows its effect in CI. A *refresh* re-derives the
  fingerprint and lists the orphaned references. Opening and saving the mapping in the editor
  does the same.
  Clarified (spec-4 grilling, 2026-11-05): that is editor behaviour. The editor calls refresh
  (spec 5). The service's Save stays byte-faithful and never re-derives a fingerprint.
- Versions sit on package references. A mapping moves to a new version of a UDF library by
  changing `source` in `udfs`. Two versions of one UDF in one mapping are two `udfs` entries.
  Standard functions have no version. They are bound to the engine. A change of meaning gets a
  new name or a migration.

## Authoring ids

`AuthoringId` (ADR-0006) is 10 lowercase Crockford base32 characters. The schema allows
`^[A-Za-z0-9_-]{1,64}$`. Ids are unique within the mapping, across all target field mappings.

- The editor draws ids with `crypto.getRandomValues`.
- The importer derives them deterministically from SHA-256 of PO's `object_uid`, or else of the
  target path and the pin chain. PO's `gid` is often `0` in exports and cannot be used.
- An id survives editing, re-wiring and moving a box. Copy and paste gives fresh ids, and
  references that leave the pasted fragment become `null`. Ids are never reused.

## Canonical form

- There is one writer. It sorts keys by UTF-16 code unit and indents as
  `JSON.stringify(value, null, 2)` does. It writes LF line endings, a final LF, and UTF-8 without
  a BOM.
- It leaves out members equal to their default, and empty objects and arrays, except an empty
  table parameter, which is a value and is written `{}` (#24, F-6). A `context` equal
  to the field's parent is left out.
- Values are strings. `formatVersion` is the only number.
- Unordered collections are objects keyed by id or field path. Arrays appear only where order
  matters.
- GATE check 2's "round-trips unchanged" means byte-identical.

## Layout and orphans

- The file holds no editor layout. The editor lays out each function graph deterministically
  from its structure. #10 chooses the layout library. Zoom, collapsed branches and any manual
  positions are the viewer's own state in the browser. If #10 shows that automatic layout is not
  enough, an optional layout file can be added later without changing this format.
- Orphaned references are not marked in the file. They are derived on load against the
  structure, and the editor marks them. Loading never changes the file.

## Later, at the latest with the PO importer (`formatVersion` 2)

- The importer (ADR-0002) maps:
  - prefixed `Dst`/`Src@path` → field path;
  - `object_uid` → fan-out;
  - `arg@pin` → input order (the catalogue's `poPin`);
  - `const` → a constant box, and table bindings → table parameters;
  - `Src@context` → `context`;
  - `functionmodel` and `javaText` → `udfs` and a Java file;
  - an unknown function → `unresolved`.
  It drops `viewData`.
- `formatVersion` 2 adds what milestone 1 leaves out:
  - a box output shared across target field mappings (PO `object_uid` used under two target
    fields; the owner has used it in real mappings);
  - switched-off target fields (`switchedOff`);
  - box parameters bound to mapping parameters (`ext_bind`), which ties into the Flottila
    embedding;
  - output ports (`arg@from`).
  Until then the importer reports them.

## Considered options

- **YAML**: rejected. It has several spellings for one value. **XML**: rejected. It is a second
  schema language next to JSON Schema, and the editor and the code generator are JSON-native.
- **A directory with copied schema files, or schemas inline**: rejected. Flottila keeps
  schemas in git and in shared packages. A copy drifts from its source, and inline files cannot
  be diffed.
- **A hash per schema file**: rejected. Git already addresses content, and a comment edit
  would block compiling.
- **Layout in the file, or in a sibling file**: rejected. Every drag would change the diff.
  The graphs are small enough to lay out automatically.
- **Edge objects with ids**: rejected. Nothing traces an edge (ADR-0006).
- **Nested expression trees**: rejected. They have no fan-out.
- **One box pool for the whole mapping**: rejected for milestone 1. It loses diff locality.
- **Sequential ids**: rejected. They collide on merge. **UUIDs**: rejected. A v4 UUID is 36
  characters of noise per reference, and JDK 25 has no v7 factory (observed).
- **A catalogue version on every box**: rejected. The package reference already pins UDFs, and
  per-box versions repeat it and can disagree.
- **A stored `orphaned` flag**: rejected. It goes stale. **Inline UDF Java (PO `javaText`)**:
  rejected. It cannot be diffed or edited in an IDE.

## Consequences

- Every new member costs a `formatVersion` bump and a migration.
- #12 decides the mapping's identity and name, whether milestone 1 resolves package references,
  and the self-contained transport export (an analogue of `.mmap`).
- #10 lays out graphs automatically and generates fresh ids on paste (ADR-0012: dagre, stable
  layout, "Arrange").
- #14 needs an observed case: does PO evaluate a box shared by two target fields once?
- #16 decides how UDFs are written, compiled and isolated, and whether the editor fills `udfs`
  from annotations.
