# Founding architecture — agreed direction (2026-11-10)

This is the architecture the owner and Claude worked out in the founding conversation, before the
repo existed. It is the **owner's stated direction**: grilling sessions start from it and confirm,
refine or overturn it — they do not re-ask what is written here. Each point becomes binding when
an ADR records it; until then, a Phase 0 ticket may change it with a reason. Section numbers are
referenced by the Phase 0 map's tickets.

The full proposal, with its option tables and examples, is the owner's document "jq Engine for
Modern Java — Architecture Proposal" (2026-11-10). This file is its content in the form agents
need; where the two differ, this file is the one agents follow.

## 1. Goal and scope

- An implementation of the **jq 1.8 language** on Java 25 and newer, open source (Apache 2.0).
  Working name and command name: `jqj`.
- Judged on, in this order:
  1. **Speed when warm.** Speed is a priority of the design. The target is the same range as the
     original jq, measured end to end (read JSON, run the filter, write JSON). Beating jq or jaq
     in benchmarks is not required.
  2. **Memory proportional to the output, not the input**, and a hard budget on every evaluation.
  3. **jq compatibility**, proven by jq's own test files and by comparison with the real jq
     binary. Every deliberate difference is written down (`DEVIATIONS.md`).
  4. **Modern Java, final APIs only.** JDK 25 is the baseline (Rail8's runtime). Preview and
     incubator features are opt-in extras, never required.
  5. **Safe to host.** A filter written by a flow author cannot block a thread, read the
     environment, run forever or exhaust the node's memory.
- Not goals: jq's C API, identical error message wording, other query languages (JSONPath,
  JMESPath).
- Target workload: integration runtimes — compile a filter once, run it on thousands of small and
  medium messages; also one-field reads (key extraction, routing) on every message, and the
  occasional very large file.

## 2. Home, role, licence (owner's decisions)

- A **neutral standalone project** that R8 consumes. It is designed with R8's model in
  mind (a message body is bytes, envelope data as variables, `whole` / `stream` step kinds) but has
  no dependency on R8 code. The adapter (`r8-step-jq`) lives in the R8 repo.
- In R8, jq is **one of several tools**, not the only mapping language: mapping, routing
  and filter predicates, key extraction, splitting, in any number of steps.
- **Apache 2.0.** An independent implementation carries no obligation toward jq's licence. Only
  files copied from jq (its function library written in jq, its test files) keep jq's copyright
  notice, listed in `NOTICE` together with joni. Confirm jq's `COPYING` before copying; do not
  copy jq manual text.
- **Regex engine: joni** (the Java port of Oniguruma, the library jq uses; works on UTF-8 bytes).
- **`import` is supported.** A host resolves module names; in R8 only from the flow's
  package and its declared dependencies, never from disk.

## 3. What is taken from jq and what differs

| jq does | jqj does | Why |
| --- | --- | --- |
| Compiles the filter to bytecode for its own VM | Compiles the filter to an IR, then to JVM bytecode (and an interpreter over the same IR) | The JIT makes machine code specialised for one filter |
| Reference counts; mutates in place when the count is 1 | GC for sharing; an ownership marker for in-place updates (§6) | The JVM has no reference counts |
| Backtracking VM hands results on one at a time | Push-style `emit` (§8) | Same effect, compiles to plain loops |
| Parses the whole input into a value tree | Input stays bytes plus a tape; only touched parts are decoded (§5) | Integration filters read a small part of a payload |
| Many builtins defined in jq | Same prelude, hot builtins native (`map`, `select`, `range`, `recurse`, `flatten`, `limit`) | Definition-by-jq is slow for hot paths |
| `a \|= f` collects paths, then `setpath` each | Update in place while walking when the left side is a fixed path | No path arrays |
| `--stream` is a mode the user chooses | Streaming chosen automatically when the filter allows (§6) | No rewrite for a big file |
| Flex and Bison | Hand-written lexer and Pratt parser | Error messages, no generator |

