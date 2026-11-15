# Standard functions bind by name: an engine catalogue as data, a total FunCall, po:<name> in the helper module

Status: accepted (2026-11-02, #50 design, spec #48); amended by F-48 (#219): useOneAsMany's error conditions are the observed ones of PO 7.5 and CPI

## Decision

- **Catalogue as data.**
  - `function-catalogue.json` is a resource of `engine/mapping`, in canonical form (ADR-0011). It lists the standard functions by name, with these members:
    - `executionKind`: `single`, `context` or `queue`;
    - `inputs`: `[{name}]`;
    - `params`: `[{name, type, default, values?}]`;
    - `portable`.
  - Inputs and params are arrays, because their order is the signature.
  - Every param has a default.
  - Spec 3 adds ADR-0011's other members (the PO/CPI names, fidelity and the like) and the schema, and publishes it. Spec 3 extends the file; it never rewrites it.
- **Only lowering reads the catalogue.**
  - A context or queue function becomes `FunCall(FunctionRef(name, paramTypes, resultType), args, origin)`. The args are the inputs in catalogue order, then every param in catalogue order as a `Literal`.
  - Lowering fills in defaults, so the IR is total. `IrVerifier` checks arity, arg types and that params are literals.
  - The back ends bind by name only.
- **Runtime library.**
  - The algebra over `QueueBlock` lives in `engine/xdm` (`ContextFunctions`). This is where ADR-0004's sharing rules hold.
  - `engine/runtime` binds a name once per call site at closure compile and parses the params into the closure. This refines ADR-0021's "queue-operation library": the binding is in `runtime`, the algebra in `xdm`.
  - The bytecode tier calls the same binding.
- **Export.**
  - A queue is `array(item()*)`, one member per context; a value is an `xs:string` or `$po:SUPPRESS`, ADR-0004's marker (since #51).
  - A `Lift` exports as `po:lift((queues), function($in as xs:string*) as xs:string {…})`. The runtime binds a single-value function by name to a string node.
  - A `FunCall` exports as `po:<catalogue name>(args…)`, with one `xsl:function` per function in `po-runtime.xsl`, taking its args in `FunCall` order.
  - Renaming a function breaks exports already handed out.
- **Stage-3 codes at the box** (added to ADR-0011):
  - `wrong-arity`
  - `unconnected-input`
  - `unknown-parameter`
  - `wrong-parameter-type`
  - `invalid-parameter-value`

  A function name the catalogue does not hold stays `unsupported-box-kind` until its spec lands. `unresolved-function` remains the code of an unresolved box.

- **Families (F-15, owner 2026-11-03, spec-3 grilling Q14).**
  - A function family is the standard functions of one SAP PO palette group. Each family has one
    file per module: `catalogue/<family>.json` (mapping), `<Family>Family.java` (runtime: name →
    binder), `po-runtime/<family>.xsl` (xslt-export), and `<Family>Functions.java` (xdm) when it
    has queue algebra.
  - The build scans these directories and writes `function-catalogue.json` (canonical key order),
    `po-runtime.xsl` (`core.xsl`, then the families by id) and the runtime's family index. A name
    in two families fails the build. Nothing hand-written lists the families.
  - The merged catalogue gives each entry its `family` (Q16). Artifact names, their bytes for the
    same entries (until #71 adds `fidelity` and the box param, ADR-0026), the IR and ADR-0023's
    one-file export are unchanged.
  - Today's eleven functions form the family `context`. `RuntimeErrors` stays one shared,
    append-only file (Q15).
  - A family is the PO palette group: `ifWithoutElse` belongs to `boolean`, `formatNum` to
    `arithmetic`, `fixValues` to `conversions` (spec-3 review), whatever their execution kind. Two tickets of one family add whole entries to
    the same files; the merger resolves that as a mechanical adjacent edit.
  - A `<Family>Family` binds each name through a package-private method, not a lambda in a
    static initializer: PIT makes no mutants for the latter (#69).
  - Helpers that several families share go into `core.xsl`; it only grows, and the #69 byte test
    pins main's declarations, not the absence of new ones.
- **Names and fidelity (spec-3 grilling, owner 2026-11-03).**
  - A function id is camelCase (`constant`, `copyValue`, `fixValues`, `formatNum`, `dateTrans`,
    `equalsS`). The catalogue holds no PO spelling, which is the migration tool's business (Q8,
    Q8b).
  - Every entry has `fidelity`: `exact`, `deviation` (with a short text) or `assumed`. Spec 3
    adds `function-catalogue.schema.json` and `GET /api/catalogue` (Q2, Q3).
- **Data errors name their box (F-19, Q12).** A data error of a standard function carries the
  authoring id of the box that raised it, in the API and in the export's message. Each `po:`
  function that can raise one takes the box id as its last argument. A malformed number or date
  is `err:FORG0001` in both back ends (Q10).

## Considered options

- **ServiceLoader per family (F-15)**: rejected. Under JPMS one `provides … with A, B` lists every provider, so `module-info.java` becomes the shared list, and the providers must be public.
- **A hand-written family list**: rejected. Every new family would edit the same line, which is the conflict F-15 removes.
- **Catalogue as Java code in `engine/ir`**: rejected. Spec 3 would have to generate the published data from code, and the glossary calls the catalogue data.
- **The runtime reads the catalogue**: rejected. `runtime` cannot require `mapping` (ADR-0016), and a total IR makes it unnecessary.
- **Named params in `FunCall`**: rejected. ADR-0006 says `Literal` arguments, and one positional list also serves `Lift` bodies.
- **Params passed per call**: rejected. They would be parsed on every run, and the bytecode tier would lose constant literals.

## Consequences

- Each new function costs, in its family's files (F-15):
  - one catalogue entry;
  - one algebra method when the family has queue algebra;
  - one runtime binding entry;
  - one `po:` function;
  - its corpus and differential cases.
- In formatVersion 1, a box feeding several inputs is lowered once per use. There is no `Let` in spec 2, and the trace (#53) reports such a box once.
- The catalogue and `po-runtime.xsl` hold every family, against CODING_STANDARDS ("no central
  registry"). Spec 2 accepts that, because it adds one family; before spec 3 both split into one
  file per family (F-15).
- A context function's data error is one code, `po:<CATALOGUE NAME UPPERCASED>`, the same name
  in the runtime and the export; the message says which count failed (#52). It costs a
  `RuntimeErrors` constant and an error corpus case. xdm throws `QueueDataException` for data
  errors only; the binding rethrows it with the code at the box. A single-value cast error of a
  context function, such as `ifWithoutElse`, raises `err:FORG0001` and not `po:<NAME>` (F-44).
  useOneAsMany refuses unless
  input 1 has as many contexts as input 2 and one value, SUPPRESS included, in each, and then
  unless input 3 has as many values as input 2 (observed on PO 7.5 and CPI, F-48:
  `fn/useOneAsMany/003` to `009`; it replaces #34's assumed one value of input 1 per context of
  input 2, whatever input 1's contexts). PO's own messages, "Too many" or "Too few values in the
  first queue" and "No value found", are not copied: the code is the contract.
