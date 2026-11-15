# obs-division

Notation, what to return and the ESR / CPI naming: `../README.md`. Source: `src-orders.xsd` (root
`Orders`). Target: `tgt-list.xsd` (root `Out`).

## Mapping div-1

| Box | Function | Inputs |
| --- | --- | --- |
| D1 | `divide` | in1 = `Orders/Order/Line/Qty` with context `Order` (dividend); in2 = `Orders/Order/Line/Sku` with context `Order` (divisor) |

Target wires: `Orders/Order` -> `Out/Item`; D1 -> `Out/Item/V`.

Say which arithmetic mode the PO 7.5 system runs (Java `float`, or `BigDecimal` per SAP Note
958486) if you can see it, and the same for CPI. ADR-0005 (our rule): a terminating quotient is
exact, else 18 fractional digits (more if the dividend has a longer scale), the last digit rounded
half-down; `1 div 3` = `0.333333333333333333`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `d1.xml` | One order, eight lines, `V` in line order, each as dividend / divisor: 1/3, 2.0/3, 4/2, 1/4, 10/4, 7/3, 1/30000000000000000000001, 0.1/3. The main question is PO's text for `1/3` (ADR-0005 catalogue deviation). Also: is `4/2` written `2` (trailing `.0` cut)? `1/4` = `0.25`? What does `1/30000000000000000000001` give (ours: `0`)? | ADR-0005 |
| `d2.xml` | One line, 7/0. Error text, `Infinity`, or another value? (ours: `FOAR0001`.) | ADR-0005 |
