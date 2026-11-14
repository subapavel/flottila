# The prelude is jq's builtin.jq, parsed once per JVM and looked up after the native table

Status: accepted (2026-11-13, architect under `/run-spec` #56, ticket #71; designer draft); amends ADR-0040 (Binder order) and ADR-0031 (Source of inlined prelude nodes); amended by ADR-0044 (`to_entries` native, #85)

jq 1.8.2's `builtins_bind` binds builtin.jq's definitions, then the bytecoded builtins, then the C functions (`function_list`), and binds each unbound reference to the last binder first. No (name, arity) in builtin.jq is also a C or bytecoded builtin, so looking up natives before the prelude gives jq's result. The only exceptions are F-31's replacing natives (`flatten/0`, `flatten/1`, `inputs/0`), where jqj's native wins by design. Looking natives up first also lets a native-only filter compile without touching the prelude.

## Decision

- **Source**: jq 1.8.2's `builtin.jq`, verbatim, moves to `jq-core/src/main/resources/io/github/subapavel/jqj/internal/parse/builtin.jq`. Its SHA-256 is checked against `SOURCES.sha256`, and NOTICE names the main path. The MIT text stays in `META-INF/LICENSE-jq`.
- **Cache**: a holder-class `Prelude` parses the file once per JVM, with fixed settings independent of any Engine, and indexes the definitions by `name/arity`. The AST is shared read-only. A filter whose every call binds before the prelude (nested defs, params, user defs, the native table) never triggers the parse; a name that binds nowhere does parse it, to know the name is undefined (amended at #71).
- **Lookup order**:
  - A user reference looks in nested defs, params, the def, outer user scopes, then the native table, then the prelude.
  - A prelude body looks in nested defs, params, the def, the native table, then only the whole prelude index. That equals "the prelude definitions before it" because `PreludeBindTest.builtinJqHasNoForwardReference` fails on any forward call in the SHA-pinned file (amended at #71).
  - ADR-0040's order is amended to match.
- **Live definitions**: a prelude definition is bound, checked and lowered only when a live reference reaches it.
- **Native table**: every jq-1.8.2 builtin signature (`function_list`, libm, the bytecoded builtins and `builtins/0`), plus F-31's replacing natives. An entry not built yet binds, and lowering it is a Stub. So an unbuilt native gives a Stub, while an unknown name gives jq's "not defined". The table is translated from builtin.c and libm.h and gets a NOTICE line.
- **Recursion**:
  - ADR-0040's SCC rule finds the recursive prelude definitions; there is no hand list.
  - `..` is a call of `recurse/0`, as parser.y lowers it.
  - In 2a, `..`, `recurse`, `paths`, `walk`, `while`, `until` and `repeat` are Stubs.
- **Compile level**: a live prelude body is a compile level that never has a binding error, which a test proves for all definitions.
- **Source**: nodes lowered from a prelude body take the call site's Source; closure arguments keep their own Source. This amends ADR-0031.
- **Cost**: an inlined prelude call ticks and charges nothing, so `map(f)` costs what `[.[]|f]` costs.
- **query-core** gains nothing.

## Amendment (ADR-0044, ticket #85)

- **`to_entries/0` is a replacing native**, like F-31's: the native table lists it, so builtin.jq's definition is never bound, and it walks a read object once through its key proof (`Binder.Native.TO_ENTRIES`, keepsArgs true per F-47).

## Considered options

- **ADR-0040's prelude-before-natives order**: `.a|length` would parse the prelude.
- **A 2a subset of builtin.jq**: it edits jq's bytes, loses the SHA proof and needs a re-sync every spec.
- **One parse per Engine**: the prelude depends on nothing in an Engine.
- **Binding all definitions once per JVM**: a second Binder path, and dead definitions get checked.
- **A serialised AST at build time**: build work before any measurement asks for it.
- **"not defined" for an unbuilt native**: jq compiles those filters.
