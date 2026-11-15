# obs-target-creation-2

Notation, what to return and the ESR / CPI naming: `../README.md`. Structures are in
`../../structures/observations/`. Source of every mapping: `src-orders.xsd` (root `Orders`). The
follow-up of `obs-target-creation` (#31) asked by findings F-38 (part 1) and F-37 (part 2),
request #134. One run of an input answers every case that names it. A run that ends with an
error: return the exact error text and stop there. Each case id is a directory under
`corpus/cases/`.

Side observation to watch (from #31, no finding yet): a complex target node with a mapped value
and no mapped child gets the value as text (`<Row>a</Row>`). Every mapping of part 1 shows it;
say if a system writes it otherwise.

# Part 1: a parent occurrence whose context the child queue lacks (F-38)

In #31, mapping spec2-nested-grp-row-remove-contexts on `minOccurs-004.xml` gave the second `Grp`
one `Row` with the queue's last value `b`, where the `Row` queue (one context `a, b`) has no
second context. In #31 d1, an optional `V` (via `mapWithDefault`) got nothing at the parent
occurrence whose context its queue lacks. These runs separate what decides it.

## Mapping mc-opt (target `tgt-nested-opt.xsd`, root `Doc`)

Mapping spec2-nested-grp-row-remove-contexts of #31 with the target `Row` 0..2 instead of 1..2.
On CPI, edit that mapping's target in place.

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
| `minOccurs-004.xml` | Grp per Id: o1, o2. Row queue: one context `a, b`. Grp 1 gets Rows a and b. Does Grp 2 still get one Row `b` with `Row` optional? Yes: the reuse does not depend on `minOccurs`. No Row: `minOccurs` 1 made PO fill the missing context. Display queue of R1 helps. | F-38; ADR-0006 target slots, the `min` check |

## Mapping mc-min2 (target `tgt-nested-min2.xsd`, root `Doc`)

The same boxes and wires as mc-opt, target `Row` 2..2.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `minOccurs-004.xml` | Grp 1 gets Rows a and b (two values, `minOccurs` 2 met). Grp 2: one Row `b`, two Rows `b`, `b` (PO repeats the last value up to `minOccurs`), or an error (record its text)? | F-38 |

## Mapping mc-memo (target `tgt-nested.xsd`, root `Doc`)

`Row` is 1..2. No function between the source and `Row`, so the queue has one context per `Memo`.

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Id` with context `Orders` |
| S2 | source | `Orders/Order/Memo/Text` (default context `Memo`) |

Target wires: S1 -> `Doc/Grp`; S2 -> `Doc/Grp/Row`. Nothing else is mapped.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `mc-memo-1.xml` | Memo a under o1, b under o2, none under o3: Row queue `[a] [b]`, three Grp. Grp 1 Row a, Grp 2 Row b. Grp 3 reads a context the queue lacks: one Row `b` (as with `removeContexts`), an error, or no Row? Compares a source queue with the `removeContexts` result of mc-opt. | F-38 |

## Mapping mc-memo-opt (target `tgt-nested-opt.xsd`, root `Doc`)

The same boxes and wires as mc-memo, target `Row` 0..2.

| Input | Question it decides | Tests |
| --- | --- | --- |
| `mc-memo-1.xml` | As in mc-memo with `Row` optional. Grp 3: one Row `b`, or no Row (as d1 of #31 for `V`)? | F-38 |
| `mc-memo-2.xml` | Memo a under o1, an empty `<Memo/>` under o2, none under o3: Row queue `[a] []`, its last context empty. Grp 2 reads the empty context: no Row (assumed, as #31 d2). Grp 3 reads a context the queue lacks: one Row `a` (the queue's last value), or no Row (the last context, empty, or a missing context read as empty)? | F-38 |

The assumed case of part 1, unchanged since #31 and contradicted there (F-38). It is answered by
the runs above, not by a new run.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `target/minOccurs/004` | `minOccurs-004.xml` | The #31 mapping spec2-nested-grp-row-remove-contexts (Row 1..2). #31 observed Grp 2 with one Row b. The case keeps the assumed error `po:MINOCCURS` at `T2` until mc-opt, mc-min2 and mc-memo give the rule. |

# Part 2: the `createIf` values #31 did not try (F-37)

#31 observed: `true`, `TRUE`, `1` give `""`; `false`, `0` give SUPPRESS; `no`, `skip`, `""` end the
run with "Cannot cast ... to boolean".

## Mapping create-if (target `tgt-list.xsd`, root `Out`)

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |

Target wire: C1 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/createIf/009` | `createIf-009.xml` | Tags FALSE, 1, True in one context. Assumed: `false` in any case is false and `true` in any case true, so SUPPRESS, "", "": two empty All. An error on FALSE or True: record its text. |
| `fn/createIf/007` | `createIf-007.xml` | Tag " true" (leading space). Assumed: no trimming, so the run ends with "Cannot cast ' true' to boolean" (`po:CREATEIF` at `C1`). Trimmed: one empty All. |

## Mapping create-if-twice (target `tgt-list.xsd`, root `Out`)

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` with context `Orders` |
| C1 | `createIf` | in1 = S1 |
| C2 | `createIf` | in1 = C1 |

Target wire: C2 -> `Out/All`. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `fn/createIf/008` | `createIf-008.xml` | Tags false, 0: C1 gives SUPPRESS, SUPPRESS. Does C2 read SUPPRESS as an input? Assumed: SUPPRESS gives SUPPRESS, so `Out` is written empty. An error: record its text. Display queue of C2 helps. |
| `fn/createIf/003` | `createIf-003.xml` | Tags true, false, 1: C1 gives "", SUPPRESS, "". C2 reads "" first. Assumed, as #31 observed for an empty source value: "Cannot cast '' to boolean" (`po:CREATEIF` at `C2`). |
