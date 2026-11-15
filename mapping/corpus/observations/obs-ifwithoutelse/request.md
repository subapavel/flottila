# obs-ifwithoutelse

Notation, what to return and the ESR / CPI naming: `../README.md`. Source: `src-orders.xsd` (root
`Orders`). Target: `tgt-list.xsd` (root `Out`). Mirrors the SAP 7.11 worked example
(`concat(counter, ifWithoutElse(...))`).

## Mapping iwe-1

Chain for `Out/Item/V` (parameter Else = empty string):

| Box | Function | Inputs |
| --- | --- | --- |
| C1 | `Constant{0}` | none |
| G1 | `greater` | in1 = `Orders/Order/Line/Qty` with context `Order`; in2 = C1 |
| X1 | `ifWithoutElse`, Else = empty string | in1 = G1; in2 = `Orders/Order/Line/Sku` with context `Order` |
| N1 | `counter{initial=1, increment=1}` | in1 = `Orders/Order/Line/Sku` with context `Order` |
| K1 | `concat` | in1 = N1; in2 = X1 |

Chain for `Out/Item/W` (parameter Else = SUPPRESS, the "Keep SUPPRESS Values" choice; record the
exact label PO 7.5 and CPI show and which one is the default):

| Box | Function | Inputs |
| --- | --- | --- |
| C2 | `Constant{0}` | none |
| G2 | `greater` | in1 = `Orders/Order/Line/Qty` with context `Order`; in2 = C2 |
| X2 | `ifWithoutElse`, Else = SUPPRESS | in1 = G2; in2 = `Orders/Order/Line/Sku` with context `Order` |
| N2 | `counter{initial=1, increment=1}` | in1 = `Orders/Order/Line/Sku` with context `Order` |
| K2 | `concat` | in1 = N2; in2 = X2 |

Target wires: `Orders/Order` -> `Out/Item`; K1 -> `Out/Item/V`; K2 -> `Out/Item/W`. Two separate
counters: no box is shared.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `i1.xml` | Lines (a,5), (b,0), (c,7). Else = empty string: assumed `concat` skips the context, `V` = `1a`, `2c`. Else = SUPPRESS: assumed SUPPRESS is linked to the second counter value, `W` = `1a`, `3c`. This is the SAP 7.11 result; does PO 7.5 give it, and with which parameter label and default? Display queues of X1, X2, K1, K2. | #2 §7.11 |
| `i2.xml` | Lines (a,0), (b,0): every condition is false. Else = empty string: `V` empty or `1`,`2`? Else = SUPPRESS: no `W`? Shows whether an all-false context still creates a target value. | #2 §7.11 |
