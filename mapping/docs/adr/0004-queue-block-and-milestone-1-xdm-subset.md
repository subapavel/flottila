# Queue block and the milestone-1 XDM subset

Status: accepted (2026-10-30, grilling #4); refined by ADR-0006 and ADR-0008 (a structure field's queue holds `""` per occurrence); SUPPRESS bitset shape since #51

This ADR records `docs/FOUNDING-ARCHITECTURE.md` §3, §6 and §7.1/§7.4 for milestone 1. A queue is
logically `array(item()*)`: one member per context, `xs:string` values, SUPPRESS a marker in the
context. Physically it is a **queue block**, an immutable struct-of-arrays:

- `String[] values` holds n slots.
- `int[] contextStarts` holds c + 1 entries. `[0] = 0` and `[c] = n`, the end sentinel. Entries
  never decrease, and c ≥ 0.
- `long[] suppress` is the SUPPRESS bitset. It is empty when the queue has no SUPPRESS. A
  SUPPRESS slot has its bit set and a `null` value, so reading it as a string fails at once, as
  `FOTY0013` does in the export.

A queue with no contexts (`{0}`) differs from a queue with one empty context (`{0,0}`). A source
queue always has at least one context: its contexts are the occurrences of its context element,
and when that element never occurs in the message the queue is one empty context `{0,0}`
(observed on PO 7.5 and CPI, #31, F-17). `{0}` stays legal as a function result. Queue
values are always strings. Typed values exist only inside a function call. Context operations
never copy characters:

- `removeContexts` shares the value array.
- `splitByValue` builds only a new `int[]`; since #55 the `emptyValue` mode also builds a new
  value array of references to the kept strings (no characters copied).
- `collapseContexts` and `useOneAsMany` copy references only.

The milestone-1 XDM is `XdmValue → Item → AtomicValue` (sealed, records). Its atomic types are
`xs:string`, `xs:boolean`, `xs:decimal` (`BigDecimal`), `xs:integer`, `xs:date`, `xs:time` and
`xs:dateTime`. Nodes, maps, arrays, function items and lazy sequences join the sealed hierarchy
with the XSLT front end.

Source queues are built by projection. The IR node `SourceQueue(field, context, origin)` means
`array:join(context ! [field ! string()])`. One StAX/JSON pass over the input fills a builder
for every `SourceQueue` of the mapping. Milestone 1 builds no tree. A constant is an atomic value: lowering gives
`FunCall(constant, [Literal])`, which the optimiser may fold to a `Literal` (#83). A queue meets the rest of XDM only through the IR nodes `QueueToArray` and
`ArrayToQueue`, where SUPPRESS becomes `map{'po:marker':'SUPPRESS'}`. The optimiser removes these
nodes when the callee accepts a queue directly. Milestone 1 has no such callee.

## Considered options

- **`List<List<Item>>` or typed `AtomicValue[]` slots**: rejected. They box every value and add
  a type switch on every read (FOUNDING §7.1).
- **A flat list with a context-change sentinel** (the shape of SAP's `ResultList`): rejected.
  Every function would have to split the queue again. If a queue UDF ever needs that shape, an
  adapter at the UDF boundary provides it.
- **SAP's `"_sUpPresSeD_"` string as SUPPRESS**: rejected. It collides with data and leaks into
  output.
- **`xs:untypedAtomic` source values**: rejected. XPath promotes them to `xs:double` in
  arithmetic, against ADR-0002.
- **No end sentinel, or a minimum of one context for every queue**: rejected. The first makes
  every operation special-case the last context. The second cannot express a function result
  with no context. PO does have the minimum for source queues (F-17), and the projection keeps it;
  the block itself still holds `{0}`.
- **Tiny tree or projected buffer in milestone 1**: deferred. No milestone-1 mapping needs them,
  and JMH (gate check 7) decides when they come.

## Consequences

- The block invariants, the IR type `Queue` and the string form of values bind the interpreter,
  the bytecode tier, the display queue, the UDF adapter and the exporter.
- `XSI_NIL` (#2 ambiguity 14) is not in milestone 1. Adding it later means a second bitset with
  the same invariants.
- Assumed readings until observed cases confirm them (ADR-0002):
  - A constant fed into a context or queue function is a queue of one context with one value.
    Contradicted for removeContexts by obs-lift-reuse lift-4 (F-51, open, #222).
  - ~~An absent parent gives zero contexts, not one empty context.~~ Observed otherwise (#31,
    F-17): a parent absent under one occurrence adds no context, as assumed (d1), but a context
    element absent from the whole message gives one empty context (d3). Replaced by the rule above.
- Since #51 the SUPPRESS bitset ends at its last SUPPRESS word, and bit k is set exactly when
  `values[k]` is null; a block without SUPPRESS has an empty bitset. `withContextStarts` shares the
  values and the bitset; a per-slot function shares the context starts.
