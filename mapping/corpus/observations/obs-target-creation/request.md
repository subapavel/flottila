# obs-target-creation

Notation, what to return and the ESR / CPI naming: `../README.md`. Structures are in
`../../structures/observations/`. Four small mappings. Source of all of them: `src-orders.xsd`
(root `Orders`).

## Mapping create-a (target `tgt-create.xsd`, root `Create`)

`Row` is 1..2, `Val` is 0..1.

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | `Constant{keep}` | none |
| A2 | `equalsS` | in1 = `Orders/Order/Line/Sku` with context `Orders`; in2 = A1 |
| A3 | `createIf` | in1 = A2 |

Target wires: A3 -> `Create/Row`; `Orders/Order/Line/Qty` (default context `Line`) -> `Create/Row/Val`.
Nothing else is mapped.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `a1.xml` | Lines skip,keep,keep,keep: `Row` queue is `SUPPRESS,"","",""`, `maxOccurs` 2. Does SUPPRESS count toward `maxOccurs`, and do the truncated slots keep their numbers? Assumed output: two `Row`, `Val` = 2 and 3. If SUPPRESS counted: one `Row` (Val 2). If slots were renumbered: `Val` 1 and 2. | ADR-0006 bullet 3; #2 §7.2 |
| `a2.xml` | Both lines skip: `Row` queue is `SUPPRESS,SUPPRESS`, `minOccurs` 1. Does SUPPRESS count toward `minOccurs`? Assumed: yes, it does not count, so the mapping fails with a `minOccurs` shortfall. Record the error text, or the output if none. | ADR-0006 bullet 3; #2 §7.2 |
| `a3.xml` | No line at all: `Row` queue has no value. Same question as a2 for zero values; compare the two texts. | ADR-0006 bullet 3; #2 §7.2 |

## Mapping create-b (target `tgt-gen.xsd`, root `Gens`)

`Grp` is 0..unbounded, `Gen` is 2..3, `V` is 0..1.

| Box | Function | Inputs |
| --- | --- | --- |
| B1 | `Constant{a}` | none |
| B2 | `Constant{b}` | none |
| B3 | `concat` | in1 = B1, in2 = B2 |

Target wires: `Orders/Order` -> `Gens/Grp` (node to node); B3 -> `Gens/Grp/Gen`;
`Orders/Order/Line/Qty` (default context `Line`) -> `Gens/Grp/Gen/V`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `b1.xml` | Two orders with two lines each (Qty 1,2 and 3,4). `Gen` is generated from constants only; how many copies per `Grp` (assumed: `minOccurs` = 2, `maxOccurs` 3 ignored)? Which `Qty` lands in which `Gen`? Assumed: `Grp` 1 gets 1,2 and `Grp` 2 gets 3,4 (child number `(p - 1) * copies + j`). | ADR-0006 bullet 4; #2 §7.2 |
| `b2.xml` | Orders with 3 lines (Qty 1,2,3) and 1 line (Qty 4). Numbering is by position, not by order: assumed `Grp` 1 gets 1,2 (3 is lost) and `Grp` 2 gets 3,4. If the lines were matched per order, `Grp` 2 would get 4 only. | ADR-0006 bullet 4 |

## Mapping create-c (target `tgt-unmapped.xsd`, root `Unmapped`)

`Head` is 1..1, `A` is 0..1, `Req` is 1..1.

Target wires: `Orders/Order/Id` with context `Orders` -> `Unmapped/Head/A`. `Req` stays unmapped.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `c1.xml` | An unmapped leaf with `minOccurs` 1: does the editor report a design-time error (record the message and where it shows: save, activation, test)? What does a run produce (an empty `Req`, no `Req`, an error)? Assumed: design-time error. | ADR-0006 bullet 5 |

## Mapping create-d (target `tgt-list.xsd`, root `Out`)

| Box | Function | Inputs |
| --- | --- | --- |
| D1 | `mapWithDefault{default=D}` | in1 = `Orders/Order/Memo/Text` (default context `Memo`) |

Target wires: `Orders/Order` -> `Out/Item` (node to node); D1 -> `Out/Item/V`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `d1.xml` | Orders 1 and 3 have `Memo/Text` (a, c), order 2 has no `Memo`. Does the absent parent give zero contexts (assumed: `V` queue has the contexts `a`, `c`, so `Item` 1 gets a, `Item` 2 gets c, `Item` 3 gets none) or an empty context for order 2 (then `D` appears in `Item` 2)? Also what the child queue does when it has fewer contexts than the parent has occurrences. Display queue of D1 helps. | ADR-0004 bullet 2; #2 §7.2 |
| `d2.xml` | Order 2 has `<Memo/>` with no `Text`. An empty context for a present parent: assumed `D` in `Item` 2. Compare with d1. | ADR-0004 bullet 2; #2 §7.6 |
| `d3.xml` | No order has a `Memo`. Assumed zero contexts: no `V` at all, not `D` in each `Item`. | ADR-0004 bullet 2 |

