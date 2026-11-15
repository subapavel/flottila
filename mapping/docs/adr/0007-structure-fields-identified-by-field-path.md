# Structure fields are identified by their field path

Status: accepted (2026-10-30, grilling #8)

A mapping model, `SourceQueue(field, context, origin)`, tracing and a later PO importer refer to a
source or target field only by its **field path**: the kind, namespace and local name of every
step from the root down to the field. Its canonical text uses EQNames, with `@` for attributes and
a bare name when there is no namespace, for example `/Q{urn:a}Order/Item/@id`. A JSON path starts
with `$`, and names percent-encode `% / @ { }`, for example `/$/order/items/id`. Prefixes and form
defaults do not change a path. A path stays the same across schema reloads as long as names,
namespaces and nesting stay the same. `AuthoringId` (ADR-0006) stays the id of mapping elements
(target field mappings, function boxes, source-field boxes), never of structure fields.

A context is a proper ancestor's field path. By default it is the parent, and for an attribute
it is the attribute's element. The author may set any ancestor (#2 §1).

## Considered options

- **Opaque field ids**: rejected. They need an id map per schema version, and re-importing a
  changed schema breaks that map anyway.
- **XSD component designators**: rejected. They name declarations, not positions in an
  instance, and do not exist for JSON Schema.

## Consequences

- A field renamed or moved in the schema becomes an orphaned reference after re-import
  (ADR-0009); the editor offers to remap it.
- Two sibling declarations with the same step (`sequence(a, b, a)`) have no unique path and are
  refused when a mapping uses them (ADR-0008).