## 4. Pipeline

```
compile time (once per filter, cached by content hash)
  filter text → lexer + Pratt parser → AST → resolver (names, prelude, sugar) → IR + analyses
                                                   ├→ interpreter backend ┐
                                                   └→ bytecode backend ───┴→ JqFilter (immutable, shared)
run time (once per message)
  JSON bytes → reader (one pass, fills the tape) → JqFilter.run (fused loops, budgets) → writer → bytes
```

Both backends produce the same `JqFilter`; callers never know which one they got.

## 5. Value model

- One sealed `JqValue` (null, bool, number, string, array, object) with **two families**: views
  into the input bytes (tape-backed) and values the filter builds.
- **Tape**: one pass over the bytes fills one `long[]`; an entry is a type tag plus a byte offset;
  a container entry records where it ends, so skipping a subtree is one jump. A document costs its
  bytes plus one array, whatever its size.
- Rejected: reusing Jackson `JsonNode` / Vert.x `JsonObject` (one heap object per node, mutable,
  UTF-16 strings, number precision lost); an own immutable tree for input (still one object per node).
- **Strings stay UTF-8.** Field lookup compares bytes against a key constant prepared at compile
  time. UTF-8 byte order is jq's sort order (code point); `String.compareTo` is not.
- **Numbers keep their original text.** Arithmetic is `double` as in jq; an untouched number is
  written from its original bytes, so large literals round-trip as jq does since 1.7.
- **Built objects share their key list** (a shape per construction site); small objects are
  searched linearly, a hash index only above roughly eight keys. Built arrays are `Object[]` plus
  a length, frozen once handed on.
- Generated code often needs **no view object**: `.a.b.c` on input walks the tape with an `int`
  cursor.
- Number and slice types are identity-free final classes (ready for Valhalla; nothing depends on it).

## 6. Memory management

1. **Zero-copy input**: a `MemorySegment` (heap `byte[]`, direct buffer, mapped file).
2. **A view lives as long as its document.** Results normally go straight to the writer; a result
   that must outlive the document is `detach()`ed (copied into built values). Off-heap and mapped
   documents sit in an `Arena`; a stale view throws.
3. **Sharing is free; mutation needs ownership.** A value created inside `reduce`, `foreach`,
   `|=` or an array builder carries an owner marker of that running operation; while it matches,
   the engine appends or overwrites in place. The marker is cleared when the value escapes (bound
   to a variable, stored in another value, emitted). When in doubt, copy: a mistake costs speed,
   never correctness. This is what keeps `reduce .[] as $x ([]; . + [$x])` linear.
4. **No collections between pipeline stages**; an array exists only where the filter asks for one.
5. **An explicit `Workspace` per evaluation** (tape array, output buffer, path stack), passed as a
   parameter, never a `ThreadLocal` (one virtual thread per message pools nothing).
6. **Hard budgets**: nesting depth, bytes built, bytes written, steps or time checked on every
   loop back-edge. Over budget is a jq error, not an `OutOfMemoryError`.
7. **Automatic streaming**: for `<path>[] | rest` where `rest` never looks back at the whole
   document, the reader hands over one element at a time, each with its own small tape.
8. **Filters unload**: a compiled filter is a hidden class; dropped from the cache, it is reclaimed.

Recommended JVM settings, not required: `-XX:+UseCompactObjectHeaders`; generational ZGC or G1.

## 7. Front end and IR

- Hand-written lexer; Pratt parser over jq's operator table (`|`, `,`, `//`, assignment family,
  `or`, `and`, comparisons, `+ -`, `* / %`, unary, postfix), string interpolation, `as`
  destructuring with `?//`, `reduce`, `foreach`, `if`, `try`, `label`, `def`, `import`/`include`.
  AST: sealed interfaces and records with line and column.
