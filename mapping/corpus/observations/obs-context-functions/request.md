# obs-context-functions

Notation, what to return and the ESR / CPI naming: `../README.md`. Source of all mappings:
`src-orders.xsd` (root `Orders`). Target of all mappings: `tgt-list.xsd` (root `Out`). Where a
mapping says `Orders/Order` -> `Out/Item` (node to node), `Item` *i* is order *i* and the queue of
`V` belongs to it by context. Where a target is `Out/All`, nothing else is mapped and the whole
result lands under the single root context.

## Mapping ctx-1: `removeContexts` and empty contexts

| Box | Function | Inputs |
| --- | --- | --- |
| R1 | `removeContexts` | in1 = `Orders/Order/Line/Sku` (default context `Line`) |

Target wire: R1 -> `Out/All`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `f1.xml` | o1: Sku a. o2: a `Line` with no `Sku` (empty context). o3: no `Line` (empty context at order level). o4: `<Sku/>` (the empty string as a value). o5: Sku b. Does `removeContexts` drop empty contexts? Assumed: `All` = `a`, empty (from o4), `b`. If empty contexts were kept as empty strings there are more empty `All`. | #2 §7.6 |

## Mapping ctx-2: `mapWithDefault` on a SUPPRESS-only context

| Box | Function | Inputs |
| --- | --- | --- |
| C1 | `Constant{0}` | none |
| G1 | `greater` | in1 = `Orders/Order/Line/Qty` with context `Order`; in2 = C1 |
| X1 | `ifWithoutElse`, Else = SUPPRESS | in1 = G1; in2 = `Orders/Order/Line/Qty` with context `Order` |
| M1 | `mapWithDefault{default=D}` | in1 = X1 |

Target wires: `Orders/Order` -> `Out/Item`; M1 -> `Out/Item/V`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `f2.xml` | o1: Qty 5 (X1 = `5`). o2: Qty 0 (X1 = a context holding only SUPPRESS). o3: no line (a truly empty context). o4: Qty 0, 3 (X1 = `SUPPRESS,3`). Is a SUPPRESS-only context empty for `mapWithDefault`? No assumed result is fixed: either `V` = `D` in `Item` 2 (counts as empty) or none (does not). Expected for o1 `5`, o3 `D` (SAP), o4 `3`. | #2 §7.6 |

## Mapping ctx-3: `SplitByValue` modes (three variants of one mapping)

| Box | Function | Inputs |
| --- | --- | --- |
| R1 | `removeContexts` | in1 = `Orders/Order/Tag` |
| S1 | `SplitByValue{mode}` | in1 = R1 |
| CC | `collapseContexts` | in1 = S1 |
| R2 | `removeContexts` | in1 = `Orders/Order/Tag` |
| S2 | `SplitByValue{mode}` | in1 = R2 |

