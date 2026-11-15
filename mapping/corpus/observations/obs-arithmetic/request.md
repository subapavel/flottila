# obs-arithmetic

Notation, what to return and the ESR / CPI naming: `../README.md`. Source: `src-orders.xsd` (root
`Orders`). Target: `tgt-list.xsd` (root `Out`). The arithmetic family's assumed cases (spec #70).

## Mapping arith-inv

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Line/Qty` with context `Orders` |
| A1 | `inv` | in1 = S1 |

Target wire: A1 -> `Out/All`.

The assumed reading is that `inv` is 1 divided by the value (ADR-0005's division rule); the other
reading is the sign change (the value times -1). Return the output, per input.

| Case | Input | Question it decides |
| --- | --- | --- |
| `fn/inv/001` | `i1.xml` | Qty is 4, 3, -2, 0.5, one `Line` each. 1 divided by the value is 0.25, 0.333333333333333333 (or PO's own text for 1/3), -0.5, 2; a sign change would give -4, -3, 2, -0.5. |

## Mapping fmt-nnn (formatNum)

One mapping per row below, `nnn` being the row's case number; only the pattern changes.

| Box | Function | Inputs |
| --- | --- | --- |
| N1 | `FormatNum{pattern}`, the Number Format of the row | in1 = `Orders/Order/Line/Qty` with context `Orders` |

Target wires: N1 -> `Out/All`.

`All` holds one value per `Qty`, in document order. Say which default locale the PO 7.5 server's
JVM runs with (it gives DecimalFormat its decimal and grouping separators), and which arithmetic
mode (Java `float`, or `BigDecimal` per SAP Note 958486) if you can see it; the same for CPI.
Our reading (ADR-0003, ADR-0005): DecimalFormat over the exact decimal, with the separators `.`
and `,`, rounding half to even.

| Case | Input | Pattern | Question it decides |
| --- | --- | --- | --- |
| `fn/formatNum/001` | `formatNum-001.xml` | `0` | 7, 2.5, 3.5, -0.4, -1.5: half to even and the sign of a negative zero. Ours: 7, 2, 4, -0, -2. |
| `fn/formatNum/002` | `formatNum-002.xml` | `000` | 7, 42, 1234: leading zeros, no maximum. Ours: 007, 042, 1234. |
| `fn/formatNum/003` | `formatNum-003.xml` | `#` | 0, 0.4, 123: a zero when no digit is left. Ours: 0, 0, 123. |
| `fn/formatNum/004` | `formatNum-004.xml` | `0.00` | 1.5, 0.125, 0.375, 2.675. Ours: 1.50, 0.12, 0.38, 2.68; a `float` mode gives 2.67 for 2.675. |
| `fn/formatNum/005` | `formatNum-005.xml` | `0.##` | 1, 1.5, 1.256, 0.004: trailing zeros and the separator go. Ours: 1, 1.5, 1.26, 0. |
| `fn/formatNum/006` | `formatNum-006.xml` | `#.##` | 0.5, 0, 3.125: one integer digit without a `0` in the pattern. Ours: 0.5, 0, 3.12. |
| `fn/formatNum/007` | `formatNum-007.xml` | `.00` | 0.5, 0, 12.3: no integer digit. Ours: .50, .00, 12.30. |
| `fn/formatNum/008` | `formatNum-008.xml` | `#,##0.00` | 1234567.891, -1234.5, 999.995, 0. Ours: 1,234,567.89, -1,234.50, 1,000.00, 0.00. |
| `fn/formatNum/009` | `formatNum-009.xml` | `#,##,##0` | 1234567, 12: grouping by the last group only. Ours: 1,234,567, 12. |
| `fn/formatNum/010` | `formatNum-010.xml` | `0.#%` | 0.125, -0.5, 1. Ours: 12.5%, -50%, 100%. |
| `fn/formatNum/011` | `formatNum-011.xml` | `0‰` | 0.125, 0.0015. Ours: 125‰, 2‰. |
| `fn/formatNum/012` | `formatNum-012.xml` | `#,##0.00;(0)` | -1234.5, 1234.5, -0.001: the negative subpattern's own digits ignored. Ours: (1,234.50), 1,234.50, (0.00). |
| `fn/formatNum/013` | `formatNum-013.xml` | `'EUR '#,##0.00` | 1234.5, -3: a quoted prefix, the minus sign before it. Ours: EUR 1,234.50, -EUR 3.00. |
| `fn/formatNum/014` | `formatNum-014.xml` | `0.0 m` | 1, -2.25: an unquoted suffix. Ours: 1.0 m, -2.2 m. |
| `fn/formatNum/015` | `formatNum-015.xml` | `0 'o''clock'` | 5: two quotes in quoted text. Ours: 5 o'clock. |
