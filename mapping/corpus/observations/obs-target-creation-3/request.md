# obs-target-creation-3

Notation, what to return and the ESR / CPI naming: `../README.md`. Structures are in
`../../structures/observations/`. The two runs that separate the four readings (a) to (d) that
`obs-target-creation` and `obs-target-creation-2` left open for a target field that reads a
context its queue lacks (the assumed case `target/minOccurs/004`). A run that ends with an error:
return the exact error text and stop there. No case is set by this request; the answers go to the
mapping's finding F-38.

## What the earlier runs showed

`Grp` is one per `Id`, three orders give three `Grp`; `Row` is 1..2 under `Grp`. When the `Row`
queue has fewer contexts than there are `Grp`, the last `Grp` reads a context the queue lacks.

| Earlier run | Row queue | Last Grp |
| --- | --- | --- |
| `removeContexts(Sku)`, `Row` 1..2: one context `a, b` | function result, one context, last context at `max` | one Row `b` |
| `removeContexts(Sku)`, `Row` 2..2 | same | two Rows `b`, `b` |
| `removeContexts(Sku)`, `Row` 0..2 | same | no Row |
| `Memo/Text` with no function, `Row` 1..2: two contexts `[a] [b]` | source queue, several contexts, last context below `max` | error: values missing in queue context |

The readings the runs leave:

- (a) the queue's last context already reached `max` (pads) against not (error);
- (b) a function's result queue (pads) against a source queue (error);
- (c) a queue of one context (pads) against one of several (error);
- (d) PO's cursor: a slot that stops at `max` leaves the end marker unread, and a later read below
  `min` repeats the marker's value.

# Mapping mc-memo-two (source `src-memos.xsd`, target `tgt-nested.xsd`, root `Doc`)

The mapping `mc-memo` of `obs-target-creation-2` with the source `src-memos.xsd`, where `Memo`
holds up to two `Text`. `Row` is 1..2. No function between the source and `Row`, so the queue has
one context per `Memo`, and the last context holds two values.

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Id` with context `Orders` |
| S2 | source | `Orders/Order/Memo/Text` (default context `Memo`) |

Target wires: S1 -> `Doc/Grp`; S2 -> `Doc/Grp/Row`. Nothing else is mapped.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `memo-two-text.xml` | Memo a under o1, b and c under o2, none under o3: Row queue `[a] [b, c]`, three Grp. Grp 1 Row a, Grp 2 Rows b and c. Grp 3 reads a context the queue lacks, after a last context that reached `max` in a source queue. One Row `c` (or two Rows `c`, `c`): (a) and (d) read it, (b) and (c) do not. An error (record its text, as in `mc-memo`): (b) and (c). No Row: none of the four. Display queue of S2 helps. | F-38 readings (a) to (d) |

# Mapping mc-remove-one (source `src-orders.xsd`, target `tgt-nested.xsd`, root `Doc`)

The mapping spec2-nested-grp-row-remove-contexts of `obs-target-creation` (`Row` 1..2), over an
input whose queue has one value in its only context.

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

| Input | Question it decides | Tests |
| --- | --- | --- |
| `one-value.xml` | Only o1 has a Line: Row queue of R1 is one context `a`, below `max`. Grp 1 gets Row a. Grp 2 and Grp 3 read a context the queue lacks. One Row `a` each: (b) and (c) read it, (a) and (d) do not. An error (record its text and where it fired): (a) and (d). Display queue of R1 helps. | F-38 readings (a) to (d) |

Together: padding in both runs means the reuse does not depend on the queue's kind, length or
fill; an error in both means case `target/minOccurs/004` read a special case; the other results
each keep only the readings named in the two tables.
