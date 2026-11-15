# Founding architecture — agreed direction (2026-10-30)

This is the architecture the owner and Claude worked out in the founding conversation, before the
repo existed. It is the **owner's stated direction**: grilling sessions start from it and confirm,
refine or overturn it — they do not re-ask what is written here. Each point becomes binding when
an ADR records it; until then, a Phase 0 ticket may change it with a reason. Section numbers are
referenced by the Phase 0 map's tickets.

## 1. Goal and scope

- A mapping engine and editor for enterprise integration, open source (Apache 2.0), Java 25 and
  newer only (records, sealed types, pattern `switch`, `ScopedValue`, Class-File API, virtual
  threads, structured concurrency behind a flag while it is preview).
- Milestone 1: **graphical mapping** in SAP PO style (queues, contexts, standard functions, Java
  UDFs), edited in a React + PatternFly UI, run compiled, exported to XSLT.
- Later: **XSLT 3.0 + XPath 3.1** on the same engine (maps, arrays, `xsl:iterate`, packages,
  JSON); XQuery 3.1 is close once XPath exists; a standalone XPath API (routing conditions, value
  extraction) for Flottila.
- Target workload: integration runtimes — compile once, run thousands of times on small and
  medium messages (KB to a few MB). Large files are the exception, handled by burst mode (§9).
- Separate repo until it is embedded in Flottila as its mapping engine and mapping UI.

## 2. Compiler shape — one core, several front ends and back ends ("LLVM shape")

```
Front ends                      Shared core                         Back ends
mapping model  ─┐                                                ┌─ interpreter (tier 0)
XSLT 3.0       ─┼─→ IR (over XDM, typed, F&O) → optimiser ────── ├─ bytecode (tier 1, Class-File API)
XPath 3.1      ─┤                                                └─ XSLT 3.0 exporter
XQuery (later) ─┘
```

- Every front end lowers to the same IR; data model, function library, optimiser, back ends,
  JSON support and burst mode are shared, so a feature built once serves every front end.
- A graphical mapping may call an XSLT/XPath function and vice versa, because both are IR.
- XSLT pipeline: StAX parse → stylesheet AST → static analysis (import/override precedence,
  packages, type inference, streamability) → IR → optimiser → back end.

## 3. XDM — one logical model, several physical representations

The logical contract is single: items, sequences, nodes, atomic values, maps, arrays (XDM 3.1).
Physical representations are many and the compiler picks per value site (like a database's one
relational model over row store, column store and index):

| Representation | Shape | For |
| --- | --- | --- |
| **Tiny tree** | struct-of-arrays (`int[] kind`, `int[] parent`, `int[] next`, `int[] nameCode` …); a node is an `int` handle; QNames interned to `int` in a name pool | XSLT/XPath navigation (axes, `//`, `preceding::`) |
| **Queue block** | `String[] values` + `int[] contextStarts` + `long[]` SUPPRESS bitset | graphical mapping queues and contexts |
| **Projected buffer** | only the source fields a mapping reads; values as offset + length into the input buffer | simple mappings, no tree at all |
| **Persistent map / array** | immutable, structure-sharing | JSON, XPath 3.1 maps and arrays |

- Atomic values: a sealed interface with records; ready to become Valhalla value classes later
  without an API change.
- Sequences are lazy pull iterators; materialised only where an expression needs it (`last()`,
  a variable read twice).
- Adapters (DOM, StAX, JSON) sit behind the same node contract.
- One `XdmValue` contract serves the interpreter and slow, generic paths; generated hot-path code
  uses the concrete representation classes directly (sealed hierarchy → exhaustive switches the
  JIT optimises).
- Where representations meet (a mapping calling an XSLT function on a queue), the compiler
  inserts an explicit conversion (queue → `array(item()*)`); the optimiser removes it when the
  callee accepts the queue directly. Sharing the IR and optimiser is the goal; forcing one runtime
  structure on everything is the explicit anti-goal.

## 4. IR and optimiser

- The IR is an algebraic data type: `sealed interface Expr permits …` with records
  (`Literal`, `VarRef(name, slot)`, `Path`, `FunCall`, `ForExpr`, `IfExpr`, constructors …).
- The optimiser is a set of pure rewrite functions using exhaustive pattern `switch` with record
  patterns (constant folding, dead branches, path fusion, common sub-expressions, inlining
  functions and UDFs). Adding an IR node makes every unhandled site a compile error.
- Every IR node carries the id of the authoring element it came from (target field, function box,
  XSLT instruction) — this drives tracing and the display queue in every tier.
