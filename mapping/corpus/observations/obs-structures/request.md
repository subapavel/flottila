# obs-structures

Notation, what to return and the ESR / CPI naming: `../README.md`.

## Mapping str-choice-1 (source `src-orders.xsd`, target `tgt-choice.xsd`, root `Choice`)

`Choice/Pick` is 1..1 and holds a choice of `A` or `B`; each branch is declared 1..1.

| Box | Function | Inputs |
| --- | --- | --- |
| C1 | `Constant{A}` | none |
| E1 | `equalsS` | in1 = `Orders/Order/Id` with context `Orders`; in2 = C1 |
| X1 | `ifWithoutElse`, Else = SUPPRESS | in1 = E1; in2 = `Orders/Order/Id` with context `Orders` |
| C2 | `Constant{B}` | none |
| E2 | `equalsS` | in1 = `Orders/Order/Id` with context `Orders`; in2 = C2 |
| X2 | `ifWithoutElse`, Else = SUPPRESS | in1 = E2; in2 = `Orders/Order/Id` with context `Orders` |

Target wires: X1 -> `Choice/Pick/A`; X2 -> `Choice/Pick/B`.

| Case | Input | Question it decides and the assumed reading (ADR-0008 Consequences, assumed reading 1) |
| --- | --- | --- |
| `target/choiceBranch/001` | `ch1.xml` | Id `A`: `A` is written, `B` is SUPPRESS. Does the suppressed branch `B` (declared `minOccurs` 1) raise a shortfall? Assumed: no, a branch of a choice counts as optional. Output `<Pick><A>A</A></Pick>`. Record the error text if PO fails. |
| `target/choiceBranch/002` | `ch2.xml` | Id `B`: mirror of ch1. Assumed output `<Pick><B>B</B></Pick>`. |
| `target/choiceBranch/003` | `ch3.xml` | Id `C`: both branches SUPPRESS. Assumed: no error from either branch, `Pick` is written empty. Record the output or the error. |

## Mapping str-choice-2 (same structures)

Only X1 and its chain (C1, E1) are drawn; `Choice/Pick/B` stays unmapped. Target wire: X1 ->
`Choice/Pick/A`.

| Case | Input | Question it decides and the assumed reading (ADR-0008 Consequences, assumed reading 1) |
| --- | --- | --- |
| `target/choiceBranch/004` | `ch1.xml` | Is an unmapped branch `B` (declared 1..1, inside a choice) a design-time error? Assumed: no, it counts as optional, and the run writes `<Pick><A>A</A></Pick>`. Record the editor message, if any, and the run output. |
| `target/choiceBranch/005` | `ch2.xml` | Id `B`, `A` suppressed and `B` unmapped: output `<Pick/>`, or an error? Assumed: `<Pick/>`. |

## Mapping str-nil (source `src-nil.xsd`, root `Nil`; target `tgt-list.xsd`, root `Out`)

`Nil/Item/Val` is 0..1 and `nillable`.

| Box | Function | Inputs |
| --- | --- | --- |
| K0 | `Constant{!}` | none |
| K1 | `concat` | in1 = `Nil/Item/Val` (default context `Item`); in2 = K0 |
| E1 | `exists` | in1 = `Nil/Item/Val` (default context `Item`) |

Target wires: `Nil/Item` -> `Out/Item` (node to node); K1 -> `Out/Item/V`; E1 -> `Out/Item/W`.

