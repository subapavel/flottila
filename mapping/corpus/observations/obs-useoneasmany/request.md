# obs-useoneasmany

Notation, what to return and the ESR / CPI naming: `../README.md`. Source: `src-orders.xsd` (root
`Orders`). Target: `tgt-list.xsd` (root `Out`).

## Mapping uoam-1

| Box | Function | Inputs |
| --- | --- | --- |
| U1 | `useOneAsMany` | in1 = `Orders/Order/Id` (default context `Order`); in2 = `Orders/Order/Line/Sku` with context `Order`; in3 = `Orders/Order/Tag` with context `Order` |

Target wires: `Orders/Order` -> `Out/Item` (node to node); U1 -> `Out/Item/V`.

In1 is the value to repeat, in2 says how often (the number of values of in2 in the context of
each in1 value), in3 supplies the context changes (#2 §4, SAP figure).

No ADR fixes a reading for the error cases; the question of every input is "what does PO do",
and the assumed reading is "not stated, #2 §7.10". Return the output, or the error text exactly,
per input. Where PO fails, also say at which box.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `u1.xml` | Counts agree. A: Id A, Sku s1,s2,s3, Tag t1,t2,t3. B: Id B, Sku s4, Tag t4. Baseline: A is repeated three times in `Item` 1, B once in `Item` 2. | #2 §7.10 |
| `u2.xml` | in2 has more values than in3 (Sku 3, Tag 2). Error, truncation, or reuse of the last in3 value? | #2 §7.10 |
| `u3.xml` | in2 has fewer values than in3 (Sku 2, Tag 3). Error, or are the extra in3 values ignored? | #2 §7.10 |
| `u4.xml` | Empty contexts. A: Tags, no Line (in2 empty). B: Lines, no Tag (in3 empty). Third order: no `Id` (in1 empty), Tag t3, Sku s3. D: all present (baseline, shows whether an earlier failure is per order or per run). Output or error per order; if the run fails on A, drop A and rerun until each case has been seen, and say so. | #2 §7.10, §7.6 |

<!-- spec2 -->

# Spec 2 cases (#61)

Spec 2 (#56, #51, #61): `useOneAsMany` with a collapsed context, a different context count, SUPPRESS and the error conditions.
One mapping per section below; rebuild it and run each listed input. Every case id is a directory under `corpus/cases/`. Return the output (or the exact error text) per input, as above; one run of an input answers every case that names it.

## Mapping spec2-use-one-as-many-collapsed

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Id` (default context) |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| S3 | source | `Orders/Order/Line/Sku` (default context) |
| U1 | `useOneAsMany` | in1 = S1, in2 = S2, in3 = S3 |
| C1 | `collapseContexts` | in1 = U1 |

Target wires: C1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/useOneAsMany/002` | `useOneAsMany-002.xml` | The result's contexts are input 3's. Sku in its default context Line is n1 \| (empty) \| n2 \| n3, as o1's second Line has no Sku. Sku in context Order counts two values for external and one for internal, so the repeated values external, external, internal fill input 3's contexts in order: external \| (empty) \| external \| internal. collapseContexts shows the first value of each, "" for the empty one: All is external, "", external, internal. With input 2's contexts All would be external, internal. To observe in PO. |

## Mapping spec2-use-one-as-many-root-value

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Id` with context `Orders` |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| S3 | source | `Orders/Order/Line/Sku` (default context) |
| U1 | `useOneAsMany` | in1 = S1, in2 = S2, in3 = S3 |
| R1 | `removeContexts` | in1 = U1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/useOneAsMany/003` | `u1.xml` | Inputs with different context counts, over obs-useoneasmany u1.xml. Id in context Orders is one context A, B, while Sku in context Order has two, s1,s2,s3 \| s4, and Sku in context Line four. The i-th value of input 1 is repeated once per value of input 2's i-th context, so removeContexts gives All A, A, A, B. If input 1 had to have as many contexts as input 2, this would be an error. To observe in PO: obs-useoneasmany does not draw this wiring yet. |

## Mapping spec2-suppress-use-one-as-many

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| S3 | source | `Orders/Order/Line/Sku` with context `Orders` |
| U1 | `useOneAsMany` | in1 = C1, in2 = S2, in3 = S3 |

Target wires: U1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/useOneAsMany/005` | `useOneAsMany-005.xml` | createIf over Tag in context Orders gives SUPPRESS, ""; Sku in context Order has two slots, then one. Value 1, SUPPRESS, is repeated twice and value 2 once, in the one context of Sku in context Orders: SUPPRESS, SUPPRESS, "", so one empty All. SUPPRESS repeated as "" gives three. |

## Mapping spec3-suppress-use-one-as-many-occurrences

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Id` with context `Orders` |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| C1 | `createIf` | in1 = S2 |
| S3 | source | `Orders/Order/Line/Sku` with context `Orders` |
| U1 | `useOneAsMany` | in1 = S1, in2 = C1, in3 = S3 |

Target wires: U1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/useOneAsMany/009` | `useOneAsMany-009.xml` | SUPPRESS in the second input: Id is A, B; createIf over Sku in context Order gives "", SUPPRESS \| ""; Sku in context Orders has three slots. The SUPPRESS slot counts as an occurrence, so A is repeated twice and B once: All A, A, B. SUPPRESS not counted gives two occurrences for three slots, an error at U1; SUPPRESS carried into the result gives All A, B. |

## Mapping spec2-use-one-as-many

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Id` (default context) |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| S3 | source | `Orders/Order/Tag` (default context) |
| U1 | `useOneAsMany` | in1 = S1, in2 = S2, in3 = S3 |

Target wires: U1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/useOneAsMany/006` | `u2.xml` | obs-useoneasmany uoam-1 on u2.xml: Sku in context Order has 3 slots, Tag 2, so the third input has fewer slots than the second: po:USEONEASMANY at U1. Assumed error: `po:USEONEASMANY` at `U1`. |
| `fn/useOneAsMany/007` | `u3.xml` | obs-useoneasmany uoam-1 on u3.xml: Sku in context Order has 2 slots, Tag 3, so the third input has more slots than the second: po:USEONEASMANY at U1. Assumed error: `po:USEONEASMANY` at `U1`. |
| `fn/useOneAsMany/008` | `u4.xml` | obs-useoneasmany uoam-1 on u4.xml: the third Order has no Id, so Id has 3 values for the 4 contexts of Sku in context Order: po:USEONEASMANY at U1, for the run, not per order. Assumed error: `po:USEONEASMANY` at `U1`. |
