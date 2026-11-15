# Service wire rules: mapping ids, ETags, problem types and result unions

Status: accepted (2026-11-02, #28 design); refines ADR-0014 and ADR-0015; amended by the spec-4 grilling (2026-11-05): package references pass the pattern

## Mapping ids and paths

- A mapping id matches `^[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}(/[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}){0,15}$`.
  - Its last segment must not be `test`, `compile`, `refresh` or `udfs`. Those are reserved for action
    paths, so routing never depends on the router.
  - The file is `<id>.mapping.json`, and the mapping's name is the last segment.
  - A file whose path fails the pattern is not a mapping.
- `base` matches the id pattern or is `""`.
- A bundle reference matches `^(\.\./){0,16}[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$`.
  - Since the spec-4 grilling (2026-11-05), a **package reference** also passes:
    `^[A-Za-z0-9_-][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.+-]*:/[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$`.
  - The `:` and `@` it needs are allowed in this form only. It never reaches the three checks: the
    service's resolver refuses it first (#90).
    Loading it gives `package-reference-unsupported` (ADR-0014).
- In every path:
  - no segment starts with `.`;
  - `\`, `:`, `%`, NUL, a leading `/` and an empty segment are refused.
- Only `Workspace` resolves paths. It runs three checks, in order:
  1. the pattern, on the decoded text, before any filesystem call;
  2. `root.resolve(x).normalize().startsWith(root)`;
  3. `toRealPath()`, then `startsWith(root)`.
- The root is the real path of `flottila.mapping.workspace`, resolved once at startup.
- Saving writes a temporary file in the real parent directory, then moves it into place atomically.

## ETag

- The ETag is `"sha256:<64 lowercase hex>"`, a strong tag over exactly the canonical bytes the response carries.
- Spec 5's `If-Match` compares it. Until then `If-Match` is ignored.

## Problems

- A problem's `type` is `urn:flottila:mapping:problem:<slug>`. Each slug has a fixed status:

  | Slug | Status |
  |---|---|
  | `invalid-workspace-path` | 400 |
  | `malformed-json` | 400 |
  | `invalid-request` | 400 |
  | `malformed-input` | 400 |
  | `not-found` | 404 |
  | `mapping-exists` | 409 |
  | `unsupported-media-type` | 415 |
  | `model-refused` | 422 |

- `model-refused` adds `diagnostics`. It answers:
  - a save refused at stage 1 or 2;
  - a GET of a stored file that is refused;
  - a refresh whose working copy is refused at stage 1 or 2 (#99): the 200 answer carries a valid
    model, so there is nothing to refresh.
- Creating an existing id is 409. This is not the spec-5 `If-Match` 409.

## Results

- `test` answers 200 with `output` or `refused` diagnostics. Stages 1–3 never give a 4xx (ADR-0015).
- `/api/structures` answers a structure load error with 200 `refused`.
- Unions use the discriminator `kind`:
  - location: `pointer`, `authoring-id` or `file-position`;
  - test result: `output`, `refused` or `error` (a runtime error, #54);
  - structure result: `loaded` or `refused`.
- The optional marker is a `java.util.Optional` record component. Every other component is required.

## Service boundary

- The service bypasses Jackson for model bytes on GET, PUT and create, so the strict reader and byte
  identity hold.
- It calls only:
  - `EngineFacade.load`, `save`, `compile`, `run`, `newMapping` and `testRun` (#54), and `refresh`
    (#99);
  - since #90, `Workspace.resolver` implements `ReferenceResolver` and builds `ResolvedFile` and
    `UnresolvedReferenceException`; `Structures.load`, `compile` and `export` take it;
  - `Structures.load`;
  - `Structure.fingerprint()` and the structure's read accessors (root field and type definitions), which
    `/api/structures` maps to `FieldDto`.
- A JSON request record with a missing, null or wrongly typed non-`Optional` member (no scalar coercion), or an
  empty body, is 400 `invalid-request`, never 500. A PUT body is the model's raw bytes, so an empty one is
  `malformed-json`.

## Considered options

- **Raw file bytes on GET**: rejected. Migration on load would not happen, and the ETag would not be canonical.
- **422 for `test`**: rejected (ADR-0015). A broken working copy is a normal editor state.
- **`NOFOLLOW_LINKS` alone**: rejected. It guards only the last component.
- **A diagnostic minted by the service for an escaping path**: rejected. Codes belong to the engine catalogues.

## Consequences

- Renaming a pattern, a slug or a `kind` is a contract change. The ui's `gen:types` and `ApiClient` follow it.
- A body above 10 MB gets Vert.x's 413 without problem+json in spec 1.
