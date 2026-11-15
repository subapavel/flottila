# The portable export writes unoptimised IR as one stylesheet that imports po-runtime.xsl

Status: accepted (2026-11-02, #27 design); the portable profile needs higher-order functions since #51

- **Input.** `engine/xslt-export` writes the unoptimised IR (`TargetDocument`) of a mapping that lowered, as one
  XSLT 3.0 stylesheet. The IR is the only input any back end takes. Unoptimised IR keeps one node per box, which
  later lets the trace mode name each box.
- **Helper module.** The stylesheet imports the helper module `po-runtime.xsl` from its own directory, with
  `xsl:import`. The helper is a resource of `engine/xslt-export`.
- **Namespaces.**
  - The `po` namespace is `urn:flottila:mapping:po`, the same one as `po:MINOCCURS`.
  - Output prefixes are the IR's (ADR-0022), declared once on `xsl:stylesheet` under
    `exclude-result-prefixes="#all"`.
  - Source names use `s0`, `s1` … in IR walk order.
  - There is never a default namespace and never an `xpath-default-namespace`.
- **No partial export.** Refusals come from loading (stages 1–2) and from lowering (stage 3), before the exporter
  runs. So an export is never partial. An IR the exporter cannot write is a compiler bug
  (`IllegalArgumentException`), as in `IrVerifier`.
- **`engine/api` result.** `export` returns `ExportResult.Exported(stylesheet, helperModule)` or
  `ExportResult.Refused(diagnostics)`, sorted by `Diagnostic.ORDER`.
- **Spec 1 scope.** Spec 1's helper module holds `po:slots` only. `po:at-least`, `po:lift`, `po:show` and the
  trace mode come in spec 2.

## Considered options

- **The model as input**: rejected. It would repeat stage 3 and the prefix rule (ADR-0022).
- **Optimised IR**: rejected. It folds boxes away.
- **The helper inlined into every export**: rejected. ADR-0002 names a helper module.
- **`xsl:use-package`**: rejected. It needs a package library on every processor.
- **A character map or Saxon serializer properties for byte identity**: rejected. Bytes are not the verdict
  (ADR-0018).

## Consequences

- Every export carries the `po` URI and the helper's file name. Renaming either breaks exports already handed out.
- `TargetSlots` exports as `po:slots` alone until `min` is enforced in spec 2 (ADR-0006).
- Since #51 a portable export uses inline functions (`po:lift`). The portable profile therefore
  requires the XSLT 3.0 higher-order functions feature. The differential oracle runs every
  portable case on Saxon-HE, so a processor without the feature shows up there first.
