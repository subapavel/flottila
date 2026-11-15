# The service API: stateless compile and test on the working copy, one diagnostic shape, code-first OpenAPI

Status: accepted (2026-10-30, grilling #12); refined by ADR-0020 (UDF scan endpoint, trace `messages`, `flottila:UDF-ERROR`); trace shape refined by #53; refresh result refined by the spec-4 grilling (2026-11-05)

ADR-0013 makes the editor reach the service only through `ApiClient`, with compile and test
on the unsaved working copy, and generated wire types only. This ADR fixes the contract and
its type source. The owner prefers Quarkus OpenAPI → TypeScript.

## Endpoints

All under `/api`, with no version in the URL. The committed `openapi.json` guards the
contract, and the editor ships with the service.

| Endpoint | Body → result |
| --- | --- |
| `GET /api/mappings` | → `[{id, name}]` |
| `POST /api/mappings` | `{id, source: {bundle, root}, target: {bundle, root}}` → 201, canonical model, `ETag` |
| `GET /api/mappings/{id}` | → canonical model, `ETag` |
| `PUT /api/mappings/{id}` (Save) | working copy, `If-Match` → canonical model, new `ETag` |
| `POST /api/mappings/{id}/compile` | working copy → diagnostics |
| `POST /api/mappings/{id}/test` | working copy, input message → output or runtime error, diagnostics, trace |
| `POST /api/mappings/{id}/refresh` | working copy → 200 `{model, diagnostics}`: the working copy with new fingerprints, and the orphaned references with every other stage-3 diagnostic (ADR-0009) |
| `POST /api/mappings/{id}/udfs/scan` | `{source}` → the declarations of the file's `@Udf` methods, or diagnostics (ADR-0020) |
| `POST /api/structures` | `{bundle, root, base}` → structure graph, fingerprint |
| `GET /api/catalogue` | → function catalogue |

- Compile, test and refresh are stateless. The model comes from the body. `{id}` only locates
  the mapping file, so relative bundle paths resolve. Refresh never saves.
- Refresh changes nothing in the model but the fingerprints. A side whose bundle fails to load
  (including a package reference) keeps its fingerprint, and its load diagnostics are returned.
  A load problem is never a 4xx (spec-4 grilling, 2026-11-05).
- The display queue has no endpoint. The test result carries the whole trace, and the editor
  switches between boxes. The service keeps no run or session.
- A structure goes over the wire as a graph: its types and each type's fields, not an expanded
  tree. ADR-0008 resolves recursion lazily, and the editor expands the tree itself.
- No transport export (an analogue of CPI `.mmap`) in milestone 1: git and shared packages are
  the transport. The XSLT export is an engine API in milestone 1, for the oracle. An endpoint
  for it is a later spec.

## Diagnostics and errors

- One shape: `Diagnostic {severity: error|warning, code, message, location}`.
  - `code` is a stable kebab-case id from a catalogue of diagnostics
    (`unconnected-target-field`, `orphaned-reference`, `unreachable-box` …). Tests and the
    editor match on the code, never on the message.
  - `message` is English. Milestone 1 has no i18n.
  - `location` is exactly one of `{authoringId}`, `{pointer}` (stage 1, ADR-0011; since #65 also
    `unmapped-mandatory-leaf` at `/targetFieldMappings/<field path>`, the member the missing
    mapping would occupy, since an unmapped field has no `AuthoringId`),
    `{side, fieldPath}` or `{file, line, column}` (a schema load error).
- Diagnostics are a result, not a failed request. Compile always answers 200.
- Save checks stages 1 and 2 only and answers 422 with diagnostics when they fail. Stage 3
  errors never block a save, so an unfinished mapping (`result: null`, F-2) can be saved.
- Test:
  - with compile errors, it answers 200 with the diagnostics and no output;
  - with a runtime error, it answers 200 with `error {code, message, authoringId?}` and no
    output. `code` is the error QName (`po:MINOCCURS`, `po:SORT`, `flottila:TIMEOUT`, `flottila:UDF-ERROR`).
    The output was buffered and is dropped (ADR-0006). The trace up to the error is returned.
    Since #52 `authoringId` is the target field mapping for `po:MINOCCURS` and the function box
    for a context function's data error; `flottila:TIMEOUT` has none. The facade's
    `TestRunResult.RuntimeError` carries it as `Optional<AuthoringId>`.
- Transport errors (malformed JSON, unknown id, 409, 413) answer `application/problem+json`
  (RFC 9457).

## Trace

- A queue is an array of contexts, and a context is an array of values. SUPPRESS is `null`,
  for example `[["1a","2b"],[null,"3c"],[]]`. Values are always strings, and `""` is a value
  (xsi:nil, JSON null, a structure field), so `null` is unambiguous.
- An atomic value (a single-value call with no queue input) is `{atomic: {type, value}}`.
- The trace is an object keyed by `AuthoringId`, in first-report order (since #53, on the wire
  too). It holds every box that was evaluated.
  `Literal`s are not traced; the editor shows them from the model (ADR-0006). Since #83 a `constant`
  box is an atomic `FunCall` and is traced as `{atomic}`. A box that was
  never evaluated, because its field was never created, is absent.
- A target field mapping's entry adds `occurrences`: per parent slot, the list of slots
  `{slot, status: created|suppressed|truncated}` (`truncated` = cut by `maxOccurs`), and any
  `minOccurs {created, min}` that raised `po:MINOCCURS` (ADR-0012).
- A box whose UDF called `run.log` adds `messages: [string]`, limited like values (ADR-0020).
- `po:show` text (`1a,2b|S,3c`) is derived from this shape for oracle diffs and logs only. It
  cannot tell a value `S` or a comma from the markers.
- Limits, both configurable: a test input up to 10 MB (413 above), and the trace limit
  (`flottila.mapping.trace-limit`, 10,000) per entry; a limit below 1 is refused at startup and
  by the facade (#53). Since #53 the cap marker is `capped: true`,
  not `truncated`, which names a slot cut by `maxOccurs`.
- The run clock's zone (`flottila.mapping.time-zone`, a region or offset id, default `UTC`, #80):
  a mapping run reads its time once, in that zone, for `currentDate` and the date functions; the
  export passes it as the processor's implicit time zone. An unknown zone is refused at startup.
- Since #53 each entry carries `kind`:
  - `queue` {contexts, capped, slotCount, contextCount}: at most the limit's slots and contexts,
    the totals always;
  - `occurrences`, for a target field mapping {min, max?, parents [{parentSlot, created,
    slots [{slot, status}]}], capped}: at most the limit's slot and parent records, always
    keeping the field's last parent. The field's queue is the entry of its `result` box.
    `created < min` marks the parent slot that raised `po:MINOCCURS`; there is no separate
    `minOccurs` record.
  - A box feeding several inputs has one entry, its first report. `{atomic}` waits for the first
    atomic box, in spec 3.
- Since #53 the slot budget of an occurrences entry is spent in parent order, so the kept last
  parent can show `created` without its slot records; a cut queue shows its first contexts, and
  the last one shown may look empty. The editor reads `capped` and the totals (spec 5). The
  nullable slot of a context is the schema-only type `TraceValue` (`string | null`), code-first.
- A runtime error returns the trace up to the error. A test run compiled without tracing returns
  an empty trace.

## Types

- The contract is code-first. Wire DTOs are records in the service's API package, separate from
  the engine's records.
  - A sealed DTO carries `@Schema(oneOf, discriminatorProperty = "kind", discriminatorMapping)`,
    and each subtype declares `kind` as a `const`.
  - Every component is required unless it carries our optional marker.
- A drift test reads the generated `openapi.json`. It fails unless every `oneOf` and mapping
  equals `getPermittedSubclasses()`, and every `required` list equals the unmarked components.
- The mapping model and the function catalogue are not repeated as DTOs. Their fields are
  `$ref: ./mapping-model.schema.json` (and the catalogue's schema). `ApiClient` uses the
  json-schema-to-typescript types for them (ADR-0011). A Vitest type test (`expectTypeOf`)
  checks that both generated shapes are assignable to each other.
- smallrye-openapi writes OpenAPI 3.1 at build. `openapi.json` is committed, and CI requires an
  empty diff. `npm run gen:types` runs openapi-typescript 7.13.0 (MIT) on it into
  `ui/src/generated/`, with no Java needed.

## Considered options

- **A display-queue endpoint over a stored run**: rejected. It needs server state for an
  unsaved model, and one test response is small enough.
- **`po:show` text on the wire**: rejected. It is lossy.
- **HTTP 4xx for compile errors**: rejected. A mapping with errors is a normal state of the
  editor.
- **Contract-first, a hand-written `openapi.yaml`**: rejected. It is a second source of truth
  next to the Java DTOs.
- **Payloads as JSON Schema with json2ts**: rejected. It is one generator fewer, but it loses
  OpenAPI and Swagger UI, which Flottila's Quarkus backend will have anyway.
- **An uncommitted `openapi.json`**: rejected. The UI would need a Java build to generate its
  types, and contract changes would not show in a diff.

## Consequences

- Observed on 2026-10-30, in a scratch directory, not committed:
  - without annotations, smallrye-openapi 4.3.5 renders a sealed record hierarchy as `{}`, and
    record components are not required;
  - with `@Schema(oneOf, discriminator…)` and `requiredProperties` / `required = true` /
    `@NotNull`, openapi-typescript emits a discriminated union with required fields;
  - `@Schema(ref = "./mapping-model.schema.json")` is kept verbatim, and openapi-typescript
    resolves it. Without `./`, it becomes a dangling `#/components/schemas/…` reference;
  - two builds give a byte-identical `openapi.json`, and two openapi-typescript runs give
    identical output.
- GATE check 3 uses `POST /api/mappings/{id}/test`.