- Resolver: binds functions by name and arity, variables, closure parameters; loads the prelude;
  rewrites sugar (`map(f)` → `[.[] | f]`, `..` → `recurse`).
- IR: about twenty node kinds (identity, field, index, slice, iterate, pipe, comma, literal,
  object and array construction, call, binding, reduce, foreach, if, try, label/break,
  alternative, update, path-of, intrinsic). Both backends read the IR and nothing else.
- Analyses, each buying a fast path: **cardinality** (exactly one / at most one / many → single
  results compile to plain expressions, no `emit`); **inlining** of non-recursive functions and
  closure arguments; **path shape** (fixed left side of `|=`, `del`, `path()` → update without
  path arrays); **root use** (→ automatic streaming); **touched fields** (key constants
  pre-encoded; reader may skip subtrees nobody reads); **constant folding**.

## 8. Execution model

- **Push**: a filter calls `emit` for every result; a pipeline becomes nested loops in one method.
  Rejected: pull iterators (an object per stage, a virtual call per result); a port of jq's
  backtracking VM (one generic loop the JIT cannot specialise).
- `emit` returns a boolean; `false` means stop (`first`, `limit`, `isempty`, `break`). No
  exceptions for control flow.
- Errors: one exception type without a stack trace (jq errors are values `try` catches); `?` and
  `//` call non-throwing variants of builtins.
- State lives in JVM locals (`reduce` accumulator, `$variables`).
- Recursive builtins (`range`, `recurse`, `while`, `until`, `repeat`, `limit`, `flatten`) are
  native loops. User-written recursion uses the Java stack (risk 1, §13).
- Path tracking is a second compiled variant, generated only for a filter used under a dynamic
  `path()`, `paths` or `|=`.

## 9. Code generation

- **Bytecode backend**: Class-File API (`java.lang.classfile`, final since JDK 24); one hidden
  class per filter; key bytes, shapes, literals and compiled regexes as class data loaded as
  dynamic constants. `a | b` is fused while generating (the code for `b` is emitted where `a`
  produces a result); a large or twice-needed continuation becomes a private static method, under
  a per-method size budget (HotSpot will not compile huge methods). Real closures (`Filter`
  objects through `invokedynamic`) only for recursive functions taking filter arguments. A
  line-number table maps bytecode to jq source lines; an explain switch prints IR and bytecode.
- **Interpreter backend**: each IR node is a small object with `run(ctx, in, out)` on the same
  push protocol. Its jobs: the oracle for the bytecode backend, cold one-off filters, and a
  GraalVM native-image CLI (no run-time class definition there).
- Policy: R8 compiles at deploy time and caches by content hash; the CLI interprets small
  inputs and compiles above a size threshold.
- Not used: Vector API (incubator in JDK 25; opt-in flag only), Stable Values (preview),
  `invokedynamic` type speculation.

## 10. JSON input and output

- **Reader**: one pass validates and fills the tape; flags each string ("contains escapes") and
  number ("fits a long", "needs a double", "too big"); scans eight bytes at a time with plain
  `long` reads; with touched fields, steps over unread subtrees by bracket matching. Input modes
  as jq: one document, concatenated documents, slurp, raw input, `--stream` events.
- **Writer**: UTF-8 straight into a sink (channel, `MemorySegment`, adapter for a stream). A
  string view without escapes and an untouched number are copied byte for byte; an untouched
  container is copied whole when the input was already compact. Output modes as jq (compact,
  pretty, raw, sorted keys, ASCII, join). Number formatting has its own routine matching jq.

## 11. Embedding API and R8

