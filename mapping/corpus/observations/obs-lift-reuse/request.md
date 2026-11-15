# obs-lift-reuse

Notation, what to return and the ESR / CPI naming: `../README.md`. Source of all mappings:
`src-orders.xsd` (root `Orders`). Target of all mappings: `tgt-list.xsd` (root `Out`). In every
mapping `Orders/Order` -> `Out/Item` (node to node), so `Item` *i* is order *i* and the queue
of `V` (or `W`) belongs to it by context.

## Mapping lift-1: reuse and empty contexts

| Box | Function | Inputs |
| --- | --- | --- |
| L1 | `concat` | in1 = `Orders/Order/Tag` (default context `Order`); in2 = `Orders/Order/Line/Sku` with context `Order` |

Target wires: L1 -> `Out/Item/V`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `l1.xml` | Five orders; read `V` per `Item`. o1: Tag t1,t2 and Sku s1 (last value reused inside the context): assumed `t1s1`, `t2s1`. o2: no Tag, Sku s2,s3 (one input has an empty context): assumed no `V`. o3: Tag t3, no Line (the other input empty): assumed no `V`. o4: Tag t4,t5,t6 and Sku s4,s5: assumed `t4s4`, `t5s5`, `t6s5`. o5: `<Tag/>` (a value that is the empty string) and Sku s6: assumed `s6`, not an empty context. | ADR-0006 bullet 1; #2 §7.3 |

## Mapping lift-2: SUPPRESS in a Lift input

Boxes of the X chain (drawn twice, as X1 and X2; no box is shared):

| Box | Function | Inputs |
| --- | --- | --- |
| C1 | `Constant{0}` | none |
| G1 | `greater` | in1 = `Orders/Order/Line/Qty` with context `Order`; in2 = C1 |
| X1 | `ifWithoutElse`, parameter Else = SUPPRESS (the "Keep SUPPRESS Values" choice; record the label PO 7.5 shows) | in1 = G1; in2 = `Orders/Order/Line/Qty` with context `Order` |
| K1 | `concat` | in1 = `Orders/Order/Line/Sku` with context `Order`; in2 = X1 |
| K2 | `concat` | in1 = K1; in2 = `Orders/Order/Tag` (default context `Order`) |
| C2 | `Constant{0}` | none |
| G2 | `greater` | in1 = `Orders/Order/Line/Qty` with context `Order`; in2 = C2 |
| X2 | `ifWithoutElse`, Else = SUPPRESS | in1 = G2; in2 = `Orders/Order/Line/Sku` with context `Order` |
| U1 | `toUpperCase` | in1 = X2 |

Target wires: K2 -> `Out/Item/V`; U1 -> `Out/Item/W`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `l2.xml` | One order, lines (a,5), (b,0), (c,7), tags t1,t2,t3. X1 is `5,SUPPRESS,7`. `K1` is `a5,SUPPRESS,c7`; K2 gives SUPPRESS in slot 2 and consumes `t2`. Assumed `V`: `a5t1`, `c7t3` (if SUPPRESS did not consume the other input: `a5t1`, `c7t2`). `W` assumed `A`, `C` (a single-value function passes SUPPRESS on). Record the display queues of X1, K1, K2, U1. | ADR-0006 bullet 2; #2 §7.4 |

## Mapping lift-3: SUPPRESS as the last value read

Boxes: C1, G1, X1 as in lift-2; L3 = `concat` with in1 = X1, in2 = `Orders/Order/Tag`
(default context `Order`). Target wire: L3 -> `Out/Item/V`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `l3.xml` | o1: line (a,0), tags t1,t2,t3: X1 is a single SUPPRESS, is it reused for each tag (assumed: three SUPPRESS slots, no `V`)? o2: lines (a,5), (b,0), tags t1,t2,t3: X1 is `5,SUPPRESS`; assumed `5t1` only, the last value read (SUPPRESS) is reused for `t3` and gives SUPPRESS. If the reuse skipped SUPPRESS: `5t3` or similar; record it. | #2 §7.3; ADR-0006 bullet 2 |

## Mapping lift-4: constants into context and queue functions

| Box | Function | Inputs |
| --- | --- | --- |
| K1 | `Constant{k}` | none |
| N1 | `count` | in1 = K1 |
| K2 | `Constant{k}` | none |
| R1 | `removeContexts` | in1 = K2 |

Target wires: N1 -> `Out/Item/V`; R1 -> `Out/Item/W`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `l4.xml` | Three orders. A constant is one context with one value (assumed): `V` is `1` in `Item` 1 and `Item` 2, 3 get none; `W` is `k` in `Item` 1 only. If PO repeated the constant per parent context, every `Item` has `V` = 1 and `W` = k. Display queues of N1, R1. | ADR-0004 bullet 1 |

<!-- spec2 -->

# Spec 2 cases (#61)

Spec 2 (#51, #61): `createIf` truth values and nesting, and `replaceValue` over SUPPRESS and `<Tag/>`.
One mapping per section below; rebuild it and run each listed input. Every case id is a directory under `corpus/cases/`. Return the output (or the exact error text) per input, as above; one run of an input answers every case that names it.

## Mapping spec2-create-if

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |

Target wires: C1 -> `Out/All`. Nothing else is mapped.

Superseded by `obs-target-creation-2` (#134, F-37): PO's `createIf` ends the run on any value but `true` / `false` / `1` / `0`, so this input fails before its question. Skip this run; #134 asks the question again with inputs PO accepts.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/createIf/002` | `createIf-002.xml` | One context of six conditions: TRUE, 1, yes, 0, " true" and "". Only TRUE and 1 are true, so two empty All. A case-sensitive rule gives one, a rule that trims or takes yes gives three. |

## Mapping spec2-create-if-twice

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| C2 | `createIf` | in1 = C1 |

Target wires: C2 -> `Out/All`. Nothing else is mapped.

Superseded by `obs-target-creation-2` (#134, F-37): PO's `createIf` ends the run on any value but `true` / `false` / `1` / `0`, so this input fails before its question. Skip this run; #134 asks the question again with inputs PO accepts.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/createIf/003` | `createIf-003.xml` | createIf(createIf(Tag)) with Tag true, false, 1 in context Orders. The inner createIf gives "", SUPPRESS, ""; the outer one takes neither "" nor SUPPRESS as true, so every slot is SUPPRESS and the root is written empty. Taking "" as true would give two All. |

## Mapping spec2-replace-value-create-if

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| V1 | `replaceValue{replacement=yes}` | in1 = C1 |

Target wires: V1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/replaceValue/002` | `replaceValue-002.xml` | replaceValue(createIf(Tag), yes) with Tag true, false, true in context Orders. createIf gives "", SUPPRESS, ""; replaceValue replaces each value with yes and passes the SUPPRESS on, so All is yes, yes. Replacing the SUPPRESS too would give a third yes. |

## Mapping spec2-replace-value

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| V1 | `replaceValue{replacement=O'Brien & <Co>}` | in1 = S1 |

Target wires: V1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/replaceValue/004` | `replaceValue-004.xml` | Sku in context Orders: a, then <Sku/>, the value "", then a Line without Sku, which gives no slot. replaceValue replaces both values, the empty one included, so two All. Taking <Sku/> for no value would give one. |
