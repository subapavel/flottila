# obs-shared-box

Notation, what to return and the ESR / CPI naming: `../README.md`. Source: `src-orders.xsd` (root
`Orders`). Target: `tgt-list.xsd` (root `Out`). The question: does PO evaluate a box whose output
is wired to two target fields once? A stateful box shows it: `counter` keeps a number, so a second
evaluation would continue it (ADR-0010; a UDF with state is a later request).

## Mapping shared-1: one box, two target fields

| Box | Function | Inputs |
| --- | --- | --- |
| N1 | `counter{initial=1, increment=1}` | in1 = `Orders/Order/Id` (default context `Order`) |

Target wires: `Orders/Order` -> `Out/Item`; N1 -> `Out/Item/V` **and** N1 -> `Out/Item/W` (the
same output port wired twice).

## Mapping shared-2: control, two boxes

| Box | Function | Inputs |
| --- | --- | --- |
| N2 | `counter{initial=1, increment=1}` | in1 = `Orders/Order/Id` (default context `Order`) |
| N3 | `counter{initial=1, increment=1}` | in1 = `Orders/Order/Id` (default context `Order`) |

Target wires: `Orders/Order` -> `Out/Item`; N2 -> `Out/Item/V`; N3 -> `Out/Item/W`.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `s1.xml` | Three orders, run through both mappings. shared-2 is the control: `V` and `W` are `1`,`2`,`3` each (SAP: counter counts the calls of its target-field mapping). shared-1: evaluated once or with fresh state per use, `W` = `1`,`2`,`3` too; evaluated twice with shared state, `W` = `4`,`5`,`6`. Does `W` continue the count, and does the order of the target fields change it? Return both outputs. | ADR-0010, ADR-0018 |