- Modules: `query-core` (format-neutral: execution, code generation, control IR, workspace,
  budgets; JDK only), `jq-core` (jq parser, resolver, builtins, JSON tape, reader, writer; depends
  on `query-core` and joni), `jq-cli` (jq's flags). Sketch; Phase 0 ticket 14 settles it.
- API sketch: `JqEngine.builder().limits(…).host(…).build()`; `engine.compile(text)` → immutable,
  thread-safe `JqFilter`; `filter.run(JsonDoc, Bindings, JsonSink, Workspace)`;
  `filter.needsWholeDocument()`.
- Host SPI: clock, environment policy, module loader, debug sink, extra inputs.
- Sandbox defaults for an embedding host: `env`/`$ENV` empty, `input_filename` off, `halt` an
  error, `import` through the host only, `now` from the injected clock, `debug`/`stderr` to the
  host's sink, `input`/`inputs` only when the host supplies documents. The CLI turns them on.
- R8 (in its own repo): a memory-tier body is wrapped as a heap segment, a spooled body is
  mapped in an `Arena` for the step; `JsonDoc` can be the envelope's shared parsed view;
  `$headers`, `$props`, `$keys`, `$partner` bound per message; compiled filters cached by content
  hash; small payloads on the message's virtual thread, large ones on the CPU worker pool.

## 12. Verification

- Five layers: (1) jq's own test files against both backends; (2) backend against backend on
  generated filters and inputs; (3) against the real jq binary on the same cases; (4) JSON reader
  conformance, including malformed input; (5) ownership property tests (an in-place update is
  never visible through another reference).
- Reference: the jq 1.8 line; the exact version is pinned by Phase 0.
- Benchmarks: JMH with the GC profiler. Scenarios: field extract `.a.b.c` on 1 KB / 100 KB /
  10 MB; filter and reshape `.orders[] | select(.total > 100) | {id, total}`; aggregate
  `group_by(.k) | map({k: .[0].k, n: length})`; accumulate `reduce .[] as $x ([]; . + [$x])` on
  1M elements (must be linear); update `.items[].price |= . * 1.2`; pass-through `.` on 10 MB;
  cold start. jq 1.8 is the reference; jaq, gojq and jackson-jq are for orientation only. Same
  scope for every tool.

## 13. Risks

| # | Risk | Handling |
| --- | --- | --- |
| 1 | **Deep recursion.** Push style uses the Java stack; jq optimises tail calls, the JVM does not. Simple self tail calls can become loops; calls inside "for each result", mutual recursion and non-tail recursion cannot | Native loops for standard recursive builtins; self tail call → jump; `StackOverflowError` → jq error; a trampoline or a large-stack thread as fallback. **Needs a spike** (Phase 0 ticket 3) |
| 2 | **Ownership markers.** One missed escape is a visible mutation | Conservative rule plus property tests |
| 3 | **Path semantics** (dynamic `path()`, `paths`, `del`, `\|=`) | Second compiled variant; the largest single piece of work |
| 4 | **Views pin the input** (a ten-byte view keeps a 10 MB document alive) | The `detach()` rule; a host detaches what it stores |
| 5 | **Number edge cases** (big literals exact, comparisons precise, arithmetic in doubles) | Follow jq 1.8 case by case; oracles |
| 6 | **Tape cost for tiny reads.** Indexing a whole document to read one field is wasted work, and R8 does such reads on every message | Touched-field skipping; measure against a forward cursor that stops at the field. **Needs a spike** (Phase 0 ticket 4); a second light reading mode if the difference is large |

## 14. Keeping the door open for XML

A constraint on this engine, not a plan to build the XML one. The language for XML would be
XPath / XQuery, as a second front end on the shared core.

1. `query-core` is format-neutral and never mentions JSON; `jq-core` sits on top.
2. Navigation goes through one navigation class per format, so an XML version can add axis steps
   without touching the generator.
3. The tape entry leaves room: reserved tag values, and a wider entry (two longs) per format for
   a parent link.
4. Strings are byte slices everywhere in the core.

## 15. Way of working

ADR-0001: the workflow of `subapavel/r8-mapping`, unchanged in substance. The environment is
Claude Code Cloud (4 vCPU). GitHub Issues are the tracker; one integration branch per spec.
