# obs-statistic

Notation, what to return and the ESR / CPI naming: `../README.md`. Source of all mappings:
`src-orders.xsd` (root `Orders`). Target of all mappings: `tgt-list.xsd` (root `Out`). The one
request of the statistic family (spec #70, #75). Every mapping maps `Orders/Order` -> `Out/Item`
(node to node), so `Item` *i* is order *i* and the queue of `V` (and `W`) belongs to it by context.
One run of an input answers every case that names it. Each case id is a directory under
`corpus/cases/`.

## Mapping stat-sum

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Qty` with context `Order` |
| F1 | `sum` | in1 = S2 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sum/002` | `sum-002.xml` | Empty contexts: o1 has no Line and o2's one Line has no Qty, so Qty in context Order is (empty) \| (empty) \| 4. Assumed: an empty context sums to 0, as XPath's sum(()), so V is 0, 0, 4. If PO gives no value, the first two Items have no V. |

## Mapping stat-sum-suppress

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Tag` (default context `Order`) |
| C1 | `createIf` | in1 = S2 |
| R1 | `replaceValue{replacement=5}` | in1 = C1 |
| F1 | `sum` | in1 = R1 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/sum/003` | `sum-003.xml` | R1 is 5, SUPPRESS, 5 \| SUPPRESS \| (empty) \| 5 (a Tag true gives 5, false SUPPRESS). Assumed: sum skips SUPPRESS and a SUPPRESS-only context sums to 0: V is 10, 0, 0, 5. Return R1's display queue too. If SUPPRESS raised an error or counted as a value, the output differs. |

## Mapping stat-average

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Qty` with context `Order` |
| F1 | `average` | in1 = S2 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/average/003` | `average-003.xml` | Empty contexts, as sum-002: Qty in context Order is (empty) \| (empty) \| 4. Assumed: average of no value is an empty context, as XPath's avg(()), so only the third Item has V, 4. PO may write 0, NaN or raise an error instead: return what it does. |

## Mapping stat-average-suppress

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Tag` (default context `Order`) |
| C1 | `createIf` | in1 = S2 |
| R1 | `replaceValue{replacement=5}` | in1 = C1 |
| F1 | `average` | in1 = R1 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/average/004` | `average-004.xml` | R1 is 5, SUPPRESS, 5 \| SUPPRESS \| 5. Assumed: average skips SUPPRESS, so V is 5, none, 5. Counting SUPPRESS as 0 would give 3.333… for o1 and 0 for o2. |

## Mapping stat-count

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| F1 | `count` | in1 = S2 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/count/002` | `count-002.xml` | o1 has no Line and o2's one Line has no Sku; o3 has `<Sku/>` and `x`. Assumed: an empty context counts 0 and the empty string is a value, so V is 0, 0, 2. If PO gives no value for an empty context, or skips `<Sku/>`, the output differs. |

## Mapping stat-count-suppress

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Tag` (default context `Order`) |
| C1 | `createIf` | in1 = S2 |
| F1 | `count` | in1 = C1 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/count/003` | `count-003.xml` | C1 is "", SUPPRESS, "" \| SUPPRESS \| (empty). Assumed: count skips SUPPRESS, V is 2, 0, 0. Counting SUPPRESS would give 3, 1, 0. |

## Mapping stat-index-default

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| F1 | `index` with its parameters as the editor proposes them | in1 = S2 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/index/003` | `index-003.xml` | Sku in context Order is a, b \| (empty) \| c. Assumed defaults: initial value 1, increment 1, reset at each new context, so V is 1, 2 \| none \| 1. Record the parameter labels and defaults PO 7.5 shows. |

## Mapping stat-index-suppress

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Tag` (default context `Order`) |
| C1 | `createIf` | in1 = S2 |
| F1 | `index{initial=1, increment=1, reset at each context}` | in1 = C1 |

Target wires: `Orders/Order` -> `Out/Item`; F1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/index/004` | `index-004.xml` | C1 is "", SUPPRESS, "" \| SUPPRESS, "" \| (empty). Assumed: SUPPRESS keeps its slot and takes no number, so V is 1, 2 \| 1 \| none. Numbering SUPPRESS too would give 1, 3 \| 2; turning SUPPRESS into a number would add V elements. Return F1's display queue. |

## Mapping stat-counter-two-fields

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Id` (default context `Order`) |
| N1 | `counter{initial=1, increment=1}` | in1 = S2 |
| S3 | source | `Orders/Order/Id` (default context `Order`) |
| N2 | `counter{initial=1, increment=1}` | in1 = S3 |

Target wires: `Orders/Order` -> `Out/Item`; N1 -> `Out/Item/V`; N2 -> `Out/Item/W`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/counter/001` | `counter-001.xml` | The input `s1.xml` of obs-shared-box (mapping shared-2, two separate counters): three orders with an Id each. Assumed: each counter counts the calls of its own target field mapping, so V and W are 1, 2, 3 each. formatVersion 1 has no box shared by two target fields (ADR-0010), so obs-shared-box shared-1 decides the shared case; one run of shared-2 there answers this row. |

## Mapping stat-counter

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Line/Sku` with context `Order` |
| N1 | `counter{initial=1, increment=1}` | in1 = S2 |

Target wires: `Orders/Order` -> `Out/Item`; N1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/counter/002` | `counter-002.xml` | Sku in context Order is a, b \| (empty) \| c. Assumed: counter is called once per value of the queue and does not reset at a context change, so V is 1, 2 \| none \| 3. A count per context would give 1 for c. |

## Mapping stat-counter-suppress

| Box | Function | Inputs |
| --- | --- | --- |
| S2 | source | `Orders/Order/Tag` (default context `Order`) |
| C1 | `createIf` | in1 = S2 |
| N1 | `counter{initial=0, increment=2}` | in1 = C1 |

Target wires: `Orders/Order` -> `Out/Item`; N1 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/counter/003` | `counter-003.xml` | C1 is "", SUPPRESS, "" \| (empty) \| "". Assumed: SUPPRESS keeps its slot and is not counted, so V is 0, 2 \| none \| 4. Counting SUPPRESS too would give 0, 4 \| 6. Return N1's display queue. |
