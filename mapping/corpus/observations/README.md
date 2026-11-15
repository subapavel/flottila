# Observation kit

Eight synthetic mappings for the owner to draw on SAP PO 7.5 (ESR, message mapping) and SAP Cloud
Integration (CPI mapping editor), with the inputs to run them on. Process: ADR-0018 "Observed
cases". Everything here is synthetic (ADR-0002); nothing from a real system enters the repo.

Each directory `<obs-id>/` holds a `request.md` (boxes, parameters, wires, contexts, target root;
for each input the question it decides and the assumed reading it tests) and the input files. The
structures are in `../structures/observations/`. The owner adds `po75/` and `cpi/` (outputs per
input) and `meta.json` (both releases, the date, any divergence). A later `oracle:corpus` ticket
turns them into cases.

## Notation in a `request.md`

- A box is `Function{parameter}`; the table gives its id, function and inputs (`in1`, `in2`, ...
  in the order of the function's input ports in the editor). A constant is a `Constant` box with
  the value in braces.
- A source field is written by its path under the root. Its **context** is the default (its
  parent element) unless the request says `with context X`: set it in the data-flow editor's
  context menu to the element `X` (`Orders` = the root element, `Order` = the order). CPI has the
  same setting.
- `A -> B` is a wire; `Orders/Order -> Out/Item` is a node-to-node mapping of two structure
  elements. A target field with no wire is not mapped.
- `ifWithoutElse` with Else = SUPPRESS is the parameter choice that makes the false branch
  SUPPRESS instead of the empty string.
- Inputs are named by file; an input is run through the mapping(s) its table names. `o1`, `o2`
  ... are the orders of the file in document order.

## What to return per input

1. The target XML as PO or CPI wrote it (or the error text, exactly, and where it fired).
2. Where the request asks for it: the display queue text of the named boxes.
3. PO 7.5 release and support package, CPI release or tenant version, the date. Both systems
   if you can; PO 7.5 is the reference (ADR-0018).
4. A divergence between PO and CPI, or a result that is not any of the readings the request lists.

Put outputs in `po75/<input>-<mapping>.xml` (or `.txt` for an error) and `cpi/` likewise.
`oracle` tests parse the XML and JSON files in this tree (`ObservationKitWellFormedTest`), so keep
them well-formed; a deliberately malformed output goes into a `.txt`.

## Coverage

ADR-0004, ADR-0006 and ADR-0008 each end with a list of assumed readings; the bullet number is
the position in that list (first bullet = 1). `#2 §7.n` is item n of the open ambiguities of
`docs/research/po-mapping-semantics.md` on branch `research/po-mapping-semantics`.

| Assumed reading | obs-id | Inputs (mapping) |
| --- | --- | --- |
| ADR-0004 bullet 1: a constant into a context or queue function is one context with one value | `obs-lift-reuse` | `l4.xml` (lift-4) |
| ADR-0004 bullet 2: an absent parent gives zero contexts | `obs-target-creation` | `d1.xml`, `d2.xml`, `d3.xml` (create-d) |
| ADR-0006 bullet 1: an empty or missing context in a `Lift` input gives an empty result context | `obs-lift-reuse` | `l1.xml` (lift-1) |
| ADR-0006 bullet 2: SUPPRESS in a `Lift` input gives SUPPRESS and consumes the other inputs | `obs-lift-reuse` | `l2.xml` (lift-2), `l3.xml` (lift-3) |
| ADR-0006 bullet 3: SUPPRESS does not count toward `minOccurs` / `maxOccurs`; truncated slots keep their numbers | `obs-target-creation` | `a1.xml`, `a2.xml`, `a3.xml` (create-a) |
| ADR-0006 bullet 4: constant-only graph on a target is a generating function, children `(p - 1) * copies + j` | `obs-target-creation` | `b1.xml`, `b2.xml` (create-b) |
| ADR-0006 bullet 5: an unmapped target leaf with `minOccurs > 0` is a design-time error | `obs-target-creation` | `c1.xml` (create-c) |
| ADR-0008 bullet 1: a branch of a choice counts as optional (unmapped rules, `po:MINOCCURS`) | `obs-structures` | `ch1.xml`, `ch2.xml`, `ch3.xml` (str-choice-1, str-choice-2) |
| ADR-0008 bullet 2: `xsi:nil` and JSON `null` read as the empty string | `obs-structures` | `nil.xml`, `nil.json` (str-nil) |
| #2 §7.2: target creation, fewer child contexts than parent occurrences, SUPPRESS against `minOccurs` | `obs-target-creation` | `a1.xml`, `a2.xml`, `a3.xml`, `b1.xml`, `b2.xml`, `d1.xml` |
| #2 §7.3: last-value reuse, empty context, SUPPRESS as the last value | `obs-lift-reuse` | `l1.xml`, `l3.xml` |
| #2 §7.4: SUPPRESS propagation (`concat`, `toUpperCase`, `removeContexts`, `collapseContexts`) | `obs-lift-reuse`, `obs-context-functions` | `l2.xml`; `f6.xml` (ctx-6a, ctx-6b) |
| #2 §7.6: empty contexts (`removeContexts`, `mapWithDefault` on SUPPRESS-only, `SplitByValue` tag without a value) | `obs-context-functions` | `f1.xml`, `f2.xml`, `f3.xml`, `f5.xml` (o4) |
| #2 §7.7: `exists` per context or per value | `obs-context-functions` | `f4.xml` (ctx-4) |
| #2 §7.10: `useOneAsMany` count mismatches and empty contexts | `obs-useoneasmany` | `u1.xml` to `u4.xml` |
| #2 §7.11: `ifWithoutElse` "Keep SUPPRESS Values" on PO 7.5 | `obs-ifwithoutelse` | `i1.xml`, `i2.xml` (also `l2.xml`, `ctx-2` use the SUPPRESS choice) |
| #2 §7.16: `collapseContexts` first or last value | `obs-context-functions` | `f5.xml` (ctx-5) |
| ADR-0010 (via #14): is a box shared by two target fields evaluated once (`counter`) | `obs-shared-box` | `s1.xml` (shared-1, shared-2) |
| ADR-0005: PO's result for `1/3` | `obs-division` | `d1.xml`, `d2.xml` (div-1) |

Not covered here, by design: #2 §7.14 `XSI_NIL`; #2 §7.15 and a shared box with UDF state
(ADR-0020, a later spec); `po-owner-local` (spec 6). #2 §7.4 is covered for the functions named
above only; `sort`, `sortByKey`, `sum`, `count` and the rest of the catalogue stay open.

## Spec 2 cases (#61)

Every corpus case with `provenance: assumed` is named in a `request.md` (section "Spec 2 cases"),
with its mapping drawn as a box table and its input beside it; `ObservationRequestsCoverAssumedCasesTest`
fails if one is missing or its input is not in the request's directory. Inputs named
`<function>-<nnn>.xml` are copies of a case's `input.xml`.
