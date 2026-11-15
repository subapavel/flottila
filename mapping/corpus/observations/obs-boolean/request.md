# obs-boolean

Notation, what to return and the ESR / CPI naming: `../README.md`. Source of all mappings:
`src-orders.xsd` (root `Orders`). Target of all mappings: `tgt-list.xsd` (root `Out`). The one
request of the boolean family (spec #70, #77, #78). Every mapping but the ifWithoutElse ones at
the end maps F1 -> `Out/All`; S1 is
`Orders/Order/Line/Sku`, S2 `Orders/Order/Line/Qty` and S3 `Orders/Order/Tag`, all with context
`Orders`, so All has one value per Line. One run of an input answers every case that names it.
Each case id is a directory under `corpus/cases/`. The open question of the whole family: is
only the exact text `true` true, and are results written `true` / `false`? Assumed: yes. Return
what PO writes if it differs (for example `TRUE`, `1` or the empty string read as true, or a
result written `1` / `0`). The `ifWithoutElse` mappings (#78) draw their own wires and contexts;
`obs-ifwithoutelse` (#35) asks the related `concat(counter, ifWithoutElse(...))` question.
Since F-37 (#31): PO's `createIf` casts with `flib7.Bool.toBoolean` and ends the run with
"Cannot cast 'no' to boolean" on any value but `true` / `false` (any case), `1`, `0`. If a run
here ends with that error, return its exact text: it answers the run's cases (one failing value
stops the whole run, so note which value it names).

## Mapping boolean-and

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| F1 | `and` | in1 = S1, in2 = S2 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/and/001` | `and-001.xml` | Lines (Sku, Qty): (true, true), (true, false), (TRUE, true), (empty, true), (1, 1). Assumed All: `true`, `false`, `false`, `false`, `false`: only the exact text `true` is true, so `TRUE` and `1` are false. |

## Mapping boolean-or

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| F1 | `or` | in1 = S1, in2 = S2 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/or/001` | `or-001.xml` | Lines: (true, true), (false, true), (false, false), (TRUE, 1), (empty, true). Assumed All: `true`, `true`, `false`, `false`, `true`. The fourth line decides whether `TRUE` or `1` counts as true in `or`. |

## Mapping boolean-not

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| F1 | `not` | in1 = S1 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/not/001` | `not-001.xml` | Lines: true, false, TRUE, 1, empty. Assumed All: `false`, `true`, `true`, `true`, `true`: anything but the exact text `true` is false, so `not` of it is true. If PO reads `TRUE` or `1` as true, `not` gives `false` there. |

## Mapping boolean-equals

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| F1 | `equals` | in1 = S1, in2 = S2 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/equals/001` | `equals-001.xml` | Lines: (keep, keep), (keep, Keep), (empty, empty), (a, empty), (1, 1.0). Assumed All: `true`, `false`, `true`, `false`, `false`: the values are compared as case-sensitive text, so `1` and `1.0` differ. If PO compares numbers as numbers, the last is `true`. |

## Mapping boolean-notEquals

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| F1 | `notEquals` | in1 = S1, in2 = S2 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/notEquals/001` | `notEquals-001.xml` | The same lines as equals. Assumed All: `false`, `true`, `false`, `true`, `true`, the negation of `equals`. |

## Mapping boolean-if

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| S3 | source | `Orders/Order/Tag` with context `Orders` |
| F1 | `if` | in1 = S1 (condition), in2 = S2 (then), in3 = S3 (else) |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/if/001` | `if-001.xml` | Lines (Sku, Qty) with Tags e1 to e4 in the order: (true, q1), (false, q2), (TRUE, q3), (empty, q4). Assumed All: `q1`, `e2`, `e3`, `e4`: the then value only for the exact text `true`. |

## Mapping boolean-isNil

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| F1 | `isNil` | in1 = S1 |

Target wire: F1 -> `Out/All`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/isNil/001` | `isNil-001.xml` | Four lines: `x`, `<Sku xsi:nil="true"/>`, `<Sku></Sku>`, `<Sku> </Sku>` (a space). Assumed All: `false`, `true`, `true`, `false`. Our engine reads `xsi:nil` as the empty string (ADR-0008, `obs-structures` nil.xml), so it cannot tell a nil field from an empty one. If PO gives `false` for the empty element, or `false` for the nil field, return both results. |

## ifWithoutElse (#78)

The open question of these mappings: what does a false condition give? Assumed: the box parameter
"Keep SUPPRESS Values" (ours `keepSuppressValues`), off by default, gives no value at all, as
SAP's empty-string Else value that the next function skips; on, it gives SUPPRESS, which keeps
its slot (#2 §3, #2 §7.11). Record the label PO 7.5 and CPI show for the parameter, and which
choice is the default. The inputs pair per context as any function's do (ADR-0006): the longer
input sets the count and the shorter reuses its last value. Wire `Orders/Order -> Out/Item` only
where a mapping says so.

### Mapping boolean-ifWithoutElse-item

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Qty` with context `Orders` |
| S2 | source | `Orders/Order/Line/Sku` with context `Orders` |
| X1 | `ifWithoutElse`, parameter not set | in1 = S1 (condition), in2 = S2 (then) |
| S3 | source | `Orders/Order/Line/Sku` (default context `Line`) |

Target wires: X1 -> `Out/Item`; S3 -> `Out/Item/V`. Mapping boolean-ifWithoutElse-item-keep is
the same with X1's "Keep SUPPRESS Values" on.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/ifWithoutElse/001` | `ifWithoutElse-001.xml` | Parameter off. Lines (Sku, Qty): (a, true), (b, false), (c, true), (d, true). Assumed: the false line leaves no value, so X1 is a, c, d and the three Items take V from the first three Lines: `a`, `b`, `c`. Return the display queue of X1. |
| `fn/ifWithoutElse/002` | `ifWithoutElse-002.xml` | The same input, parameter on (mapping boolean-ifWithoutElse-item-keep). Assumed: X1 is a, SUPPRESS, c, d; SUPPRESS keeps its place, so the Items take V from Lines 1, 3 and 4: `a`, `c`, `d`. |

### Mapping boolean-ifWithoutElse-orders

| Box | Function | Inputs |
| --- | --- | --- |
| S0 | source | `Orders/Order` with context `Orders` |
| S1, S3 | source | `Orders/Order/Line/Qty` with context `Order` |
| S2, S4 | source | `Orders/Order/Line/Sku` with context `Order` |
| X1 | `ifWithoutElse`, parameter not set | in1 = S1, in2 = S2 |
| X2 | `ifWithoutElse`, "Keep SUPPRESS Values" on | in1 = S3, in2 = S4 |

Target wires: S0 -> `Out/Item`; X1 -> `Out/Item/V`; X2 -> `Out/Item/W`. No box is shared.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/ifWithoutElse/003` | `ifWithoutElse-003.xml` | o1 (a, true), (b, false); o2 (c, false), (d, TRUE); o3 (e, true). o2's context has no true condition (`TRUE` is not true). Assumed: Items `V`=a `W`=a; empty; `V`=e `W`=e. Does o2's Item get a `V` or `W`, empty or not? |

### Mapping boolean-ifWithoutElse-tags

Mapping boolean-ifWithoutElse-orders with `Orders/Order/Tag` (default context `Order`) in place
of Sku for S2 and S4.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/ifWithoutElse/004` | `ifWithoutElse-004.xml` | o1: Qty true, false, true; Tags x, y. o2: Tag z, no Line. o3: Qty true; Tags p, q, r. o4: Qty true, no Tag. Assumed, `V` and `W` alike: x, y (y used again for the third Qty); none (no condition); p, q, r (true used again); none (no Tag). |

### Mapping boolean-ifWithoutElse-suppress

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2 | source | `Orders/Order/Line/Qty` with context `Orders` |
| C1 | `createIf` | in1 = S2 |
| X1 | `ifWithoutElse`, parameter not set | in1 = S1 (condition), in2 = C1 (then) |
| S3 | source | `Orders/Order/Line/Qty` (default context `Line`) |

Target wires: X1 -> `Out/Item`; S3 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/ifWithoutElse/005` | `ifWithoutElse-005.xml` | Lines (Sku, Qty): (true, 1), (false, 0), (false, TRUE), (true, true). C1 is "", SUPPRESS, "", "". Assumed: the SUPPRESS of C1 stays SUPPRESS with the parameter off, though line 2's condition is false, and only line 3 leaves no value: X1 is "", SUPPRESS, "", so the Items take V from Lines 1 and 3: `1`, `TRUE`. If PO drops that SUPPRESS with the false line, V is `1`, `0`. |

### Mapping boolean-ifWithoutElse-suppress-condition

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Sku` with context `Orders` |
| S2, S3 | source | `Orders/Order/Line/Qty` with context `Orders` |
| X1 | `ifWithoutElse`, "Keep SUPPRESS Values" on | in1 = S1, in2 = S2 |
| X2 | `ifWithoutElse`, parameter not set | in1 = X1 (condition), in2 = S3 (then) |
| S4 | source | `Orders/Order/Line/Qty` (default context `Line`) |

Target wires: X2 -> `Out/Item`; S4 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/ifWithoutElse/007` | `ifWithoutElse-007.xml` | Lines (Sku, Qty): (true, true), (false, true), (true, false), (true, true). X1 is true, SUPPRESS, false, true. Assumed: a SUPPRESS condition gives SUPPRESS, not a false condition, so X2 is true, SUPPRESS, true and the Items take V from Lines 1 and 3: `true`, `false`. If PO reads SUPPRESS as false, V is `true`, `true`. Return the display queues of X1 and X2. |

### Mapping boolean-ifWithoutElse-empty-keep

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Qty` with context `Order` |
| S2 | source | `Orders/Order/Tag` (default context `Order`) |
| X1 | `ifWithoutElse`, "Keep SUPPRESS Values" on | in1 = S1, in2 = S2 |
| R1 | `removeContexts` | in1 = X1 |
| S3 | source | `Orders/Order/Line/Sku` (default context `Line`) |

Target wires: R1 -> `Out/Item`; S3 -> `Out/Item/V`.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/ifWithoutElse/008` | `ifWithoutElse-008.xml` | o1: Tag z, no Line. o2: Line (a, false), no Tag. o3: Tags x, y; Lines (b, true), (c, false). Assumed: o1 and o2 give no value at all, not SUPPRESS, so R1 is x, SUPPRESS and the one Item takes V from the first Line: `a`. If they give SUPPRESS, the Item takes V from the third Line: `c`. Return the display queue of X1. |

### Mapping boolean-ifWithoutElse-collapse

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Qty` with context `Order` |
| S2 | source | `Orders/Order/Line/Sku` (default context `Line`) |
| X1 | `ifWithoutElse`, parameter not set | in1 = S1, in2 = S2 |
| K1 | `collapseContexts` | in1 = X1 |

Target wire: K1 -> `Out/All`. The condition has one context per Order, the value one per Line.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/ifWithoutElse/009` | `ifWithoutElse-009.xml` | o1 with Lines (a, true), (b, true). Assumed: context 1 pairs true, true with a alone, used again, so a, a; context 2 has no condition and is empty. All: `a`, empty. |

### Mapping boolean-ifWithoutElse-collapse-tags

Mapping boolean-ifWithoutElse-collapse with `Orders/Order/Tag` with context `Orders` in place of
Sku for S2: the condition has one context per Order, the value one in all.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/ifWithoutElse/010` | `ifWithoutElse-010.xml` | o1: Tag x, Line (a, true); o2: Line (b, true). Assumed: context 1 is x; context 2 has a true condition but no value and is empty. All: `x`, empty. |
