# designer memory

Durable facts only (AGENTS.md, Memory).

- XmlSchema 2.3.3: a URIResolver that returns null falls back to `getKnownSchema` (#90).
- `DiagnosticCatalogueTest` scans every code-shaped string literal: a new diagnostic code needs a
  catalogue entry, and a dropped one leaves its constant behind (#96).
- `Structure.field`/`resolve` take the first same-step sibling; ambiguity is a refusal, not a
  lookup rule (#92).
- XmlSchema 2.3.3 (observed, spec #89 review): an attribute ref has a null type name and type, and
  `getWireName()` gives the target QName (type via `getRef().getTarget()`). An anonymous simple
  type has `getSchemaTypeName() == null` and an `XmlSchemaSimpleType` from `getSchemaType()`. An
  invalid `block` value does not throw.