<!-- spec2 -->

# Spec 2 cases (#61)

Spec 2 (#52, #61): target fields at depth, maxOccurs and minOccurs with SUPPRESS and a missing context in a child queue.
One mapping per section below; rebuild it and run each listed input. Every case id is a directory under `corpus/cases/`. Return the output (or the exact error text) per input, as above; one run of an input answers every case that names it.

## Mapping spec2-create-if-row

Source `src-orders.xsd` (root `Orders`), target `tgt-create.xsd` (root `Create`).

Boxes for target field `Create/Row`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| C1 | `createIf` | in1 = S1 |

Boxes for target field `Create/Row/Val`:

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Qty` (default context) |

Target wires: C1 -> `Create/Row`; S2 -> `Create/Row/Val`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `target/max/002` | `max-002.xml` | obs-target-creation a1 with createIf instead of equalsS (spec 3): Sku no, 1, 1, 1 give the Row queue SUPPRESS, "", "", "" and Qty 1 \| 2 \| 3 \| 4 the Val contexts. SUPPRESS does not count toward maxOccurs 2 and keeps slot 1, so Rows 2 and 3 are created, Val 2 and 3, and slot 4 is truncated. If SUPPRESS counted: one Row, Val 2. If slots were renumbered: Val 1 and 2. |
| `target/minOccurs/002` | `a2.xml` | Both Skus are skip, so createIf gives SUPPRESS, SUPPRESS. SUPPRESS does not count toward minOccurs, so Row (1..2) has no created occurrence: po:MINOCCURS at T1. If SUPPRESS counted, the run would write the root without a Row. Assumed error: `po:MINOCCURS` at `T1`. |

## Mapping spec2-nested-grp-row-val

Source `src-orders.xsd` (root `Orders`), target `tgt-nested.xsd` (root `Doc`).

Boxes for target field `Doc/Grp`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Id` with context `Orders` |

Boxes for target field `Doc/Grp/Row`:

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |

Boxes for target field `Doc/Grp/Row/Val`:

| Box | Function | Inputs |
| --- | --- | --- |
| S3 | source | `Orders/Order/Line/Qty` (default context) |

Target wires: S1 -> `Doc/Grp`; S2 -> `Doc/Grp/Row`; S3 -> `Doc/Grp/Row/Val`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `target/max/003` | `max-003.xml` | Row (1..2) per Sku in context Order: a, b, c \| d, slots 1, 2, 3 \| 4. Grp 1 creates Rows 1 and 2 (Val 1, 2); slot 3 is truncated but keeps its number, so Grp 2's Row is slot 4 and reads Qty context 4: Val 4. Renumbering after the truncation would give Val 3. |

## Mapping spec2-nested-grp-row-remove-contexts

Source `src-orders.xsd` (root `Orders`), target `tgt-nested.xsd` (root `Doc`).

Boxes for target field `Doc/Grp`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Id` with context `Orders` |

Boxes for target field `Doc/Grp/Row`:

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` (default context) |
| R1 | `removeContexts` | in1 = S2 |

Target wires: S1 -> `Doc/Grp`; R1 -> `Doc/Grp/Row`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `target/minOccurs/004` | `minOccurs-004.xml` | Grp per Id in context Orders: o1, o2. Row (1..2) per removeContexts(Sku): one context a, b. Grp 1 gets Rows a and b; Grp 2 reads context 2, which the queue lacks and which counts as empty, so the run raises po:MINOCCURS at T2. Skipping a missing context would write a second Grp without a Row. Assumed error: `po:MINOCCURS` at `T2`. |

## Mapping spec2-create-if-item

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/Item`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| C1 | `createIf` | in1 = S1 |

Boxes for target field `Out/Item/V`:

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Qty` (default context) |

Target wires: C1 -> `Out/Item`; S2 -> `Out/Item/V`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `target/suppress/001` | `suppress-001.xml` | Item per createIf(Sku) in context Orders: "", SUPPRESS, "" for Sku 1, no, true. V per Qty in its default context Line: 10 \| 20 \| 30. The SUPPRESS slot 2 creates no Item but consumes Qty context 2, so the second Item reads context 3: V 10, then V 30. Renumbering the created slots would give V 10, then V 20. |
