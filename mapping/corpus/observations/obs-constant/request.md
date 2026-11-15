# obs-constant

Notation, what to return and the ESR / CPI naming: `../README.md`. Source of all mappings:
`src-orders.xsd` (root `Orders`). Target of all mappings: `tgt-list.xsd` (root `Out`), except
`constant-gen-numbering` (`tgt-gen.xsd`, root `Gens`). The one request of the constant family
(spec #70, #79, #83). Every mapping maps `Orders/Order` -> `Out/Item` (node to node), or
`Orders/Order` -> `Gens/Grp`, so `Item` *i* is order *i* and the queue of `V` belongs to it by
context. One run
of an input answers every case that names it. Each case id is a directory under `corpus/cases/`.
`CopyValue`'s parameter is the position the editor asks for; write it as the request gives it.

## Mapping copy-value

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| F1 | `CopyValue{1}` | in1 = S2 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/copyValue/001` | `copyValue-001.xml` | Which value position 1 copies, and what a context too short for it gives: Sku in context Order is a, b, c \| d \| e, f. Assumed: the position counts from 0 in each context, and a context without that position gives no value, so V is b, (none), f. Return F1's display queue too. Counting from 1 gives a, d, e; counting across the whole queue gives b in o1 only; an error or an empty V in o2 differs too. |

## Mapping copy-value-default

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| F1 | `CopyValue` (parameter left as the editor proposes it) | in1 = S2 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/copyValue/002` | `copyValue-001.xml` | The default position: assumed 0, the first value of each context, so V is a, d, e. Return the default the editor shows, and whether it lets the box be saved without one. |

## Mapping copy-value-beyond

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| F1 | `CopyValue{3}` | in1 = S2 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/copyValue/003` | `copyValue-001.xml` | A position beyond every context: assumed an empty context each, so no Item has a V and the run succeeds. Return F1's display queue. If PO raises an error (an index exception), return its text exactly. |

## Mapping copy-value-suppress

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Tag` (default context `Order`) |
| C1 | `createIf` | in1 = S2 |
| F1 | `CopyValue{1}` | in1 = C1 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/copyValue/004` | `copyValue-004.xml` | C1 is "", SUPPRESS, "" \| SUPPRESS, "" \| (empty). Assumed: SUPPRESS counts as a position and is copied, so F1 is SUPPRESS \| "" \| (empty) and only o2's Item has a V, empty. Return C1's and F1's display queues. If SUPPRESS is skipped, F1 is "" \| (empty) \| (empty) and only o1's Item has a V. |

## Generating functions (#83)

A `Constant` box has no input; the editor calls such a box a generating function. For each case
return the output, and the display queue of every box the row names.

### Mapping constant-item

| Box | Function | Inputs |
| --- | --- | --- |
| C1 | `Constant{x}` | none |

Target wires: `Orders/Order` -> `Out/Item`; C1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/constant/003` | `constant-001.xml` | **Owner question: does a generating function repeat per parent occurrence?** V is 0..unbounded, so SAP's rule gives one value, but per what? Assumed: once per `Item`, so all three Items get one V x, o2's (no lines) too. If only the first Item gets a V, the value is generated once per message. Return C1's display queue. |

### Mapping constant-concat

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| C1 | `Constant{-x}` | none |
| F1 | `concat` | in1 = S2, in2 = C1 |
| S3 | source | `Orders/Order/Line/Sku` with context `Order` |
| C2 | `Constant{y-}` | none |
| F2 | `concat` | in1 = C2, in2 = S3 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`; F2 -> `Out/Item/W`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/constant/004` | `constant-001.xml` | Sku is a, b \| (empty) \| c. Assumed: the constant joins every value of every context, and o2's empty context stays empty: V is a-x, b-x \| (none) \| c-x and W is y-a, y-b \| (none) \| y-c. Return F1's and F2's display queues. If o2's Item gets a V -x, the constant fills the empty context. |

### Mapping constant-concat-constants

| Box | Function | Inputs |
| --- | --- | --- |
| A1 | `Constant{a}` | none |
| B1 | `Constant{b}` | none |
| F1 | `concat` | in1 = A1, in2 = B1 |
| A2 | `Constant{a}` | none |
| B2 | `Constant{b}` | none |
| F2 | `concat` | in1 = A2, in2 = B2 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`; F2 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/constant/005` | `constant-001.xml` | concat of two constants reads no source field. Assumed: it is a generating function too, so each Item gets one V ab and the root one All ab. Return F1's display queue. |

### Mapping constant-count

| Box | Function | Inputs |
| --- | --- | --- |
| C1 | `Constant{x}` | none |
| N1 | `count` | in1 = C1 |
| C2 | `Constant{w}` | none |

Target wires: `Orders/Order` -> `Out/Item`; N1 -> `Out/Item/V`; C2 -> `Out/Item/W`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/constant/006` | `constant-001.xml` | What a constant is at a context function's input. Assumed: one context with one value, so count is 1 in one context and only the first Item gets a V 1; W w is in every Item. Return C1's and N1's display queues. One context per Item would give V 1 in every Item. |

### Mapping constant-gen-numbering (target `tgt-gen.xsd`, root `Gens`)

`Grp` is 0..unbounded, `Gen` is 2..3, `V` is 0..1.

| Box | Function | Inputs |
| --- | --- | --- |
| C1 | `Constant{g}` | none |
| S3 | source | `Orders/Order/Line/Qty` (default context `Line`) |

Target wires: `Orders/Order` -> `Gens/Grp`; C1 -> `Gens/Grp/Gen`; S3 -> `Gens/Grp/Gen/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/constant/007` | `constant-007.xml` | Orders with 3 lines (Qty 1, 2, 3) and 1 line (Qty 4). Gen is generated minOccurs = 2 times per Grp. Assumed: Gen j of Grp p reads Qty's context (p - 1) * 2 + j, so Grp 1 gets 1, 2 and Grp 2 gets 3, 4. If Grp 2 gets 4 only, the children are matched per order; if both get 1, 2, per Gen alone. The same question as obs-target-creation `b2.xml`, with a constant instead of concat. |

### Mapping constant-empty

| Box | Function | Inputs |
| --- | --- | --- |
| C1 | `Constant` (value left empty) | none |

Target wires: C1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/constant/008` | `constant-001.xml` | A Constant whose value is left empty. Assumed: the empty string, so one empty All. Return whether the editor lets the box be saved without a value. |

## FixValues (#83)

Each mapping reads `Orders/Order/Line/Sku` with context `Order` as S2 into F1 (in1 = S2) and
wires F1 -> `Out/Item/V`. The table is written `key=value, …`; ' ' marks a space at an end of a
key. Write the missing-key option as the row gives it, and return the labels the dialog offers.

| Mapping | F1 |
| --- | --- |
| fix-values-key | `FixValues{a=A, d=D}`, the option for a missing key left as proposed |
| fix-values-default | `FixValues{a=A, d=D}`, missing key: use the default value `?` |
| fix-values-default-empty | `FixValues{a=A}`, missing key: use the default value, left empty |
| fix-values-empty-table | `FixValues{}` (no row), missing key: use the default value `-` |
| fix-values-exact | `FixValues{a=A, ' b'=B}`, missing key: use the default value `?` |
| fix-values-suppress | `FixValues{''=E}` (the empty key), missing key: throw an exception; in1 = C1, where C1 is `createIf` with in1 = `Orders/Order/Tag` |

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/fixValues/002` | `copyValue-001.xml` | **Owner question: the options for a key the table lacks, their labels and the default.** Assumed: three options, use the key (the default), use a default value, throw an exception; so b, c, e, f pass as they are: A, b, c \| D \| e, f. Return the dialog's options as labelled and which is preselected, and the error text when `fix-values-key` is run with "throw an exception" (we raise `po:FIXVALUES`, fn/fixValues/004). |
| `fn/fixValues/003` | `copyValue-001.xml` | Assumed: the default value replaces every missing key: A, ?, ? \| D \| ?, ?. |
| `fn/fixValues/005` | `copyValue-001.xml` | A default value left empty. Assumed: the empty string, so every Item gets an empty V for a missing key. |
| `fn/fixValues/006` | `copyValue-001.xml` | A table without rows. Assumed: every key is missing, so every V is -. Return whether the editor saves an empty table. |
| `fn/fixValues/007` | `copyValue-004.xml` | C1 is "", SUPPRESS, "" \| SUPPRESS, "" \| (empty). Assumed: SUPPRESS passes through FixValues as SUPPRESS and throws nothing; "" is the key '' and gives E: Item 1 gets E, E, Item 2 E, Item 3 none. Return C1's and F1's display queues. |
| `fn/fixValues/008` | `fixValues-008.xml` | Skus A, ' a', 'a ', a \| b, ' b', ' b '. Assumed: the key matches exactly, so only a and ' b' are found: ?, ?, ?, A \| ?, B, ?. A match ignoring case or trimming differs. |
