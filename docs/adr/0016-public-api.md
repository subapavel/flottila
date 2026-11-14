# Public API: engine, filter, Workspace, documents and values

Status: accepted (2026-11-11, owner, issue #13 "What are the public API, the host SPI and the sandbox?"); amends ADR-0004 (the host rule); supersedes the API sketch of founding §11; amended by ADR-0018 (issue #12), ADR-0020 (issue #15) and ADR-0024 (#24: kinds, exception shapes, F-20); amended by ADR-0026 (accessor edges); amended by ADR-0027 (JsonWriter sinks); amended by ADR-0031 (compile-error blocks, #61)

Type and method names are binding from the first publish; until then a rename is one mechanical
ticket (ADR-0013). Facts marked "jq-ref" were observed on 1.8.2 by the adviser (issue #13 round 1).

## Surface

- `jq-core` (module `io.github.subapavel.jqj`) exports three packages: `…jqj` (engine, filter,
  Workspace, documents, sinks, bindings, limits, exceptions), `…jqj.value` (values) and `…jqj.host`
  (the SPI, ADR-0017). No exported signature names a `query-core` type, so `requires
  …jqj.query` is not `transitive`. A test checks every exported signature. `jq-cli` uses only the
  exported packages.
- **Versioning**: 0.x promises nothing. From 1.0, semver covers the three packages and documented
  behaviour, but not message wording (founding §1), `explain()` text, step counts across releases or
  U. An API-diff check joins the spec gate at the first publish.

## Engine and filter

- `JqEngine.builder().limits(Limits).host(Host).build()` is immutable, thread-safe and keeps no
  filter. The backend choice belongs to "Interpreter or bytecode first, and what speed must
  milestone 1 show?".
- `JqFilter compile(String text)` and `compile(String text, Set<String> variables)` throw
  `JqCompileException(message, line, column)`.
- `JsonDoc read(MemorySegment)` gives an **owned document** with its own read-only tape.
  `Workspace newWorkspace()`; `JsonDoc Workspace.read(MemorySegment)` gives a **Workspace
  document** on the reused tape. `JqValue JsonDoc.root()` is the run input. Reading follows ADR-0015
  (a heap segment in place, any other copied once).
- A `JqFilter` is immutable and thread-safe:
  - `boolean run(JqValue input, Bindings, JqSink, Workspace)` and an overload with `RunOptions`:
    true when the results ran out, false when the sink stopped;
  - `boolean stream(MemorySegment, Bindings, JqSink, Workspace, RunOptions)`: the host's streaming
    switch (ADR-0007), `IllegalStateException` if `needsWholeDocument()`. It takes a whole segment;
    Rail8's large bodies arrive in chunks and wait for the chunked reader (ADR-0015, later);
  - `boolean needsWholeDocument()`, `Set<String> variables()`;
  - `String explain()`: the IR and its facts, unstable, also `jqj --explain` (a jqj-only flag).
    Bytecode listing (founding §9) is the backend ticket's.
- `interface JqSink { boolean emit(JqValue v); }`, false = stop. One adapter to `query-core`'s
  `Sink` per run.
- `JsonWriter.sink(Workspace, WritableByteChannel, OutputOptions)` and
  `JsonWriter.sink(Workspace, OutputStream, OutputOptions)` write UTF-8 and charge bytes written.
  `OutputOptions` carries ADR-0015's output options. A `MemorySegment` sink is added when a host
  needs one.
- `Bindings.builder().put(String, JqValue).positional(List<JqValue>).build()` gives
  `$ARGS = {"positional":[…],"named":{…}}` as jq's `main.c`. A declared variable left unbound →
  `IllegalArgumentException`.
- `RunOptions(Limits, JsonInputs)`: `JsonInputs.of(List<MemorySegment>)` or
  `JsonInputs.concatenated(List<JsonSource>, InputOptions)` feed `input`, `inputs`,
  `input_filename` and `input_line_number`. `JsonSource(String name, ReadableByteChannel channel)`
  is one named file; `InputOptions` carries ADR-0015's input modes (`-n`, `-s`, `-R`, `--stream`,
  `--seq`). The engine never opens a file: `jq-cli` opens them and reads the same `JsonInputs`
  through the exported API.

## Exceptions

- `sealed JqException extends RuntimeException permits JqCompileException, JqSyntaxException,
  JqFilterException, JqTermination`, translated once at run entry from `query-core`'s exceptions (ADR-0008), payload
  detached, no stack trace.
- `JqFilterException(JqValue value)` carries jq's error value.
- `JqTermination(Cause cause, int exitCode, JqValue value, Budget budget)`. `Cause` mirrors
  ADR-0008 plus ADR-0017's `CANCELLED`: `HALT`, `BUDGET`, `STACK`, `STALE_VIEW`, `CANCELLED`.
  `Budget` is `STEPS`, `DEADLINE`, `RECURSION_DEPTH`, `BYTES_BUILT` or `BYTES_WRITTEN`; null for
  other causes. The parse depth limit and the regex match time raise ordinary jq errors (F-13,
  ADR-0017).
- `JqSyntaxException(String message, long offset, int line, int column)`, a fourth permitted
  subclass, is thrown by `read` and `stream` on a malformed document: jq's text and the byte offset
  (ADR-0015). Inside a filter (`fromjson`, `input`) a syntax error is a `JqFilterException` with
  jq's text.
- An exception thrown by a `JqSink`, a module loader, a debug sink or a channel passes through
  unchanged and ends the evaluation; the Workspace stays usable.
- `read` and `stream` copy any segment that is not a writable `byte[]`-backed heap segment
  (ADR-0015), so only that copy touches the host's segment. A segment closed or used off its owner
  thread during the copy throws the JDK's `IllegalStateException` or `WrongThreadException`, which
  passes through the same way.

## Values (`…jqj.value`)

- `sealed interface JqValue permits JqNull, JqBool, JqNumber, JqString, JqArray, JqObject`, with
  `detach()` and `toJson()`. The four kinds with views are public sealed interfaces and `JqNull` and `JqBool` are enums (ADR-0003, ADR-0024, F-20); views, `LiteralNumber`,
  `DoubleNumber` and built classes are in the non-exported `.internal.value` and implement them. A
  sealed permit across packages needs one named module: it compiles because `jq-core`'s main code
  is compiled with its `module-info` (VERIFIED_PASS on JDK 25.0.5; javac rejects it without one).
  The class-path test run of ADR-0013 is proved by the API-slice ticket #24 (F-20). A host's exhaustive
  `switch` needs no default.
- `JqString`: `value()` (a `String`), `utf8()` (a read-only `MemorySegment`: a slice of the
  document without a copy for a tag-5 view and of the array for a built string, a decoded copy for
  a tag-6 view). A slice of a view follows the host rule below, but reading it later is not
  checked: after the Workspace's next read it may show other bytes.
  `JqArray`: `length()`, `get(int)`. `JqObject`: `size()`, `get(String)`, `forEach` in jq's key
  order. Static `of(…)` factories build values.
- `JqNumber`:
  - `doubleValue()` follows ADR-0012's 17-digit rule;
  - `fitsLong()` is FITS_LONG (false for `-0`), `longValue()` throws `ArithmeticException` unless it
    fits;
  - `decimalValue()` is exact for a literal (within the exponent limits, ADR-0028) and the printed digits for a computed number (`5e-324`,
    not `BigDecimal.valueOf`'s `4.9E-324`); it throws on NaN and ±Infinity;
  - `toString()` is jq's printed form; `of(long)`, `of(double)` and `parse(String)` (`tonumber`'s
    grammar, a literal).

## Lifetime and threads (amends ADR-0004)

- **A result is valid during its sink call.** It stays valid longer only if it views a document
  the host holds: a Workspace document until that Workspace's next main read or `doc.close()`; an
  owned document until `doc.close()`. A streamed result, a result viewing an `input` document and
  every debug-sink value are valid only during their call. Anything else kept → `detach()`. A
  mistake gives `STALE_VIEW`, never a wrong value, except through a kept `utf8()` slice.
- Steps may chain over one owned document without copying, and threads may share it: after `read`
  it holds only a `byte[]` and its tape.
- `detach()` inside an evaluation charges bytes built and ticks per KiB; called by the host after a
  run, it charges nothing.
- The engine and filters are shared freely. One Workspace per running evaluation; it is reusable in
  sequence and across filters, and may change threads between runs when handed over. Overlapping or
  re-entrant use → `IllegalStateException`. `Workspace.stepsUsed()` reports the last run's steps.
- **Unloading**: a filter's hidden class is defined without `ClassOption.STRONG` (collected on G1
  and ZGC, VERIFIED_PASS); the engine keeps no cache (a host caches by content hash), and a
  Workspace drops filter references when a run ends.

## Considered options

- **`requires transitive` with `query-core`'s `Sink` and exceptions:** no adapter, but hosts handle
  `Object` values and `query-core` becomes a second frozen surface.
- **Results valid only during their sink call:** one rule, but every kept view is copied.
- **A pull iterator or `Stream<JqValue>`:** founding §8; a view would outlive its call.
- **An engine compile cache:** it blocks unloading.
- **A `ThreadLocal` or per-filter Workspace:** founding §6.5.
- **Exporting `LiteralNumber`:** freezes the number representation.

## Amendment (ADR-0018 and ADR-0020, issues #12 and #15)

- In milestone 1 there is no hidden class; the unloading test observes a dropped `JqFilter` and its
  interpreter tree being collected.
- `JqCompileException.getMessage()` is jq's whole command-line text when no syntax error is
  involved: every error block with its source line and carets, then `jq: N compile error(s)`. It
  also gives the blocks as a list. Syntax-error text is jqj's own (ADR-0020).