- Serialized IR ("compiled mapping/stylesheet", like Saxon's SEF): compiled at build or deploy,
  only deserialised in production — no XSLT parsing, errors caught at build time, a smaller runtime.

## 5. Back ends and execution

- **Interpreter (tier 0)**: fast start, editor test runs, GraalVM native image (where bytecode is
  impossible), so it must be reasonably fast on its own.
- **Bytecode (tier 1)**: Class-File API (`java.lang.classfile`, final in 24) — no ASM; each hot
  template or mapping becomes a **hidden class** (`Lookup.defineHiddenClass`), unloadable with its
  stylesheet; constants via `ConstantDynamic` / class data; the JIT then inlines expressions into
  the template body.
- **Tiering**: XSLT counts invocations and compiles hot templates past a threshold; an `eager`
  option compiles everything at load for predictable latency. **Mappings compile eagerly at
  deploy** (like PO at activation); the interpreter serves the editor's test runs.
- Differential tests: interpreter and bytecode must produce byte-identical output.
- Static context: an immutable record built by the compiler. Dynamic context: frames with slots
  (`Object[]` or specialised arrays), never maps by name. Tunnel parameters: a persistent map in
  the frame.
- `ScopedValue` for implicit, thread-bound state: current output destination, error listener,
  collation, `current-dateTime()` fixed per transformation.
- The main transformation is single-threaded and deterministic. Virtual threads for I/O
  (`doc()`, `collection()`, `unparsed-text()`, parallel `xsl:result-document`); structured
  concurrency for an opt-in parallel `xsl:for-each`, behind a flag while preview.
- Startup: serialized IR, Leyden AOT cache (JEP 483/514/515), GraalVM native image optional
  (interpreter only).
- Refined by ADR-0021 (grilling #17): milestone 1 has one back end, the interpreter in
  `engine/runtime`, closure-compiled, with eager compile at deploy, no tiering, no compiled-mapping
  cache and no AOT cache. The bytecode tier's shape (`engine/bytecode`, a hidden class per mapping,
  class-data constants) is fixed for the spec after milestone 1.

## 6. Graphical mapping on this core

- A graphical mapping is a **target-driven** function graph: each target field has a tree of
  functions over source fields. Lowering: target field → element constructor; target cardinality →
  `for-each` over the source context; function tree → IR expression.
- **Queues and contexts** are logically `array(item()*)` (one member per context). PO operations
  map to array operations: `removeContexts` = flatten to one context; `splitByValue` = each item
  its own context; `collapseContexts` = first item of each context; `useOneAsMany` = replicate by a
  second queue's contexts; SUPPRESS = a marker in the context (physically the bitset, §3).
- UDF kinds map onto the same shapes: single value = mapped over every item; all values of a
  context = applied per array member; all values of a queue = applied to the whole array.
- **Mapping model is data, never generated code** — declarative, versioned, diffable, validated
  by a schema; compiled to IR at run or deploy. (SAP PO generates Java, which is hard to debug
  and migrate.) Format: Phase 0 ticket 5.
- Structures (XSD, WSDL, JSON Schema, OData metadata) load into one structure model with types and
  cardinalities, shared by editor and compiler. Details: Phase 0 ticket 7.
- **Display queue / tracing** in every tier through the IR node ids (§4).
- **Export to readable XSLT 3.0** — no vendor lock-in, and the export is the differential oracle
  (§10).
- UDFs in Java via the same mechanism as extension functions (annotation + `MethodHandle`) with a
  signature that declares its kind.
  Settled by ADR-0020 (grilling #16): static methods over `engine/udf-api`, compiled in-process
  from the workspace, guarded in-process (class loader per library, allow-list, interruption guard).
- Later: import SAP PO message mappings from ESR exports (same context semantics, near 1:1);
  UDFs ported separately.
- Implementation order agreed: core (XDM, IR, F&O subset, interpreter) → **mapping front end
  first** (simpler than XSLT: no templates, modes, import precedence) → XSLT front end → bytecode
  tier speeds up both. Whether milestone 1 already ships bytecode: Phase 0 ticket 17.
  Settled by ADR-0021 (grilling #17): milestone 1 ships only the closure-compiled interpreter; the
  bytecode tier is the first engine spec after it.

## 7. Mapping performance — how to match or beat SAP PO

Why PO is fast: compiled Java, strings everywhere, only used fields, sequential target output.
Rules for us:

1. Queue as struct-of-arrays (§3), never `List<List<Item>>`; `removeContexts` drops the boundary
   array without copying values.
2. **Projection**: the compiler knows every source path a mapping reads; the parser skips the rest
   and builds nothing for it (largest single win on IDocs and big messages).
3. **Streamed output**: target-driven generation in document order straight into the serializer;
   no target tree in memory.
4. **Strings by default**: values stay `String` or offset + length into the input buffer; numbers
   and dates are converted only by functions that need them.
5. **Eager compilation at deploy**; the interpreter only for the editor.
6. **Shape specialisation**: 1:1 assignments, unchanged contexts and constants get direct code
   without queues (most fields of a typical mapping).

Expected advantages over PO: modern JIT/GC and compact object headers (JEP 519), whole-mapping
optimisation (dead branches, shared sub-expressions, UDF inlining), projection and zero-copy
values. Risks: naive context functions (`useOneAsMany`, nested `splitByValue`) that copy queues;
a shared runtime structure that makes mappings pay for XSLT's generality. Proof: import real-shaped
mappings with synthetic inputs, JMH latency on small messages, throughput and memory on large ones,
early — right after the first bytecode prototype. Mapping time is usually a few ms of PO's total;
the platform's throughput depends on the rest of the pipeline as well.
Refined by ADR-0021: the proof runs in milestone 1 on the interpreter. It uses three corpus
benchmark mappings, targets of ≤ 500 µs per small message and ≥ 20 MB/s on large ones, and an
optional, informative PO observation request.

## 8. XSLT and XPath specifics (later milestones)

- Own XPath 3.1: the JDK has XPath 1.0 only, no usable standalone 3.1 library exists, and XSLT and
  XPath must share data model, types and IR so the optimiser sees across the boundary; match
  patterns are an XPath subset. Rough share of the work: XPath language ~25 %, F&O library
  ~35–40 % (200+ functions), XSLT proper ~20–25 %, serialization and API ~10–15 %.
- **Biggest risk: the F&O library** (XSD regex dialect ≠ Java regex, `format-number`,
  `format-dateTime` with languages and calendars, collations, Unicode normalisation).
- **JSON**: `parse-json` / `json-doc` to maps and arrays, lookup `?`, `json-to-xml` /
  `xml-to-json`, output methods `json` and `adaptive`; own streaming JSON parser straight into
  persistent maps/arrays and a second mode straight into a tiny tree; key hashes precomputed for
  known keys; keys keep insertion order (as XPath 4.0 drafts); an option to parse numbers as
  `xs:decimal` so large ids and amounts keep their precision.
- Serializer writes straight to `OutputStream` with its own UTF-8 encoder and precomputed escape
  tables per output method; Vector API (incubator) only behind a flag.
- Extension functions: annotated Java methods (`@XPathFunction(ns, name)`), registered through
  `ServiceLoader`, called through `MethodHandle` (bytecode calls them directly).
- APIs: a JAXP `TransformerFactory` adapter (drop-in for Xalan/Saxon) and a modern immutable,
  thread-safe API (`Xslt.compile(src).load().transform(in, out)`).
- Never use Saxon's source as a model (MPL 2.0 obligations); the specification and the tests are
  the source of truth.

## 9. Streaming

Not needed for most integration traffic (small messages; big files are split before mapping; full
XSLT 3.0 streamability is restrictive and the hardest part of the spec). Plan: **burst mode** —
read the document with a pull parser, lift each record into a small tree with `copy-of()`, run
ordinary unrestricted XSLT inside the record; memory is bounded by one record. Full streamability
(whole-document aggregation, streamed grouping) only on real demand, prepared for by putting
streamability analysis in the static-analysis stage and IR.

## 10. Verification

- **W3C QT3 and XSLT 3.0 test suites** (tens of thousands of cases) — the red tests for XPath/XSLT
  already exist; percentage passing is the progress metric.
- **Differential oracle**: Saxon-HE (MPL 2.0, test scope only) on the exported XSLT of every
  mapping, and on generated XPath expressions and documents.
- **Golden corpus**: synthetic mappings with inputs and the outputs PO semantics demand.
- **Ratchet**: committed pass lists, coverage, benchmark baselines; a merge may only keep or raise
  them.
- JMH against Saxon-HE (XSLT) and against PO-shaped workloads (mappings).

## 11. Modules (sketch, settled by Phase 0 ticket 12)

JPMS modules along the pipeline: `xdm`, `xpath`, `xslt-compiler`, `runtime`, `serializer`,
`jaxp-adapter`, plus the mapping front end, structure import, the service and `ui/`. Root package
`io.flottila.mapping`.

Refined by ADR-0014: the engine modules are JPMS; the service is a Quarkus application on the
class path over them, its module rules enforced by ArchUnit.

Settled by ADR-0016 (grilling #13) for milestone 1. The engine has nine JPMS modules under
`engine/`: `xdm`, `structure`, `structure-import`, `ir`, `serializer`, `runtime`, `mapping`,
`xslt-export`, and the facade `api` that the service uses. `xpath`, `xslt-compiler` and
`jaxp-adapter` come with their specs. The build, CI and gates are in ADR-0017. ADR-0020 adds `udf-api` and
`udf` (eleven engine modules).

## 12. UI

React + PatternFly (also Flottila's future UI). Editor shape decided by the prototype ticket
(three variants); the editor's model types are generated from the mapping-model schema.

## 13. Effort estimate given at the start

For one experienced developer with AI agents: MVP of the XSLT core 2–4 months, ~95 % conformance
+3–5, performance tier +3–6, streaming +3–6; ~12–18 months for a serious engine (vs 3–5 years
without AI). For a platform's own needs phases 1–3 may suffice. Mapping-first changes the order,
not the total.
