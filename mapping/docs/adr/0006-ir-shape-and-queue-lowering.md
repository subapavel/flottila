# IR shape and queue lowering: sealed `Expr`, explicit `Lift`, target slots

Status: accepted (2026-10-30, grilling #5); refined by ADR-0008 (unmapped rules use effective occurrence); refined by #26: the IR entry is `TargetDocument(prefix, root, rootSlot, content)`, which creates the root once with slot number 1 and has no origin; constructors carry the prefix lowering assigns (ADR-0022); `FunCall` and its binding by name: ADR-0025; `ifWithoutElse` is built in spec 3 (#48 Q1); `SourceQueue` names its field by element steps and its context by depth; refined by #51: `Lift(queues, slotVars, body, origin)`; refined by #52: `TargetSlots(queue, parentSlot, min, max, slotVar, valueVar, body, origin)`; refined by #63: `TargetRepeat(copies, parentSlot, slotVar, body, origin)` for unmapped n..n fields; refined by #83: generating functions, `AtomicToQueue`, `TargetRepeat(copies, parentSlot, slotVar, value, body, origin)`; amended by the spec-4 grilling (2026-11-05): no `ContentType` in milestone 1; refined by #102: `AttributeConstructor(prefix, name, value, origin)`, `SourceQueue(field, contextDepth, structureField, attribute, origin)`; refined by #98: `TargetDocument(prefix, root, rootSlot, content, input)` and `SourceQueue(field, contextDepth, structureField, attribute, arrays, origin)`; amended by F-37 (#133): `createIf` casts its input as PO does, `po:CREATEIF`; F-38 marks the `min` check of a missing context contradicted (#134); amended by F-44 (#215): the boolean family casts as `createIf` does, `err:FORG0001`, and `ifWithoutElse` follows the observed SUPPRESS and context model; refined by #217 (F-46): generating blocks, `TargetRepeat(copies, parentSlot, slotVar, value, valueVar, body, origin)`; amended by F-47 (#218): SUPPRESS is the greatest `sort` / `sortByKey` key, `collapseContexts` keeps a SUPPRESS first slot and gives no value for an empty queue; amended by F-45 (#216): `replaceValue` is a context function, because it reads SUPPRESS, and SUPPRESS passes a `Lift` as observed for `toUpperCase` and `concat`; amended by F-48 (#219): `exists` gives `true` per slot and counts SUPPRESS, `mapWithDefault` keeps a context of SUPPRESS only

This ADR records `docs/FOUNDING-ARCHITECTURE.md` §4 and §6 for milestone 1 and fills its gaps.
Every front end lowers to records of `sealed interface Expr`. The records are `Literal`,
`VarRef`, `Let`, `FunCall`, `Cast`, `SourceQueue`, `QueueToArray`, `ArrayToQueue`,
`AtomicToQueue`, `Lift`, `TargetSlots`, `TargetRepeat`, `ElementConstructor`,
`AttributeConstructor` and `TextConstructor`. Each record ends with `AuthoringId origin`.
`AuthoringId(String value)` is opaque, stable and unique within a mapping. It names a target
field mapping, a function box or a source-field box, and later an XSLT instruction. Tracing,
the display queue and design-time errors refer to nothing else.

The IR is pure data: records, enums, strings, ints, `Optional` and `List` of those, and a symbolic
`FunctionRef(name, params, result)`. It holds no `MethodHandle` or `Class`; back ends link calls.

## Typing

- `IrType` is `AtomicType` (the seven types of ADR-0004, `INTEGER` ⊑ `DECIMAL`), `QueueType` or
  `ContentType`. A node's type is derived; only `VarRef` stores one.
  Amended (spec-4 grilling, 2026-11-05): in milestone 1 `IrType` is `AtomicType | QueueType`.
  Nothing in specs 1–3 needed `ContentType`; it comes with JSON output (the first spec after
  milestone 1), where constructors of two output formats need a type.
- The front end emits well-typed IR. It inserts `Cast` where a signature needs a typed value
  (a failed cast raises `FORG0001`, ADR-0005) and `AtomicToQueue` where a context or queue
  function gets an atomic input.
- User errors are design-time errors raised by lowering with their `origin`: an unresolved
  function, a wrong arity, a parameter that cannot be cast, an untranslatable pattern
  (ADR-0003), a target leaf with `minOccurs > 0` and no mapping.
- `IrVerifier` re-checks the IR after lowering and after every optimiser pass. A failure there
  is a compiler bug.

This refines ADR-0004: typed values exist only inside a `Lift` body or a `FunCall`. A queue
meets arrays and XDM callees only through `QueueToArray` / `ArrayToQueue`, and atomic values
only through `Lift` and `AtomicToQueue`.

## Functions by execution kind

- A source field becomes a `SourceQueue`. Equal (field, context) pairs share one queue of the
  projection plan.
- A **single-value** function or UDF with at least one queue input becomes
  `Lift(queues, body)`. The body is an atomic `FunCall` over the slot variables; a `Literal`
  argument stays in the body and so applies to every slot. `Lift` has the export's `po:lift`
  semantics:
  - context i of the result pairs context i of every input; the result has as many contexts as
    the input with the most;
  - if any input's context i is empty or missing, result context i is empty;
  - otherwise the longest input sets the slot count and shorter inputs reuse their last value
    inside the context;
  - SUPPRESS in any input gives a SUPPRESS slot without running the body; the other inputs are
    still consumed.
- Since #51 the record is `Lift(queues, slotVars, body, origin)`. Slot variable k holds input k's
  value at the slot. The body sees only the slot variables and is of type STRING; for a box it
  is the atomic `FunCall`, with the inputs as `VarRef`s and then the params as `Literal`s. Lowering
  writes one `Lift` per box; fusing them is optimiser work.
- A single-value call with no queue input stays an atomic `FunCall`.
- **Context** and **queue** functions, standard or UDF, are `FunCall`s over queue blocks into a
  runtime library, bound by their name in the function catalogue. Box parameters are `Literal`
  arguments. Context operations have no IR records of their own; the optimiser matches by name.
  `createIf` and `ifWithoutElse` are context functions, because they produce SUPPRESS.
  `replaceValue` is a context function, because it reads SUPPRESS: a `Lift` would pass a SUPPRESS
  slot on without calling it, and PO replaces that slot too (F-45, amended with #216).
- Since #217 (F-46) a queue function the catalogue marks `generating` (ADR-0026), today only
  `copyValue`, gives a **generating block**: a queue of one context whose first slot is one value
  for every occurrence of a target field under every parent occurrence, as a constant's value is.
  At a context or queue function's input it is the queue it is. At a single-value function's
  input, of a `Lift` or of an atomic call, lowering refuses it with `unsupported-generating-input`
  at the consuming box; SAP PO accepts that graph, so it is `copyValue`'s deviation.

## Target fields

Target fields are produced in target document order and written straight to the output
destination; no target tree is built. This replaces FOUNDING §6's "target cardinality →
for-each over the source context".

- The queue of each mapped target field is a `Let`, evaluated lazily on first read. Calls
  therefore run in target document order, as in PO, and a field never created is never
  evaluated.
  Until `Let` is an IR record (ADR-0025: none in spec 2), each back end evaluates a
  `TargetSlots` queue at most once per mapping run, on first read (#52).
  The XSLT export binds a queue under an occurrence as a `po:queue<n>` variable at the top of
  the root template; first read there rests on Saxon-HE 12.5 evaluating a variable lazily
  (observed, not required by XSLT 3.0), pinned by `target/firstRead/001`–`002` (#52).
- The root is created once with slot number 1.
- `TargetSlots(queue, parentSlot, min, max, …)` reads context p, where p is the parent's slot
  number. Each slot gets its **slot number**, its position across all contexts of the queue.
  The slot number is the context index of the children's queues.
  - A SUPPRESS slot creates nothing but keeps its number, so it consumes its child context.
  - Creation stops after `max` occurrences. Truncated slots keep their numbers.
  - Contexts beyond the parent's last slot are never read.
  - Fewer than `min` created occurrences raise `po:MINOCCURS`, the export's error QName.
    SUPPRESS slots do not count toward `min` or `max`.
  - Since #52: `slotVar` holds the occurrence's slot number in the body and is its children's
    `parentSlot`; `valueVar` holds its value. A mapped field stands at any depth under mapped
    ancestors. Since #63 it also stands under an unmapped n..n parent (created n times, below)
    and under an unmapped optional parent (left out with it); under an unmapped parent with
    `0 < minOccurs < maxOccurs` it stays `unsupported-field`, because PO needs a mapping there.
  - `min` is checked before the first occurrence of context p is written. A context the queue
    lacks counts as empty (assumed for the `min` check; #31 d1 observed only that an optional field
    gets no occurrence there). **Contradicted** by #31 `minOccurs-004` (F-38): PO gives a `Row`
    (1..2) under the parent occurrence whose context the queue lacks one occurrence with the
    queue's last value. The rule stays as written until `obs-target-creation-2` (#134) separates
    the readings; `target/minOccurs/004` keeps its expectation and says so. `po:MINOCCURS` is
    raised at the target field mapping (`TargetSlots.origin`). Nothing is padded up to `min`.
  - Observed (#31, PO and CPI, no finding yet): a complex target node with a mapped value and no
    mapped child is written with the value as its text (`<Row>a</Row>`); with a mapped child
    (`max-003`) it carries no text. #134 asks again.
  - The export is `po:at-least(po:slots(queue, p, max), min, authoringId)`; `po:slots` gives
    `[slot number, value]` per created occurrence, and `po:at-least` is written only when
    `min > 0`. Its third argument, the target field mapping's id, names the field in the
    `po:MINOCCURS` message, as the runtime does (spec-2 review).
- A **generating function** (a function graph with no source field whose result is a single
  value; one that yields a queue, such as `count` of a constant, maps as a queue, F-21; since #217
  also a graph whose result is a generating block, F-46) on a target becomes
  `TargetRepeat(max(1, minOccurs))` per parent slot. Its children are numbered
  `(p − 1) · copies + j`.
  - Since #83 a box lowers to a queue or a single value. A single-value function whose inputs are all single values (or that has none, as `constant`) is an atomic `FunCall`, and its params are `Literal`s. Every input of a single-value call is evaluated before the call, as PO computes every input queue of a box, so `if` has no untaken branch: a data error in either branch is raised at its box in both back ends (F-22).
  - Inside a `Lift` body a single value applies to every slot of every context. At a context or queue input it is `AtomicToQueue(value, origin)`: one context with one value (ADR-0004). It is typed `QUEUE` over a `STRING` value, it is not traced, and it exports as `[(value)]`.
  - On a target, the `TargetRepeat`'s `origin` is the target field mapping's authoring id, and its single value is computed once per copy; a generating block is read once per run (below).
  - The trace records a generating field's occurrences per parent slot (all created, the count `copies`; all suppressed when a generating block's first slot is SUPPRESS or missing, #217) and the value of each box in the graph, as for any other target field (GLOSSARY "Trace").
  - Since #217 (F-46) a generating block on a target is the `TargetRepeat`'s `value`, a `QUEUE` in the parent's scope, with `valueVar`, a `STRING` bound only in the body, on a leaf, an attribute and a structure field alike; a leaf's text and an attribute's value read `valueVar` (`value<d>`), and a structure field's element holds its children only. `valueVar` is present exactly when `value` is a `QUEUE`; a `STRING` value keeps the rule of the structure field below. `IrVerifier` checks both.
  - Each back end reads the block at most once per run, on first read, as a `TargetSlots` queue, so a run without a parent occurrence never reads it. Per copy, a value as the block's first slot (counted across its contexts) goes into `valueVar` and the body is written; a SUPPRESS first slot, or a block without a slot, writes nothing, and the copy keeps its slot number `(p − 1) · copies + j`. The trace marks those copies suppressed (`Tracer.Repeat.suppressed`); the block's box shows its one context (`[["b"]]`, `[[null]]` or `[[]]`), and the wire is unchanged.
  - The export binds the block as a `po:queue<n>` variable at the top of the template, under the root too, and writes each copy's body inside `<xsl:for-each select="po:generated($po:queue<n>)">`, which binds `valueVar` to its item. `po:generated` (`core.xsl`) gives the block's first slot when it is an `xs:string`, else `()`.
  - On a structure field the value stays on its `TargetRepeat`: `value` is an optional `STRING` in the parent's scope (it cannot read its own `slotVar`, as `TargetSlots.queue`). Each copy evaluates it before its body and never writes it, in every compile, traced or not, so the trace holds its boxes and a data error in it is raised at its box (ADR-0018 l.48–49, l.124–127). A leaf's value stays in its text; an unmapped field has none. The export writes `<xsl:value-of select="substring(value, 1, 0)"/>` before the body, a zero-length text node. That Saxon-HE 12.5 evaluates it is observed, not required by XSLT 3.0, and a differential error test in `engine/oracle` pins it (#83).
  - Documented (SAP PI 7.11): the counts. Assumed (`obs-constant`): the repeat per parent occurrence, `concat` of constants, and `AtomicToQueue`'s shape.
- An unmapped structure field with `minOccurs = maxOccurs = n > 0` is created n times. An
  unmapped optional field is left out with its subtree. Since #63 both lower to
  `TargetRepeat(copies, parentSlot, slotVar, body, origin)`, the generating-function shape, with
  copies n and its children numbered `(p − 1) · n + j`. An unmapped field has no authoring element,
  so its `origin` is its field path (e.g. `/Parents/Pair`); it reaches only compiler-bug messages,
  never a trace entry or a runtime error. Lowering follows the recursion cap of ADR-0008.
- Since #63 a source structure field maps onto a target structure field: its source queue is one
  `""` per occurrence (ADR-0008), `SourceQueue` carries a `structureField` flag, and the export
  reads it as `[field ! '']`.
- Since #102 a target attribute is a leaf, 0..1, or 1..1 when `use="required"`.
  - It is a `TargetSlots` (max 1), or a generating `TargetRepeat` (1 copy), whose body is an
    `AttributeConstructor`.
  - These items come first in their element's content, in target field order (ADR-0008).
    `IrVerifier` refuses one placed after other content, and an `AttributeConstructor` anywhere
    else.
  - A SUPPRESS slot or an empty context writes no attribute. A `""` value writes `a=""`, as a
    leaf writes `<E/>` (assumed, `obs-structures` row `str-attr`).
  - A source attribute's `SourceQueue` sets `attribute`, and its default context is its element
    (ADR-0007). The export reads `@name` and writes `xsl:attribute`.
  - A simple-content leaf's content is its attributes, then its text.
- Since #98 the IR names its input format: `TargetDocument.input` is an `InputFormat` (XML or
  JSON), set by Lowering from the source structure's root selector (ADR-0008). A JSON
  `SourceQueue` carries `arrays`, the depths (root = 1) of its path's steps that are array
  properties, so the runtime
  and the export read each array item as one occurrence.
- Constructors are records per output format. Milestone 1 has the XML ones only. A JSON target
  adds records under the same `TargetSlots` / `TargetRepeat`; nothing existing changes.
- An error after part of the output was written leaves the destination undefined. The API
  says so, and callers that need all-or-nothing buffer the output, as the service does.

## Optimiser, display queue, serialisation

- The milestone-1 optimiser is the pure-rewrite framework plus constant folding of
  deterministic single-value `FunCall` / `Cast` over `Literal`s. The catalogue gains a
  `deterministic` flag. A replacement keeps the `origin` of the node it replaces. Rewrites never
  reorder or merge non-deterministic calls (`counter`, UDFs without the flag). FOUNDING §7.6
  shape specialisation is a back-end fast path in milestone 1: for example, a one-input `Lift`
  reuses its input's `contextStarts` and SUPPRESS bitset.
- Display queue: the outermost node of each `origin` is observable. The hook is
  `Tracer.value(AuthoringId, QueueBlock | AtomicValue)`, called once per evaluation and off when
  no tracer is set. `Literal`s are shown from the model, not traced. Editor test runs use
  unoptimised IR in the interpreter, so every box has its node. Bytecode emits the hooks only in
  a traced compile.
  Since #53 a traced `TargetSlots` reports `Tracer.occurrences(field, parentSlot, queue, min,
  max)` before its min check, so a shortfall is always traced. The slot statuses are derived from
  context p and max when the trace is built, off the run thread.
- Milestone 1 does not serialise IR. Mappings compile from the mapping model at deploy
  (FOUNDING §5). The pure-data rule keeps a later SEF-like format mechanical; that format must
  version the node list.

## Considered options

- **Lifting as an implicit meaning of `FunCall`, keyed on the execution kind**: rejected.
  `FunCall` would mean two things (an XPath call and PO alignment), typing would depend on the
  catalogue, and the per-slot body would be hidden from folding and UDF inlining.
- **One IR record per context function**: rejected. About fifteen records that every back end,
  the exporter and the optimiser must switch on, growing with a catalogue that is data.
- **Target creation as for-each over the source context or over array operations**: rejected.
  It cannot express SUPPRESS consuming its child context, and it spreads PO's occurrence rules
  over generic nodes.
- **Buffering the whole output, or a validating pre-pass**: rejected. The first goes against
  FOUNDING §7.3, the second needs a second walk with duplicated logic.
- **A structured authoring id (path plus element kind)**: rejected. It ties the IR to the shape
  of the mapping model, and no second implementation exists.
- **Serialised IR in milestone 1**: deferred. It would freeze a persisted node list before the
  XSLT front end tests the IR.
- **Empty element or omission for an unmapped mandatory leaf**: rejected. Both give invalid
  output silently.

## Consequences

- Binds the interpreter, the bytecode tier and the exporter: `Lift` ≡ `po:lift`,
  `TargetSlots` ≡ `po:slots` + `po:at-least`, one error QName.
- The mapping model (#6) must give every target field mapping, function box and source-field
  box a stable id that survives editing and diffs.
- Assumed readings until observed cases confirm them (ADR-0002, #14):
  - an empty or missing context in a `Lift` input gives an empty result context;
  - SUPPRESS in a `Lift` input gives SUPPRESS and consumes the other inputs: observed for
    `toUpperCase` and `concat` (`obs-lift-reuse` lift-2, PO 7.50 and CPI, F-45), assumed for every
    other single-value function and UDF. `replaceValue` replaces a SUPPRESS, so it is a context
    function since #216 (below);
  - SUPPRESS does not count toward `minOccurs` / `maxOccurs`; truncated slots keep their
    numbers;
  - a constant-only graph through a function (`concat(const, const)`) on a target is a
    generating function; its children are numbered `(p − 1) · copies + j`;
  - an unmapped target leaf with `minOccurs > 0` is a design-time error.
- Assumed readings since #51, until observed cases confirm them (ADR-0002, #61): a shorter input
  whose last value is SUPPRESS gives SUPPRESS when reused (#32 lift-3).
- `copyValue` gives a generating block (observed on PO 7.5 and CPI, F-46, #217; it replaces the
  assumed reading of #79, one context per input context): its position counts from 0 across the
  whole input queue, context boundaries ignored; a SUPPRESS slot counts as a position and is
  copied, so no occurrence is written; the selected value goes into every occurrence of the target
  field under every parent occurrence, as a constant's does (`fn/copyValue/001`–`004`). Assumed,
  pinned by unit tests only, until the observation request F-46 names after milestone 1 answers:
  - a position past the last slot, and an empty queue, give one empty context, so no copy is
    written;
  - at a context or queue function's input the block is that queue, so `count(copyValue)` gives
    one context and a V in the first Item only, as `constant-006` does for `count` of a constant;
  - a missing first slot traces like SUPPRESS: every copy suppressed;
  - a SUPPRESS or missing first slot on a field with `minOccurs > 0` writes no copy and raises no
    `po:MINOCCURS`: a `TargetRepeat` has no min check.
- `createIf` casts each value as PO's `Bool.toBoolean` does (observed, #31 spec-2 part, F-37;
  amended with #133, replacing the assumed reading of #51): `true` in any case and `1` give `""`,
  `false` and `0` give SUPPRESS; any other value, `""` included, raises `po:CREATEIF` at the
  `createIf` box (`Cannot cast '<value>' to boolean`), in the runtime and the export. Assumed until
  `obs-target-creation-2` (#134) answers: `false` in any case (only `false` was observed); no
  trimming (` true` raises); a SUPPRESS slot gives SUPPRESS; an empty context gives none.
- The boolean family casts each value the same way (observed, `obs-boolean`, PO 7.5 and CPI,
  F-44; amended with #215, replacing the exact-text-`true` reading of #77 and #78), through one
  cast that `createIf` shares: `and`, `or`, `not`, `equals`, `notEquals` and `if` raise
  `err:FORG0001` at their box on a value that is no boolean (`Cannot cast '<value>' to boolean`),
  in the runtime and the export; `equals` and `notEquals` compare the cast values. Assumed: `and`
  and `or` cast both values, the second even where the first decides; the error code, which is the
  one every standard function's malformed value has (ADR-0025).
- `ifWithoutElse` (observed, `obs-boolean`, F-44; amended with #215, replacing the assumed reading
  of #78): the result has as many contexts as its `then` input, and context i pairs context i of
  both. Context i is empty where the condition has no context i; otherwise the longer context sets
  the slot count, the shorter reuses its last value, and an empty one gives SUPPRESS at every slot
  of the other (PO shows `<null>` for an empty condition context; both create nothing and keep
  their slot). SUPPRESS in either input, or a false condition, gives SUPPRESS, whatever
  `keepSuppressValues` says; a true condition gives the `then` value. Assumed: the parameter has no
  effect (none was seen); only a condition paired with a value is cast, and one that is no boolean
  raises `err:FORG0001` at the box; a `then` context beyond the condition's last is empty rather
  than one SUPPRESS per value (`fn/ifWithoutElse/009` tells them apart only once
  `collapseContexts` keeps SUPPRESS, as `obs-context-functions` ctx-6b shows).
- `replaceValue` is a context function (observed, `obs-lift-reuse` replaceValue-002 and the
  display queue of `obs-statistic` sum-003, PO 7.50 and CPI, F-45; amended with #216, replacing
  the single-value reading of spec 2): it gives its replacement at every slot, SUPPRESS included,
  so its result holds no SUPPRESS; its contexts stay as they are, an empty context stays empty,
  and a queue with no context gives none. A single-value input enters it as `AtomicToQueue`, so
  `replaceValue` of a constant is a queue of one context with one value and maps as a queue
  (F-21). *Assumed*: under a repeated parent that queue gives its value in the first parent
  occurrence only (V in Item 1 only, as `count` of a constant does); F-45 lists it for the
  observation request after milestone 1.
- SUPPRESS through the context functions since #51 (`assumed` unless marked). The seeded
  agreement test `SuppressContextFunctionsAgreementTest` pins each reading on both back ends;
  corpus cases cover those a root-level target can show, and the numeric-key error gets its
  corpus case with #52:
  - `removeContexts`, `splitByValue`, `useOneAsMany`, `formatByExample`, `sort` keep SUPPRESS
    as a slot with its bit. In `splitByValue` valueChange, SUPPRESS equals only SUPPRESS; in
    emptyValue, SUPPRESS is not `""`; each SUPPRESS of eachValue is a context of its own
    (`splitByValue` observed on PO 7.5 and CPI in all three modes, F-47: `fn/splitByValue/019` to
    `021`). `useOneAsMany` repeats and counts SUPPRESS like a value (observed on PO 7.5 and CPI,
    F-48: the probes of obs-useoneasmany for `fn/useOneAsMany/005` and `009`), and a SUPPRESS is
    the one value a context of its first input needs (ADR-0025). `formatByExample` counts a
    SUPPRESS slot toward its equal-count rule.
  - `collapseContexts` keeps a SUPPRESS first slot as SUPPRESS and gives `""` for an empty
    context; a queue with no slot and at most one context (one empty context, which PO's queue
    holds as nothing at all, or none) gives one empty context, no value (observed on PO 7.5 and
    CPI, F-47: obs-context-functions ctx-6b, `fn/collapseContexts/003`, `fn/splitByValue/019` to
    `021`; it replaces the documented #2 §3 reading "Empty contexts (= ResultList.SUPPRESS) are
    replaced by empty strings" for SUPPRESS).
  - `sort` and `sortByKey` order a SUPPRESS value or key as the greatest in every comparison,
    so it comes last ascending and first descending (observed on PO 7.5 and CPI for a
    case-sensitive `sort` in both orders and an ascending case-sensitive `sortByKey`, F-47:
    `fn/sort/012`, `013`, `fn/sortByKey/006`; assumed for the other comparisons and a descending
    `sortByKey`), except that a numeric `sortByKey` key must not be SUPPRESS: it raises
    `po:SORTBYKEY` (#2 §3 [SAP✓ STD]; documented).
  - `exists` and `mapWithDefault` (observed on PO 7.5 and CPI, F-48: `fn/exists/003`, `004`,
    `fn/mapWithDefault/002`, obs-context-functions ctx-2 and ctx-4; it replaces #57's assumed
    reading that a context holding only SUPPRESS counts as empty): a SUPPRESS slot counts like a
    value and `""` is a value. `exists` keeps the contexts and gives `true` per slot and one
    `false` per empty context; `mapWithDefault` fills only an empty context, so one holding only
    SUPPRESS is kept. A queue with no context gives no context.
