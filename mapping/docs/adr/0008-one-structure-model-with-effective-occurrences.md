# One structure model, normalised at import to fields with effective occurrences

Status: accepted (2026-10-30, grilling #8); amended by F-1 (json-sKema also validates mapping models, ADR-0011) and F-3 (no `--add-modules` under Maven); refined by the spec-4 grilling (2026-11-05): references outside the bundle, refused fields, JSON input

This ADR records `docs/FOUNDING-ARCHITECTURE.md` §6 ("one structure model with types and
cardinalities, shared by editor and compiler") for milestone 1 and fills its gaps. XSD, WSDL and
JSON Schema load into one model of **fields** only. Model groups disappear at import, because PO's
queues see fields, not groups.

- The module `structure` holds records only and has no dependencies. A `Structure` is a finite
  graph of types. Fields resolve lazily by field path (ADR-0007), so recursion is never
  expanded. `children(path)` returns attributes first, then elements in output order.
- A field has a kind (element, attribute, JSON property), an **effective occurrence**, an
  optional value type, `nillable`, an array marker (JSON properties only, #118), and display data. Display data (facets, default, fixed,
  enumeration, documentation) is shown in the editor and never read by the compiler.
- Effective occurrence is the product of the occurrences of every enclosing particle. A branch
  of a choice with two or more branches has minimum 0. ADR-0006's rules for unmapped fields and
  `TargetSlots(min, max)` use the effective occurrence.
- A value type is one of ADR-0004's seven atomic types, chosen by built-in ancestry or by JSON
  `type`/`format`, and otherwise `xs:string`. A list type is one value, and a union is a string.
- A **structure field** (one with child fields and no value) puts one `""` per occurrence into
  its queue (#2 §1). A **leaf field** puts its value. This corrects ADR-0004:
  `SourceQueue` is `array:join(context ! [field ! ''])` for a structure field, and the export
  uses the same rule.

## XSD

- `sequence` and `all` keep the declared order. The branches of a `choice` become sibling
  fields. `group` and `attributeGroup` references are inlined. An extension gives the base
  content, then its own content. A restriction gives its own content.
- Attributes under derivation (#95): a restriction inherits the base's attributes. A redeclared
  attribute replaces the inherited one, and `use="prohibited"` drops it, with or without a `type`
  and also from inside an attribute group. A simpleContent restriction is a leaf, like its base.
- A reference to a substitution-group head becomes a choice of the non-abstract members. The
  members are found transitively and across namespaces, head first and then ordered by QName.
  A member declared without a type takes the head's type (#95).
- A recursive path may repeat one type at most 16 times.
- An `xs:attribute ref` is an attribute field named by the referenced QName, with the global
  attribute's type and its own `use` (`xml:lang` through the `xml.xsd` fallback). An anonymous
  simple type, under an element or an attribute, takes its value type from its built-in
  ancestry like a named one; a root of an anonymous simple type stays refused. `nillable` on an
  element of an anonymous type marks the field nillable, as on a typed element. Two attributes of
  one name in one complex type, also through extension, attribute groups or references, are
  `schema-invalid` at the second declaration (ct-props-correct.4) (spec #89 review).
- Accepted with no effect:
  - `any`/`anyAttribute`: projection skips them, and they are never written;
  - mixed text: `mixed` on a complex type or on `xs:complexContent`;
  - `default` and `fixed`, because PO ignores XSD defaults (#2 §2), also on an element of an
    anonymous type;
  - `id`, `version`, `block` and `final` on schema components, and `blockDefault` and
    `finalDefault` on `xs:schema`;
  - attributes in namespaces other than the XSD and the versioning (`vc:`) namespace, and
    `vc:minVersion` below 1.1. Every other `vc:` attribute, and a `vc:minVersion` that is not a
    decimal below 1.1, stays refused.
  - an instance `xsi:type`: only the fields of the declared type are read;
  - facets: they are shown, never validated;
  - identity constraints.
- `xsi:nil` reads as `""` and is never written (ADR-0004).
- Refused when the schema loads: `xs:redefine`, `xs:override` and XSD 1.1.

## JSON Schema and WSDL

- JSON Schema is read in draft 2020-12 only. An absent `$schema` counts as 2020-12.
  - Properties keep their declared order, and `required` gives minimum 1.
  - An array property is one repeated field, with `minItems`/`maxItems`.
  - Such a field records that it is an array property, apart from its occurrence (#118):
    `"type": "array"`, `["array", "null"]` or `items` without `type`, whatever its `maxItems`.
    `maxItems: 0` gives `0..0`. An array of arrays stays refused at `items`.
  - `$ref`/`$defs` resolve through named types.
  - `allOf` merges its subschemas. `oneOf`/`anyOf` give the union of the properties, each with
    minimum 0.
  - A type list `[T, "null"]` marks the field nillable. JSON `null` reads as `""`.
  - `additionalProperties`, `patternProperties`, `if`/`then`/`else` and `not` are ignored.
  - Refused: other drafts, `$dynamicRef`, a root that is not an object, an array of arrays, and
    a merged property with two shapes.
- WSDL 1.1 and 2.0 are read by our own code over `java.xml` DOM. It hands every inline schema to
  the XSD reader.
  - The root is the element of the chosen operation's input, output or fault.
  - Refused: RPC `part@type` and messages with several parts.
  - Also refused (spec-4 grilling, 2026-11-05): a WSDL 2.0 message declared `#any`, `#none` or
    `#other`. Also a WSDL 2.0 `interface` with `extends` (#96).
  - Under a WSDL root selector, a bundle file whose document element is `wsdl:definitions` or
    `wsdl:description` is read as WSDL, and every other file as XSD (#96).
  - The selector's `interface` is the portType (1.1) or interface (2.0) EQName. `operation` and
    `fault` are NCNames. `fault` is required exactly when the direction is `fault`.

## Readers

The module `structure-import` is the only module that uses the reader libraries to read
structures, behind `StructureReader.read(SchemaBundle, RootSelector)`. The mapping-model module
uses json-sKema to validate mapping models (F-1, ADR-0011). XSD uses Apache XmlSchema 2.3.3
(Apache-2.0), and JSON Schema uses json-sKema 0.32.0 (MIT). Loading is offline: references
resolve inside the schema bundle. A namespace-only import resolves to the one bundle file with
that `targetNamespace`. Load errors carry file, line and column; XmlSchema keeps no positions,
so a JDK SAX pass (DOCTYPE refused) runs first and supplies them (F-7).

Refined by the spec-4 grilling (2026-11-05):

- The bundle is a closed set: it lists every file the reader reads, including the ones reached
  only through another file. Every read goes through the reference resolver (ADR-0014).
- An `import` or `include` `schemaLocation` resolves relative to the importing file. An `import`
  whose location is absolute, a URL, or a file outside the bundle falls back to the namespace
  rule. An `include` has no namespace, so its location must name a bundle file. When neither
  matches, the load fails with `schema-reference-outside-bundle` at the `import` or `include`
  element's file, line and column, and the message names the missing reference.
- The same rule holds for a `wsdl:import`/`include`, an `xsd:import` inside WSDL `types`, and a
  JSON Schema `$ref` to another file.
- JSON Schema (#97):
  - The file part of a relative `$ref` resolves against the referring file's bundle reference,
    whatever its `$id` says.
  - A `$ref` that is absolute falls back to the one bundle file whose root `$id` is that URI,
    the analogue of the namespace rule.
  - When neither matches, the load fails with `schema-reference-outside-bundle` at the `$ref`
    keyword's file, line and column.
  - json-sKema returns a whole schema or nothing (F-25). So a file's walk refusals are reported
    only when nothing earlier in that file failed: a root that is not an object, an array of
    arrays, two shapes, or a type-list member that is not a string. Earlier failures are a refused
    `$schema`, `$dynamicRef`, a `$ref` outside the bundle, or a json-sKema problem. All of those
    earlier errors are still reported together.
- The engine does not compute a bundle's closure for the author.
- The engine maps a reference written inside a schema to a bundle-listed reference by text,
  before any read, so the resolver reads only listed files (ADR-0014, #90).

## JSON as a source at run time

Decided in the spec-4 grilling (2026-11-05):

- The input format follows the source structure's root selector: a JSON root reads JSON, every
  other root reads XML. There is no content-type sniffing.
- The runtime has its own small JSON pull parser and takes no new dependency (FOUNDING §8).
  It keeps numbers in their input spelling, reads `null` as `""`, and projects by `$` field paths.
- The XSLT export reads JSON input through `json-to-xml(unparsed-text(...))` and navigates the
  `fn:` map/array representation. Saxon-HE 12.5 keeps the input spelling of numbers there
  (`1.50`, `1e2`, `-0.0`, a 20-digit decimal), observed 2026-11-05. JSON-source corpus mappings
  sit in the differential like any other.
- A duplicate key is malformed input on both sides. The runtime refuses it as it refuses bad
  XML. The export calls `json-to-xml` with `map{'duplicates': 'reject'}` (FOJS0003).
- Where `json-to-xml` gives U+FFFD, the runtime parser does too (#98): the escapes `\b`, `\f`
  and other control-character escapes, an escaped U+FFFE or U+FFFF, and a lone surrogate
  (observed in Saxon-HE 12.5, 2026-11-05). The output therefore stays well-formed XML on both
  sides.
- A raw (unescaped) U+FFFE or U+FFFF in the JSON input is malformed input on both sides
  (spec #89 review): `unparsed-text` refuses the text with FOUT1190, and the runtime refuses it
  as it refuses bad JSON. A leading byte order mark is dropped on both sides; U+FEFF inside a
  string is kept (observed in Saxon-HE 12.5, 2026-11-05).
- A JSON Schema `minItems` or `maxItems` must be a non-negative integer of at most 18 digits,
  as an XSD occurrence count must; anything else, and a `minItems` above `maxItems`, is
  `schema-invalid` at that keyword. Two bundle files with one root `$id` are `schema-invalid` at
  the second `$id` (spec #89 review).
- The input is never validated, as for XML. A value whose JSON kind does not fit its field reads
  as absent: an object or array for a leaf, a scalar for a structure field, a non-array for an
  array property. The export's path navigation over the `fn:` representation gives the same.
- An array property reads each item of its array as one occurrence, by its array marker and
  never by its maximum (#118). The input is not validated: `[1]` gives one occurrence even under
  `maxItems: 0`, and `[]` none. `null` is no array, so it reads as absent even with `"null"` in
  the type list; a `null` item reads as `""`.
- PO message mappings have no JSON source, so JSON-specific corpus cases are
  `provenance: decided`. The reading "JSON `null` reads as `""`" stays an assumed row of
  `obs-structures` (#36).

## JSON as a target

In milestone 1, JSON runs as a source. Its values stay strings in their input spelling: numbers
as written, and booleans as `true`/`false`. A JSON Schema also loads and shows as a target
structure, but compiling a mapping with a JSON target is a design-time error. JSON output is the
first spec after milestone 1. It adds constructor records (object, array, member, scalar) under
`TargetSlots`/`TargetRepeat`, as ADR-0006 allows. That spec must also settle the export, because
Saxon-HE 12.5's `xml-to-json` normalises numbers (`1.50` → `1.5`, observed).

One load reports every load-time error of the bundle: files in bundle order, each file in
document order. A file that is not well-formed stops only that file (spec-4 grilling,
2026-11-05). `schema-root-not-found` is reported only when the bundle has no other load error, so a root
in a file that stopped is not also reported as missing (#93).

## Refused when a mapping uses the field

These are design-time errors, raised only for a field that a mapping refers to or must create.
The structure loads and shows these fields. Model groups are gone after import, so each such
field carries its **refusal**: the reason and the file, line and column of the declaration that
causes it. The refusal's reason is part of the fingerprint, and its position is not (ADR-0009,
#92). The diagnostic's location is the
mapping element the author must change: the `authoringId` of the box or target field mapping,
or the `/targetFieldMappings/<field path>` pointer of a field that must be created. Its message
names the schema file, line and column (spec-4 grilling, 2026-11-05).

Shape (#92):
- A field carries at most one refusal.
- Same-step siblings each carry `ambiguous-field-path`, at the position of the step's second
  declaration in output order.
- A path through a refused field is refused, with the refusal nearest the root.
- The target walk never enters a refused field. A refused field with a minimum above 0 that no
  mapping writes is reported at its pointer, instead of `unmapped-mandatory-leaf` or
  `unsupported-field`.
- `/api/structures` does not show refusals until an editor story asks for them.

- two sibling declarations with the same step;
- in a target:
  - an abstract element type;
  - a repeated group of two or more particles, unless it is a choice of single elements,
    because streamed output cannot interleave it.

A path beyond the recursion cap is not a refusal: it stays `unsupported-field` (spec #89).

## Considered options

- **Model groups as nodes of the tree**: rejected. PO's queues see fields only, and every
  consumer would have to fold the groups itself.
- **A tree expanded to a fixed depth**: rejected. The tree is either too shallow or explodes on
  large schemas.
- **Two models (XML and JSON)**: rejected. Projection and the target walk would exist twice.
- **Declared `minOccurs` for the unmapped rules**: rejected. A mandatory leaf in a choice would
  force mapping every branch.
- **Readers**:
  - Xerces: rejected. It fails on the module path (split packages).
  - XSOM, Eclipse XSD and XMLBeans: rejected for licence, dependency or completeness reasons (#7).
  - WSDL4J (CPL-1.0) and Woden (last release 2015): rejected.
  - everit (drafts 4–7): deferred. It can be added as a second JSON Schema reader behind
    `StructureReader` when a corpus case needs draft-07 or OpenAPI 3.0.

## Consequences

- Changing a reader changes no other module. The fingerprint (ADR-0009) detects when a reader
  upgrade produces a different structure.
- On a hand-built module path, json-sKema needs `--add-modules kotlin.stdlib`: observed on
  Java 25. Under Maven it needs no flag, and the flag breaks the build (F-3). FOUNDING §11's single "structure
  import" module becomes `structure` plus `structure-import`.
- OData metadata (FOUNDING §6) is not in milestone 1. It can be added later as another reader.
- Assumed readings until observed cases confirm them (ADR-0002, #14):
  - a branch of a choice counts as optional for the unmapped rules and for `po:MINOCCURS`;
  - `xsi:nil` and JSON `null` read as `""`.
