# The published function catalogue: a closed schema, fidelity as a level with a text, the box id by the helper's signature

Status: accepted (2026-11-03, #71 design, spec #70); amended 2026-11-04 (#74, pattern params; #83, table params); amended 2026-11-06 (F-39, #133: a helper that starts to raise re-pins its block); amended 2026-11-13 (F-46, #217: `generating`). Refines ADR-0011 (catalogue members) and ADR-0025.

## Decision

- **Schema.** `contract/function-catalogue.schema.json` is the catalogue's contract (JSON Schema 2020-12, closed as in ADR-0011).
  - The root is keyed by function id, `^[a-z][A-Za-z0-9]{0,63}$`, a subset of the model's names. Input, param and family names use the same pattern.
  - An entry has `family`, `executionKind`, `generating?`, `inputs?`, `params?`, `portable` and `fidelity`. `inputs` and `params` have at least one item when present; `constant` has no inputs.
  - `generating` is `true` or absent (#217, F-46). It marks a queue function whose result is a generating block, one value for every occurrence of a target field under every parent occurrence (ADR-0006), today `copyValue`; lowering reads it, and a test in `engine/mapping` refuses it on any other execution kind. It is a contract member: the wire and `ui/src/generated` carry it.
  - A param is `{name, type: "string", default, values?, syntax?}` or `{name, type: "table", default: {key: value}}`.
  - `syntax` is `"numberPattern"` (#74) or `"datePattern"` (#80). It cannot be combined with `values`.
  - Adding a member is a schema change.
  - A test in `engine/mapping` validates the packed catalogue with json-sKema. The JDK-only generator keeps only canonical form and one family per name.
- **Fidelity.** It is `{level: "exact"}`, `{level: "assumed"}` or `{level: "deviation", text}`, where `text` is 1–200 characters.
  - A function is `assumed` while one of its corpus cases has `provenance: assumed`; a test checks this against the corpus.
  - The case, not the catalogue, names the observation request that decides it.
- **Endpoint.** `GET /api/catalogue` answers the packed catalogue bytes as `application/json` (ADR-0015). No ETag is set: the catalogue changes only with the build.
  - `EngineFacade.catalogue()` serves the bytes.
  - The OpenAPI response refers to the schema.
  - `ui/src/generated` gains the catalogue type.
  - The UI gets no client method until spec 5 (editor).
- **The box in data errors (F-19).**
  - A `po:` function that can raise a data error declares `box` as its last `xsl:param` and raises through `po:data-error($code, $box, $message)` in `core.xsl`.
  - The exporter reads the helper module's signatures once and appends the FunCall's origin exactly where the declaration ends in `box`. There is no list of names and no IR change.
  - Both back ends start the message with `the function box <id>: `.
  - A single-value binding receives the box as well. #71 widens `SingleValueLibrary`'s binder before the family tickets use it.

- **Pattern params (#74 amendment).**
  - `Lowering` checks a `syntax` param through the shared formatting core in `engine/xdm`, for example `NumberPattern.picture` for `numberPattern`.
  - A pattern the core cannot translate is `invalid-parameter-value` at the box. The message names the untranslatable element.
  - The IR `Literal` keeps the box's own string. The runtime and `core.xsl` each translate it through the same core, so the model and the trace show what the author wrote.
  - The translation runs on every value until spec 6 (speed) decides whether to cache it.

- **Table params (#83 amendment).**
  - A `{type: "table"}` param lowers to one `Literal` holding the table's compact canonical JSON (ADR-0010 key order and escapes), which `xdm.TableText` writes and reads. The IR and `IrVerifier` are unchanged: ADR-0025's one `Literal` per param holds.
  - The runtime decodes the table once, at bind. The `po:` helper takes it as `xs:string` and reads it with `parse-json`.
  - A string where a table belongs is `wrong-parameter-type` at the box. A non-string row stays a stage-1 `wrong-type`, and a duplicate key stays a transport error (ADR-0011).
  - The `po:fixValues` signature, the JSON spelling in exports, and the `missing` param with its values (`key`, `default`, `error`; the runtime error `po:FIXVALUES`) are contract.

## Considered options

- **Fidelity as a string with a sibling `deviation`**: rejected. "A text only with a deviation" needs `if/then`, and the generated types would lose it.
- **The box id on every `po:` function**: rejected. ADR-0025 asks for it only on a function "that can raise one", and the other functions would carry dead params.
- **A list of raising names in the exporter, or a catalogue `raises` member**: rejected. The first is a central list (F-15); the back ends never read the catalogue.
- **The box id as a trailing IR `Literal`**: rejected. It breaks ADR-0025's args contract, and `FunCall` already has an `origin`.
- **Keeping the old arity beside the new one**: rejected. The generator refuses a second declaration with the same name.
- **Lowering writes the translated picture into the IR** (#74): rejected for now. The export would receive a picture the author never wrote, and the date pictures of #80 would need a second IR shape. Spec 6 (speed) may revisit it as a cache.
- **A list of pattern-param names in `Lowering`**: rejected. It is a central list (F-15). The catalogue already describes each param.
- **A `TableLiteral` with a `TableType` in the IR** (#83): rejected for now. No single-value binder takes a table, so every family's binder would widen mid-spec, and every back end gets a new record.
- **A `Literal` per table row** (#83): rejected. It breaks ADR-0025's one positional argument per param.
- **Validation in the generator, or an ETag**: rejected. The generator would need a JSON Schema library, and no client makes conditional requests.

## Consequences

- The exports, the wire and the UI types carry these shapes. Renaming `box`, a level or the message prefix is a contract change.
- #71 re-pins the two byte fixtures of #69 once, with this ADR as the reason: the context family gains `fidelity`, and five helper declarations gain `box`. The family tickets that follow keep the new pins green. Amended by F-39 (#133): a ticket whose observed rule makes an existing helper raise re-pins that helper's block alone (it gains `box`), names the reason in the fixture's javadoc, and keeps every other byte and every assertion.
- Every context function starts as `assumed`. It becomes `exact` when its last assumed case is observed, or `deviation` when the engine still differs from PO where no case shows it: `copyValue`'s result cannot feed a single-value input, which PO accepts (F-46, #217).
