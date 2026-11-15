# test-author memory

Durable facts only (AGENTS.md, Memory).

## Build and run
- Red run: `JAVA_HOME=/opt/temurin-25 MVNW_REPOURL=https://maven-central.storage-download.googleapis.com/maven2 ./mvnw -B -pl <modules> -am test`.
- `-Xlint:all -Werror` applies to test sources: no unchecked casts (use a typed tree, checked casts
  from `Object`), no generic varargs without `@SafeVarargs`, no static call through an instance.
- A test-compile failure in a module skips every module that depends on it in the same run.
- Format with `./mvnw -B -q -pl <modules> spotless:apply` (gjf 1.36.1); it leaves text-block content alone.
- Bash refuses some compound commands (heredocs, `git` in loops) in a worktree: write files with
  the Write tool, run plain commands.

## Helpers (engine/mapping tests, package io.flottila.mapping.mapping)
- `JsonTree`: strict RFC 8259 reader (dup keys rejected) for reading contract files without the code under test.
- `Fixtures`: repo root = nearest ancestor with `mvnw` and `contract/` (engine/mapping has no
  `flottila.corpus` property); `assertBytes` compares text then bytes, strict UTF-8.
- `LoadResults`: renders diagnostics as `"error <code> at pointer <p>"` / `"... at id <id>"`.
- `Models` builders (alternating key/value varargs, insertion order kept, so writers must sort);
  `FixtureModels`; `ModelGenerator(seed)` for valid canonical-normal models.
- Fixtures: `corpus/mapping-model/*.mapping.json` (every-box-kind, escapes, new-mapping).

## Oracles
- Canonical form: Node 22 at /opt/node22/bin/node; `JSON.stringify` per string plus a manual
  serializer with `Object.keys().sort()` (UTF-16 order; avoids JS integer-key ordering).
- Validating test expectations: compile tests against stand-in types plus a reference impl in the
  scratchpad (never committed); junit-platform-console-standalone from the Maven mirror runs them.

## Library facts (observed)
- json-sKema 0.32.0: `AdditionalPropertiesValidationFailure` carries a `FalseValidationFailure`
  cause located at the member; `RequiredValidationFailure` is located at the object; the parser
  throws `DuplicateObjectPropertyException`; `JsonPointer.toString()` prints `#/a/b`.
- json-sKema 0.32.0 reported VALID for a function box with `inputs: ["a1", "a.1"]` under a
  `oneOf` of `$ref` box branches, though `items` alone fails it (seen 2026-10-31).
- A corpus red that adds mappings can fail only on fingerprint-mismatch: also show the behaviour
  red (#118, the red-oracle prefix).
- A hand-written canonical text hashed with `sha256sum` reproduces `Structure.fingerprint()`; pin
  that literal, never recompute it in the test (#118, spec #89 review).
- Malformed-input behaviour cannot be a corpus case (`expected-error.json` needs `authoringId`):
  pin it in a hand-written oracle test (`JsonDuplicateKeyTest`, `JsonRawCharacterTest`).