Target wires: CC -> `Out/Item`; S2 -> `Out/Item/V`. Variants: `ctx-3-each` (mode Each Value),
`ctx-3-change` (mode Value Change), `ctx-3-empty` (the mode that splits at a tag without a
value). The mode labels are not confirmed on SAP pages (#2 §4): list the labels PO 7.5 and CPI
offer, run every one that exists, and name the variant after its label.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `f3.xml` | One order, tags `a`, `a`, `<Tag/>`, `b`, `<Tag/>`. Per mode: how many `Item`, which values in each `V`? In particular, where does the "tag without a value" mode cut: after each `<Tag/>`, and does `<Tag/>` count as a tag without a value? No assumed result; this fixes the meaning. | #2 §7.6 |

## Mapping ctx-4: `exists`

| Box | Function | Inputs |
| --- | --- | --- |
| E1 | `exists` | in1 = `Orders/Order/Line/Sku` with context `Order` |

Target wires: `Orders/Order` -> `Out/Item`; E1 -> `Out/Item/V`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `f4.xml` | o1: two lines with Sku a, b. o2: one line without `Sku`. o3: no line. o4: `<Sku/>`. Per value or per context? Candidate: o1 `true`,`true`; o2 `false`; o3 `false`; o4 `true` (per value, false for an empty context, as in the `mapWithDefault` equivalence). Per context would give one `true` for o1. Record the exact output. | #2 §7.7 |

## Mapping ctx-5: `collapseContexts`, first or last value

| Box | Function | Inputs |
| --- | --- | --- |
| CC | `collapseContexts` | in1 = `Orders/Order/Line/Qty` with context `Order` |

Target wires: `Orders/Order` -> `Out/Item`; CC -> `Out/Item/V`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `f5.xml` | The SAP figure queue `3,2 \| 10,11,43,2 \| 7` plus an order without lines. The text says the first value (`3`, `10`, `7`), the figure shows the last (`2`, `2`, `7`). Assumed: first value. o4 (empty context): assumed empty string (SAP: empty contexts are replaced by empty strings). | #2 §7.16, §7.6 |

## Mapping ctx-6a: `removeContexts` and SUPPRESS

| Box | Function | Inputs |
| --- | --- | --- |
| C1 | `Constant{0}` | none |
| G1 | `greater` | in1 = `Orders/Order/Line/Qty` (default context `Line`); in2 = C1 |
| X1 | `ifWithoutElse`, Else = SUPPRESS | in1 = G1; in2 = `Orders/Order/Line/Qty` (default context `Line`) |
| R1 | `removeContexts` | in1 = X1 |
| R2 | `removeContexts` | in1 = `Orders/Order/Tag` |
| K1 | `concat` | in1 = R1; in2 = R2 |

Target wire: K1 -> `Out/All`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `f6.xml` | Lines (a,5), (b,0), (c,7), tags t1,t2,t3. X1 is `5 \| SUPPRESS \| 7`. Does `removeContexts` keep SUPPRESS? Kept: `All` = `5t1`, `7t3`. Dropped: `5t1`, `7t2`, `7t3`. No assumed result (community sources conflict). | #2 §7.4 |

## Mapping ctx-6b: `collapseContexts` and SUPPRESS

Boxes C2, G2, X2 as C1, G1, X1 above, plus CC = `collapseContexts` with in1 = X2.
Target wire: CC -> `Out/All`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `f6.xml` | Same input. X2 is `5 \| SUPPRESS \| 7` (three contexts). SAP says SUPPRESS becomes an empty string: `All` = `5`, empty, `7`. If SUPPRESS were dropped: `5`, `7`. | #2 §7.4 |

<!-- spec2 -->

# Spec 2 cases (#61)

Spec 2 (#55, #56, #57, #58, #59, #51, #61): `splitByValue` modes, `collapseContexts`, `formatByExample`, `exists`, `mapWithDefault`, `removeContexts`, `sort` and `sortByKey`, on empty and SUPPRESS contexts.
One mapping per section below; rebuild it and run each listed input. Every case id is a directory under `corpus/cases/`. Return the output (or the exact error text) per input, as above; one run of an input answers every case that names it.

## Mapping spec2-collapse-contexts

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Qty` with context `Order` |
| C1 | `collapseContexts` | in1 = S1 |

Target wires: C1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/collapseContexts/001` | `f5.xml` | The input of obs-context-functions ctx-5 (f5.xml), the queue of the SAP figure. Qty in context Order is 3,2 \| 10,11,43,2 \| 7 \| (empty), as o4 has no Line. collapseContexts keeps the first value of each context, gives the empty context "", and puts them all in one context, so All is 3, 10, 7, "". The last value would give 2, 2, 7, ""; without collapseContexts the root's slot would read 3, 2 alone. To observe in PO: first or last. |
| `fn/collapseContexts/002` | `collapseContexts-002.xml` | Empty contexts keep their place. o1 has no Line and o2's one Line has no Qty, so Qty in context Order is (empty) \| (empty) \| 5,6. collapseContexts replaces each empty context with "" ahead of o3's first value: All is "", "", 5. If empty contexts were dropped, All would be 5 alone; if the last value were kept, 6. To observe in PO, with obs-context-functions ctx-5 o4. |
| `fn/collapseContexts/003` | `collapseContexts-003.xml` | An empty input: <Orders/> has no Order, so Qty in context Order is one empty context (F-17). collapseContexts replaces it with "": one context holding "", so the root's one slot creates one empty All. Before F-17 the queue had no context at all and the root was written without an All. |

## Mapping spec2-exists

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Order` |
| E1 | `exists` | in1 = S1 |
| R1 | `removeContexts` | in1 = E1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/exists/003` | `f4.xml` | The input of obs-context-functions ctx-4 (f4.xml); to observe in PO: per value or per context. Sku in context Order is a, b \| (empty) \| (empty) \| "": o2's one Line has no Sku, o3 has no Line, o4's <Sku/> is the value "". exists gives one value per context, true, false, false, true, and removeContexts puts them under the root's one slot. Per value would give true, true, false, false, true; taking "" for no value would make the last false. |

## Mapping spec2-exists-create-if

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` (default context) |
| C1 | `createIf` | in1 = S1 |
| E1 | `exists` | in1 = C1 |
| R1 | `removeContexts` | in1 = E1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/exists/004` | `exists-004.xml` | To observe in PO: exists over a SUPPRESS-only context. Tag in its default context Order is false \| false, true \| (none) \| true, so createIf gives SUPPRESS \| SUPPRESS, "" \| (empty) \| "". exists gives false, true, false, true, and removeContexts puts them under the root's one slot. Counting SUPPRESS as a value would make the first true. |

## Mapping spec2-format-by-example

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` (default context) |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| F1 | `formatByExample` | in1 = S1, in2 = S2 |

Target wires: F1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/formatByExample/004` | `collapseContexts-003.xml` | An empty input: <Orders/> has no Order, so both queues have no context at all and no value. The counts agree at zero, and formatByExample gives the example's zero contexts, so the root's one slot reads no context and the root is written empty. |

## Mapping spec2-suppress-format-by-example

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| F1 | `formatByExample` | in1 = C1, in2 = S2 |

Target wires: F1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/formatByExample/005` | `formatByExample-005.xml` | createIf over Tag in context Orders gives SUPPRESS, "", ""; Sku in context Order cuts after two slots. The first context, SUPPRESS, "", is the one the root's slot reads: one empty All. A SUPPRESS written as a value, or no cut, gives two. |

## Mapping spec2-map-with-default-create-if

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` (default context) |
| C1 | `createIf` | in1 = S1 |
| M1 | `mapWithDefault{default=D}` | in1 = C1 |
| R1 | `removeContexts` | in1 = M1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/mapWithDefault/002` | `exists-004.xml` | To observe in PO (obs-context-functions ctx-2 o2, o4). Tag in its default context Order is false \| false, true \| (none) \| true, so createIf gives SUPPRESS \| SUPPRESS, "" \| (empty) \| "". mapWithDefault takes the SUPPRESS-only context for empty and gives it one D, keeps SUPPRESS, "" (SUPPRESS next to a value), fills the empty context, keeps "": removeContexts gives D, SUPPRESS, "", D, "", and the root's slot skips the SUPPRESS: D, "", D, "". A SUPPRESS-only context kept would give "", D, ""; a D per SUPPRESS slot would give D, D, "", D, "". |

## Mapping spec2-map-with-default

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` (default context) |
| M1 | `mapWithDefault{default=Default}` | in1 = S1 |
| R1 | `removeContexts` | in1 = M1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/mapWithDefault/003` | `mapWithDefault-003.xml` | To observe in PO: <Tag/> under mapWithDefault. Tag in its default context Order is "" \| (empty) \| "", x: o1's <Tag/> is the value "", o2 has no Tag. mapWithDefault keeps both contexts with a value, the empty string included, and fills the empty one: "", Default, "", x. Taking "" for no value would give Default, Default, "", x. |

## Mapping spec2-map-with-default-remove-contexts

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` (default context) |
| R1 | `removeContexts` | in1 = S1 |
| M1 | `mapWithDefault{default=D}` | in1 = R1 |

Target wires: M1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/mapWithDefault/004` | `collapseContexts-003.xml` | To observe in PO: removeContexts over an empty input. <Orders/> has no Order and no Line, so Sku in its default context Line is one empty context (F-17). removeContexts keeps one empty context, which mapWithDefault fills with D, so the root's one slot creates one All D, as in fn/mapWithDefault/005 without removeContexts. Since F-17 the input is no longer zero contexts, so this case no longer tells removeContexts apart from a no-op; ContextFunctionsTest does, over {0}. |

## Mapping spec3-map-with-default-remove-contexts-item

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/Item/V`:

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` (default context) |
| R1 | `removeContexts` | in1 = S2 |
| M1 | `mapWithDefault{default=D}` | in1 = R1 |

Target wires: `Orders/Order/Line` with context `Orders` -> `Out/Item` (node to node); M1 ->
`Out/Item/V`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/mapWithDefault/010` | `mapWithDefault-010.xml` | fn/mapWithDefault/004 with two contexts: two Lines without Sku give Sku the empty contexts [] []. removeContexts gives one empty context, which mapWithDefault fills with D, so Item 1 gets V D and Item 2 no V. Without removeContexts both Items get V D; removeContexts giving no context leaves both without V. |

## Mapping spec2-map-with-default-line

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` (default context) |
| M1 | `mapWithDefault{default=D}` | in1 = S1 |

Target wires: M1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/mapWithDefault/005` | `collapseContexts-003.xml` | To observe in PO, with fn/mapWithDefault/004: mapWithDefault over an empty input. <Orders/> has no Line, so Sku in its default context Line is one empty context (F-17), and mapWithDefault fills it with D: the root's one slot creates one All D, as removeContexts first gives in fn/mapWithDefault/004. Before F-17 the queue had no context at all and the root was written empty. |

## Mapping spec2-remove-contexts

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` (default context) |
| R1 | `removeContexts` | in1 = S1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/removeContexts/002` | `f1.xml` | The input of obs-context-functions ctx-1 (f1.xml). Sku in context Line is a \| (empty) \| "" \| b: o2's Line has no Sku and gives an empty context, o3 has no Line and gives no context, o4's <Sku/> is the value "". removeContexts drops the empty context and keeps the empty string, so All is a, "", b. If empty contexts were kept as "", there would be a fourth All. |
| `fn/removeContexts/003` | `collapseContexts-003.xml` | An empty input: <Orders/> has no Order and no Line, so the Sku queue is one empty context (F-17). removeContexts gives one context and it holds no value, so the root's one slot creates no All and the root is written empty. |

## Mapping spec2-sort

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Tag` (default context) |
| S1 | `sort` | in1 = A1 |
| R1 | `removeContexts` | in1 = S1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sort/001` | `sort-001.xml` | The defaults, lexicographic and ascending, over digits, upper case, an underscore and lower case in order o1, and accented letters in o2. Java's String.compareTo orders by UTF-16 code unit: 10, 9, A, B, _, a, b and Z, e, f, é. A locale collation would put é beside e and a beside A. R1 joins the contexts S1 keeps, so All shows each context's order in turn. Observe in SAP PO (#33). |
| `fn/sort/002` | `sort-002.xml` | Lexicographic over a supplementary character (😀, U+1F600, UTF-16 D83D DE00) and two characters in U+E000-U+FFFF (ﬀ U+FB00, Ａ U+FF21). By UTF-16 code unit the high surrogate D83D comes first: z, 😀, ﬀ, Ａ. The XPath default codepoint collation would give z, ﬀ, Ａ, 😀, so the export's helper must not use it. R1 joins the contexts S1 keeps, so All shows each context's order in turn. Observe in SAP PO (#33). |
| `fn/sort/008` | `sort-008.xml` | Each context is sorted on its own: o1 has no Tag, o2 has c, a and o3 has b. S1 keeps the empty context and the boundaries, so All is a, c, b; one sort over all values would give a, b, c. R1 joins the contexts S1 keeps, so All shows each context's order in turn. Observe in SAP PO (#33). |

## Mapping spec2-sort-case-insensitive

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Tag` (default context) |
| S1 | `sort{comparison=caseInsensitive}` | in1 = A1 |
| R1 | `removeContexts` | in1 = S1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sort/003` | `sort-001.xml` | caseInsensitive over the input of fn/sort/001. compareToIgnoreCase compares toLowerCase(toUpperCase(c)), so _ (5F) comes before every letter, and the ties a, A and B, b stay in input order (a stable sort): 10, 9, _, a, A, B, b and e, f, Z, é. Lexicographic would give A, B, _, a, b. R1 joins the contexts S1 keeps, so All shows each context's order in turn. Observe in SAP PO (#33). |
| `fn/sort/004` | `sort-002.xml` | caseInsensitive over the input of fn/sort/002. compareToIgnoreCase compares by code point, not by UTF-16 code unit, so 😀 (U+1F600) comes after Ａ (folded to U+FF41): z, ﬀ, Ａ, 😀, against z, 😀, ﬀ, Ａ for lexicographic. ﬀ has no single-character upper case and stays U+FB00. R1 joins the contexts S1 keeps, so All shows each context's order in turn. Observe in SAP PO (#33). |
| `fn/sort/011` | `sort-011.xml` | caseInsensitive where Java's simple case mappings differ from XPath's full ones: ß has no single-character upper case and stays ß (U+00DF), after SS (folded to ss); İ (U+0130) folds to i, so İ, I and i are equal and keep their input order. Input İ, SS, h, ß, I, i gives h, İ, I, i, SS, ß; full mappings would make ß equal to SS and put İ last. R1 joins the contexts S1 keeps, so All shows each context's order in turn. Observe in SAP PO (#33). |

## Mapping spec2-sort-descending

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Tag` (default context) |
| S1 | `sort{order=descending}` | in1 = A1 |
| R1 | `removeContexts` | in1 = S1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sort/005` | `sort-001.xml` | Lexicographic and descending over the input of fn/sort/001: b, a, _, B, A, 9, 10 and é, f, e, Z, fn/sort/001 reversed, as no two values are equal. R1 joins the contexts S1 keeps, so All shows each context's order in turn. Observe in SAP PO (#33). |

## Mapping spec2-sort-numeric

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Tag` (default context) |
| S1 | `sort{comparison=numeric}` | in1 = A1 |
| R1 | `removeContexts` | in1 = S1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sort/006` | `sort-006.xml` | numeric, ascending: -1, .25, +0.5, 1.50, 1.5, " 2 ", 9, 10 and 1., 007, 7. 1.50 and 1.5 are equal and keep their input order, as do 007 and 7; " 2 " is 2, as the xs:decimal cast collapses whitespace; each value keeps its own spelling. Lexicographic would put 10 before 9. R1 joins the contexts S1 keeps, so All shows each context's order in turn. Observe in SAP PO (#33). |
| `fn/sort/014` | `sort-014.xml` | Tag 10, x, 9 sorted numerically: x is no number, so the run raises po:SORT at S1. Sorted as strings the same Tags give 10, 9, x. Assumed error: `po:SORT` at `S1`. |

## Mapping spec2-sort-numeric-descending

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Tag` (default context) |
| S1 | `sort{comparison=numeric, order=descending}` | in1 = A1 |
| R1 | `removeContexts` | in1 = S1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sort/007` | `sort-006.xml` | numeric, descending over the input of fn/sort/006: 10, 9, " 2 ", 1.50, 1.5, +0.5, .25, -1 and 007, 7, 1. Equal values keep their input order in descending order too (1.50 before 1.5, 007 before 7): a stable sort, not fn/sort/006 reversed. R1 joins the contexts S1 keeps, so All shows each context's order in turn. Observe in SAP PO (#33). |

## Mapping spec2-suppress-sort-format

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| R1 | `sort` | in1 = C1 |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| F1 | `formatByExample` | in1 = R1, in2 = S2 |

Target wires: F1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sort/012` | `sort-012.xml` | createIf over Tag in context Orders gives "", SUPPRESS, "", SUPPRESS. Ascending puts SUPPRESS first: SUPPRESS, SUPPRESS, "", "". formatByExample cuts after three slots, as Sku in context Order does, so the first context holds one "": one empty All. Unsorted or SUPPRESS last gives two. |

## Mapping spec2-suppress-sort-descending-format

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| R1 | `sort{order=descending}` | in1 = C1 |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| F1 | `formatByExample` | in1 = R1, in2 = S2 |

Target wires: F1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sort/013` | `sort-013.xml` | createIf over Tag in context Orders gives SUPPRESS, "", SUPPRESS, "". Descending puts SUPPRESS last: "", "", SUPPRESS, SUPPRESS. formatByExample cuts after three slots, so the first context holds two "": two empty All. Unsorted or SUPPRESS first gives one. |

## Mapping spec2-sort-by-key

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Line/Sku` with context `Order` |
| A2 | source | `Orders/Order/Line/Qty` with context `Order` |
| K1 | `sortByKey` | in1 = A1, in2 = A2 |
| R1 | `removeContexts` | in1 = K1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sortByKey/001` | `sortByKey-001.xml` | Defaults, lexicographic and ascending, keys Sku and values Qty, both in context Order. o1's keys b, a, C, 😀, Ａ sort by UTF-16 code unit to C, a, b, 😀, Ａ, so its values 1, 2, 3, 4, 5 become 3, 2, 1, 4, 5; o2's keys y, x give 7, 6. The codepoint collation would put 5 before 4. R1 joins the contexts K1 keeps, so All shows each order's values in turn. Observe in SAP PO (#33). |
| `fn/sortByKey/004` | `sortByKey-004.xml` | Equal keys keep the input order of their values (a stable sort): keys k, j, k with values 2, 3, 1 give 3, 2, 1, not 3, 1, 2. R1 joins the contexts K1 keeps, so All shows each order's values in turn. Observe in SAP PO (#33). |

## Mapping spec2-sort-by-key-case-insensitive

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Line/Sku` with context `Order` |
| A2 | source | `Orders/Order/Line/Qty` with context `Order` |
| K1 | `sortByKey{comparison=caseInsensitive}` | in1 = A1, in2 = A2 |
| R1 | `removeContexts` | in1 = K1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sortByKey/002` | `sortByKey-002.xml` | caseInsensitive keys B, a, b, A, Ａ, 😀 with values 1-6: the ties a, A and B, b keep their input order, and Ａ (U+FF41 folded) comes before 😀 (U+1F600) by code point, so the values are 2, 4, 1, 3, 5, 6. Lexicographic would give 4, 1, 2, 3, 6, 5. R1 joins the contexts K1 keeps, so All shows each order's values in turn. Observe in SAP PO (#33). |

## Mapping spec2-sort-by-key-numeric-descending

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Line/Sku` with context `Order` |
| A2 | source | `Orders/Order/Line/Qty` with context `Order` |
| K1 | `sortByKey{comparison=numeric, order=descending}` | in1 = A1, in2 = A2 |
| R1 | `removeContexts` | in1 = K1 |

Target wires: R1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sortByKey/003` | `sortByKey-003.xml` | numeric, descending keys 10, 9, 10.0, -2 with values a, b, c, d: 10 and 10.0 are equal and keep their input order, so the values are a, c, b, d; o2's one pair gives x. The values are no numbers and are not parsed. Lexicographic descending would give b, c, a, d. R1 joins the contexts K1 keeps, so All shows each order's values in turn. Observe in SAP PO (#33). |

## Mapping spec2-suppress-sort-by-key

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| S2 | source | `Orders/Order/Line/Sku` with context `Orders` |
| K1 | `sortByKey` | in1 = C1, in2 = S2 |

Target wires: K1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sortByKey/006` | `sortByKey-006.xml` | The keys, createIf over Tag in context Orders, are "", SUPPRESS, "", SUPPRESS; the values are s1 to s4. Ascending puts the SUPPRESS keys first, in input order: s2, s4, s1, s3. SUPPRESS last gives s1, s3, s2, s4. |

## Mapping spec2-split-by-value-each

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Tag` (default context) |
| R1 | `removeContexts` | in1 = A1 |
| S1 | `splitByValue{mode=eachValue}` | in1 = R1 |

Target wires: S1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/splitByValue/004` | `splitByValue-004.xml` | eachValue with the empty value first: <Tag/>, a gives "", a, and "" is a value like any other here, in a context of its own, so All is one empty All. If eachValue dropped "", All would be a. All under the root's one slot reads context 1 of S1's queue, so it shows where the first split falls. |
| `fn/splitByValue/005` | `collapseContexts-003.xml` | An empty input: <Orders/> has no Order, so the Tag queue has no context; removeContexts gives one empty context and S1 keeps it as one empty context, so the root's slot creates no All. |

## Mapping spec2-split-by-value-change

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Tag` (default context) |
| R1 | `removeContexts` | in1 = A1 |
| S1 | `splitByValue{mode=valueChange}` | in1 = R1 |

Target wires: S1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/splitByValue/006` | `f3.xml` | The input of obs-context-functions ctx-3 (f3.xml): Tag a, a, <Tag/>, b, <Tag/>. valueChange starts a context at the "" and at b, so context 1 is a, a and All is a, a. All under the root's one slot reads context 1 of S1's queue, so it shows where the first split falls. |
| `fn/splitByValue/007` | `splitByValue-007.xml` | valueChange over a queue of one value: Tag a gives the one context a, so All is a. |
| `fn/splitByValue/008` | `splitByValue-008.xml` | valueChange over one equal run across two orders: removeContexts gives a, a, a, and no value differs from the one before it, so nothing splits and All is a, a, a. eachValue would give one a (fn/splitByValue/003). |
| `fn/splitByValue/009` | `splitByValue-009.xml` | valueChange with the empty value first: <Tag/>, <Tag/>, a gives "", "", a. The two "" are equal and a differs, so context 1 is "", "" and All is two empty All. All under the root's one slot reads context 1 of S1's queue, so it shows where the first split falls. |
| `fn/splitByValue/010` | `splitByValue-010.xml` | valueChange compares values exactly: a, A differ in case, so A starts a context and All is a alone. A case-insensitive comparison would give a, A. All under the root's one slot reads context 1 of S1's queue, so it shows where the first split falls. |

## Mapping spec2-split-by-value-empty

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Tag` (default context) |
| R1 | `removeContexts` | in1 = A1 |
| S1 | `splitByValue{mode=emptyValue}` | in1 = R1 |

Target wires: S1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/splitByValue/011` | `f3.xml` | The input of obs-context-functions ctx-3 (f3.xml): Tag a, a, <Tag/>, b, <Tag/>. emptyValue ends context 1 at the first "" and drops it, so All is a, a. All under the root's one slot reads context 1 of S1's queue, so it shows where the first split falls. |
| `fn/splitByValue/012` | `splitByValue-007.xml` | emptyValue over a queue of one value: Tag a has no "", so the one context a stays and All is a. |
| `fn/splitByValue/013` | `splitByValue-013.xml` | emptyValue over a, b, which holds no "": nothing splits, and All is a, b. eachValue would give a alone. |
| `fn/splitByValue/014` | `splitByValue-004.xml` | emptyValue with the empty value first: <Tag/>, a. The "" ends context 1 before any value and is dropped, so context 1 is empty and the root's slot creates no All. If a leading "" left no empty context, All would be a. |

## Mapping spec2-split-by-value-empty-flat

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | source | `Orders/Order/Tag` (default context) |
| R1 | `removeContexts` | in1 = A1 |
| S1 | `splitByValue{mode=emptyValue}` | in1 = R1 |
| R2 | `removeContexts` | in1 = S1 |

Target wires: R2 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/splitByValue/015` | `f3.xml` | The input of f3.xml through removeContexts(splitByValue(emptyValue)): R2 puts every value S1 keeps into one context, so All shows which values were dropped. Both "", one in the middle and one at the end, are dropped: All is a, a, b. If S1 kept them there would be two empty All. |
| `fn/splitByValue/016` | `splitByValue-016.xml` | "" at both ends and two in a row, across two orders: <Tag/>, a, <Tag/> \| <Tag/>, b, <Tag/>. removeContexts R1 gives "", a, "", "", b, ""; S1 drops all four "" and R2 joins what is left, so All is a, b. |

## Mapping spec2-suppress-split-each-collapse

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| P1 | `splitByValue{mode=eachValue}` | in1 = C1 |
| K1 | `collapseContexts` | in1 = P1 |

Target wires: K1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/splitByValue/019` | `splitByValue-019.xml` | Tag in context Orders is true, false \| true, false, false, so createIf gives "", SUPPRESS, "", SUPPRESS, SUPPRESS. eachValue puts each slot, SUPPRESS included, in a context of its own, and collapseContexts gives one "" per context: five empty All. Dropping SUPPRESS gives two. |

## Mapping spec3-suppress-split-each-item

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/Item/V`:

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S2 |
| P1 | `splitByValue{mode=eachValue}` | in1 = C1 |

Target wires: `Orders/Order/Tag` with context `Orders` -> `Out/Item`; P1 -> `Out/Item/V`.
Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/splitByValue/022` | `splitByValue-019.xml` | fn/splitByValue/019 without collapseContexts, so SUPPRESS shows: one Item per Tag, five. createIf gives "", SUPPRESS, "", SUPPRESS, SUPPRESS, and eachValue puts each slot in a context of its own, so Items 1 and 3 get an empty V and Items 2, 4 and 5 none. SUPPRESS taken for "" gives a V in every Item; dropping SUPPRESS gives a V in Items 1 and 2. |

## Mapping spec2-suppress-split-change-collapse

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| P1 | `splitByValue{mode=valueChange}` | in1 = C1 |
| K1 | `collapseContexts` | in1 = P1 |

Target wires: K1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/splitByValue/020` | `splitByValue-020.xml` | createIf gives "", SUPPRESS, SUPPRESS, "", "" in one context. valueChange cuts where SUPPRESS meets "": "" \| SUPPRESS, SUPPRESS \| "", "", and collapseContexts gives three empty All. SUPPRESS equal to "" gives one; SUPPRESS unequal to SUPPRESS gives four. |

## Mapping spec2-suppress-split-empty-collapse

Source `src-orders.xsd` (root `Orders`), target `tgt-list.xsd` (root `Out`).

Boxes for target field `Out/All`:

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| P1 | `splitByValue{mode=emptyValue}` | in1 = C1 |
| K1 | `collapseContexts` | in1 = P1 |

Target wires: K1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/splitByValue/021` | `splitByValue-019.xml` | createIf gives "", SUPPRESS, "", SUPPRESS, SUPPRESS in one context. emptyValue cuts at each "" and drops it: (empty) \| SUPPRESS \| SUPPRESS, SUPPRESS, so collapseContexts gives three empty All. SUPPRESS taken for "" gives six. |