| Case | Input | Question it decides | Tests |
| --- | --- | --- | --- |
| none yet | `nil.xml` | Four items: `<Val>x</Val>`, `<Val xsi:nil="true"/>`, `<Val/>`, no `Val`. Does a nil element read as the empty string? Assumed: item 2 behaves like item 3, `V` = `!` (an empty string value), not like item 4 (no `V`). Assumed output `V`: `x!`, `!`, `!`, none; `W`: `true`, `true`, `true`, `false` (the `exists` result for item 2 is the open part; record it). | ADR-0008 bullet 2 |
| `structure/jsonNull/001` | `nil.json` | The same four items as JSON (`"x"`, `null`, `""`, missing key), validated against `nil.schema.json`. Convert with the CPI JSON to XML converter (root element name `Nil`, no namespace) and, on PO 7.5, the REST adapter's JSON to XML conversion (root `Nil`). Run the same mapping on the converted XML. Return the converted XML (before the mapping) and the output. Assumed: `null` reads as the empty string, so `V` = `x!`, `!`, `!`, none. If the converted XML does not use the root `Nil` and the elements `Item` / `Val`, return it unchanged and stop; do not adapt the mapping. | ADR-0008 bullet 2 |

## Mapping str-required-attr (source `src-orders.xsd`, root `Orders`; target `tgt-required-attr.xsd`, root `Coded`)

`Coded` carries the attribute `code`, declared `use="required"`; `Coded/V` is 0..unbounded.

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Id` with context `Orders` |

Target wires: S1 -> `Coded/V`. The attribute `Coded/@code` stays unmapped. Nothing else is mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `target/requiredAttribute/001` | `attr1.xml` | Two orders, Id `X1` and `X2`. Is an unmapped `use="required"` attribute a design-time error, as an unmapped leaf element with `minOccurs` 1 is (obs-target-creation create-c)? Record the editor message and where it shows (save, activation, test), and what a run produces: `<Coded><V>X1</V><V>X2</V></Coded>` without `code`, an empty `code`, or an error. Assumed: design-time error, `unmapped-mandatory-leaf` at the absent target field mapping of `Coded/@code`; the run never starts. |

## Mapping str-attr (source `src-orders.xsd`, root `Orders`; target `tgt-attr.xsd`, root `Flagged`)

`Flagged` carries the attribute `flag` (optional) and the attribute `code` (`use="required"`), and
holds `V` 0..unbounded.

| Box | Function | Inputs |
| --- | --- | --- |
| S1 | source | `Orders/Order/Tag` (default context `Order`) |
| C1 | `createIf` | in1 = S1 |
| S2 | source | `Orders/Order/Id` (default context `Order`) |
| S3 | source | `Orders/Order/Id` with context `Orders` |

Target wires: C1 -> `Flagged/@flag`; S2 -> `Flagged/@code`; S3 -> `Flagged/V`. Nothing else is
mapped.

| Case | Input | Question it decides and the assumed reading |
| --- | --- | --- |
| `target/attribute/001` | `attr2.xml` | One order, Id `X1`, Tag `false` (not `no`: PO's createIf fails on `no`, F-37): C1 gives SUPPRESS. Does a SUPPRESS slot on an attribute write no attribute, as on an element? Record the output. Assumed: `<Flagged code="X1"><V>X1</V></Flagged>`, no `flag`. |
| `target/attribute/002` | `attr3.xml` | One order without Id, Tag `false`: `code` gets an empty context. Does a required attribute with no value fail the run as a 1..1 element does (`minOccurs`)? Record the error text and where it fires, or the output. Assumed: the run fails at `code`'s target field mapping (`po:MINOCCURS`); no output. |
| `target/attribute/003` | `attr4.xml` | One order, Id `X1`, Tag `true`: C1 gives the empty string. Is it written as an empty attribute, as a leaf element gets `<E/>`? Assumed: `<Flagged flag="" code="X1"><V>X1</V></Flagged>`. |
| none yet (#100) | `attr4.xml` | The qualified prefix. Draw the same mapping onto `tgt-attr-ns.xsd` (root `Flagged` in `urn:flottila:obs:attr`, attributes qualified, `V` unqualified). Record the output bytes: the prefix of `Flagged` and of each attribute, and on which element each namespace is declared. Assumed (ADR-0022): `<ns0:Flagged xmlns:ns0="urn:flottila:obs:attr" ns0:flag="" ns0:code="X1"><V>X1</V></ns0:Flagged>`. |
