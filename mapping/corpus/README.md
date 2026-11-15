# Golden corpus

Synthetic structures, mappings, inputs and expected outputs. No customer data, no real partner
data, nothing extracted from a real system, ever (ADR-0002). Decided in ADR-0018.

## Layout

```
corpus/
  structures/                    schema bundles (XSD, WSDL, JSON Schema 2020-12)
  mappings/<name>.mapping.json   synthetic mapping models (ADR-0010), referencing ../structures/
  udfs/<package path>/<Class>.java  UDF sources (ADR-0020), referenced as ../udfs/…#method;
                                 optional <Class>.xsl with hand-written XSLT bodies
  bench/<mapping>/               JMH mappings (`orders`, `wide`, `contexts`) and seeded input
                                 generators; large inputs are generated, never committed (ADR-0021)
  cases/<area>/<topic>/<nnn>/    one case
    case.json                    {mapping, provenance, ref, expect, note}
    input.xml | input.json
    expected.xml | expected-error.json
  observations/<obs-id>/         the owner's runs on SAP PO 7.5 and SAP Cloud Integration
    po75/  cpi/  meta.json       outputs per input; releases, date, divergence
  w3c/suites.json                pinned W3C test suites (repo + commit), later milestones
```

## Cases

- **Case id** = the path under `cases/`, e.g. `fn/removeContexts/001`, `target/minoccurs/003`,
  `decimal/division/002`. Permanent: never renamed, deleted or reused. Tickets
  (`oracle:corpus`) and `ratchet/oracle/*.txt` cite it.
- **`expect`**: `output` (`expected.xml`), `error` (`expected-error.json`: error QName or
  diagnostic code, `authoringId`; a design-time error at a place in the model gives `pointer`
  instead, such as `unmapped-mandatory-leaf` at `/targetFieldMappings/~1Unmapped~1Head~1Req`,
  #65), or `none` (differential only).
- **`provenance`** with **`ref`**:
  - `observed`: PO 7.5 + CPI run, `ref` = obs-id;
  - `documented`: SAP documentation, `ref` = a `SAP✓` claim of #2;
  - `assumed`: an assumed reading, `ref` = ADR and bullet;
  - `decided`: our rule or a catalogue deviation, `ref` = ADR (e.g. ADR-0005).
- A function milestone 1 ships has no case with `provenance: assumed` (GATE check 4).
- Every case runs in `engine/oracle` against `expected.*` (XMLUnit similar, element-content
  whitespace ignored) in four metamorphic variants (optimised / unoptimised IR, with / without
  tracer). A case whose mapping is all portable also runs against Saxon-HE 12.5 on the exported
  XSLT (XMLUnit identical): the differential oracle.

## UDF cases

- UDF sources live in `udfs/`. The engine compiles them in-process, as in production; the
  reactor does not.
- UDFs are never portable, so their cases are golden-corpus cases. A case also runs in the
  differential oracle when an `<Class>.xsl` beside the source supplies XSLT bodies that override
  the export's `po:UDF-NOT-EXPORTED` stubs.
- Provenance is `assumed` until an observation request confirms it, otherwise `decided`
  (ADR-0020).
- The corpus keeps two witnesses: a deterministic UDF that the optimised variant folds, and a
  stateful UDF (`MappingRun`) whose output would change if its calls were reordered or merged.

## Observed cases

1. Architect: issue `owner: observation request <obs-id>` (`ready-for-human`) with the synthetic
   mapping as drawn in the ESR / CPI editor, its inputs and the question each input decides.
2. Owner: builds it on PO 7.5 and CPI locally, returns outputs or error texts, releases, date.
   Only the synthetic material enters `observations/<obs-id>/`.
3. A class-A `oracle:corpus` ticket sets the cases to `observed`. A contradiction with an assumed
   reading is a finding (`docs/WORKFLOW.md` §7). PO 7.5 wins over CPI; a divergence is recorded
   in `meta.json` and becomes a catalogue deviation.

## W3C suites

Never copied here. `w3c/suites.json` pins each suite; the runner fetches it into `target/`. Case
id `<suite>:<test-set>/<test-case>` (`qt3:fn-substring/fn-substring-1`).
