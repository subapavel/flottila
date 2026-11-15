# Milestone 1 gate — "a graphical mapping runs, end to end"

Final, 2026-10-31 (Phase 0 map #1, grilling #19). Drafted 2026-10-30; the Phase 0 tickets set each
check. The gate-reviewer (`/gate`) verifies every check on one commit of `main`; each check names
the command and the evidence it needs. A change to a check after this point is a finding
(`docs/WORKFLOW.md` §7).

## Checks

1. **Import.** Two XSDs and one JSON Schema (draft 2020-12) from `corpus/structures/` load as
   source and as target structures; the editor shows the trees
   with types and effective occurrences. A mapping with a JSON source runs; compiling one with a
   JSON target is a design-time error in milestone 1 (ADR-0008). A schema construct ADR-0008
   refuses gives an error with file, line and column. The schemas are read from the service's
   workspace through `POST /api/structures`, and a load error is a diagnostic with a
   `{file, line, column}` location (ADR-0014, ADR-0015).
2. **Edit.** In the editor (ADR-0028, F-42) a
   person maps fields by drag and drop, inserts standard functions and constants, and saves;
   the mapping model passes its schema (ADR-0011) and round-trips byte-identical in canonical
   form (ADR-0010). Save writes `<id>.mapping.json` in the workspace and returns the canonical
   model; a save over a file changed on disk answers 409 (ADR-0014). Shown by the `@stack`
   Playwright suite against the service (ADR-0013).
3. **Run.** `POST /api/mappings/{id}/test` with an input returns the target message; the editor's
   test panel shows it. The display queue at any function shows contexts and SUPPRESS. At a
   target field it also shows the occurrences created per parent slot (ADR-0012). The test runs
   on the unsaved working copy; shown by the `@stack` Playwright suite (ADR-0013). A runtime
   error (`po:MINOCCURS`) returns its QName, the `AuthoringId` and the trace up to the error,
   and diagnostics carry their stable codes (ADR-0015). `openapi.json` and the generated
   `ui/src/generated/` types have an empty diff.
4. **Semantics.** The milestone-1 functions of issue #3 (ADR-0002, ADR-0003) pass their
   golden-corpus cases: all PO 7.5 node functions (`removeContexts`, `splitByValue` in all three
   modes, `collapseContexts`, `useOneAsMany`, `createIf`, `exists`, `mapWithDefault`,
   `formatByExample`, `sort`, `sortByKey`, `replaceValue`), statistics, arithmetic, `FormatNum`,
   text, boolean, `Constant`, `CopyValue`, `FixValues` and date functions. No shipped function has
   fidelity _assumed_; every _deviation_ is in the function catalogue and shown in the editor.
   Evidence (ADR-0018): every corpus case of these functions is in `ratchet/oracle/corpus.txt`
   on the gate commit, passing in all metamorphic variants, and none has `provenance: assumed`.
   The observation requests for the assumed readings of ADR-0004, ADR-0006, ADR-0008 and #2 §7
   are answered, and their cases are `observed`. Requests opened after spec #212 (#222) are
   deferred past milestone 1 by the owner (F-53).
5. **Export.** Every corpus mapping whose functions are all portable exports to XSLT 3.0
   (portable profile); the differential run (our runtime vs
   Saxon-HE 12.5 on the exported XSLT, XMLUnit identical) agrees on every corpus input: every
   such case id is in `ratchet/oracle/differential.txt` (ADR-0018).
6. **UDF.** Removed from milestone 1 (ADR-0030, F-33). User-defined functions depend on
   Flottila's data model and wait for it. A UDF box in a mapping stays a design-time error.
7. **Speed.** The seven JMH benchmarks of ADR-0021 run on the gate commit in spec-gate step 5
   on `claude-cloud-4vcpu`, in the closure-compiled interpreter (milestone 1 has no bytecode
   tier). They cover the `corpus/bench/` mappings `orders`, `wide` and `contexts`, end to end,
   and are recorded in `bench/RESULTS.md` with machine, date and commit. Targets:
   - every small message (8–10 kB) at ≤ 500 µs/op;
   - every large message (≥ 10 MB) at ≥ 20 MB/s, and it completes in a fork with `-Xmx128m`;
   - `compile/orders` at ≤ 5 ms/op warm.

   A scenario that regresses or misses its target in the first run runs twice more and is judged on the median of the three (ADR-0021, Notes of 2026-11-11).
   No scenario is worse than `ratchet/bench.tsv` beyond its tolerance (ADR-0019). The
   20,128-field tree scenario has a p95 frame time below 33.3 ms (no dropped frame at p95; F-26, 2026-11-10). The optional PO observation
   request (`po-owner-local`) is informative only.
8. **Quality.** The CI run of the gate commit is green in all three required jobs, `backend`,
   `ui` and `stack` (ADR-0017). That covers the build with `-Xlint:all -Werror`, Spotless, the
   enforcer bans, the licence check with only the per-artifact exceptions of ADR-0017, the
   ArchUnit rules A–C (ADR-0016) and an empty `contract/` diff. The spec gate
   (`scripts/gate-spec.sh`) passes on the VM. Floors on the gate commit (ADR-0019): JaCoCo lines
   ≥ 90 % and branches ≥ 80 % on `xdm`, `ir`, `runtime`, `serializer`, `mapping`, `xslt-export`,
   `api`, lines ≥ 80 % on `structure`, `structure-import`, `service` (CI `backend`), and PIT
   ≥ 80 % on the class-C modules (the nightly run of the gate commit). The ratchet is green
   (`java scripts/ratchet.java check` on the gate commit). The drift tests (ADR-0011, ADR-0015),
   the workspace-escape test (ADR-0014) and the diagnostic-catalogue test (ADR-0018) are green. Every spec of the milestone was merged through `/run-spec` with its review. `ui/` (ADR-0013): typecheck, lint with the import-boundary rules, Vitest
   (node and browser), Playwright e2e on the fake API and the `@stack` suite green; line
   coverage of `ui/src/model/` and `ui/src/studio/store/` ≥ 90 % each (per-glob Vitest
   thresholds, ADR-0019); generated types with an empty diff; the 20,128-field tree scenario within its
   check-7 target. No mutation testing on `ui/` in milestone 1.
9. **Hand-over.** `HANDOVER.md` covers:
   - starting the stack from a clean clone with only JDK 25 and Node 22 installed:
     `./mvnw -pl service -am quarkus:dev` with `npm run dev` for development, and the `-Pui` jar
     for one origin (ADR-0017). The Quarkus service runs over a workspace and is bound to
     `127.0.0.1` (ADR-0014);
   - how to draw and run a mapping;
   - how to run the ticket gate, the spec gate and the benchmarks (`-Pbench`);
   - open findings, ADRs to revisit and the next three specs ("After milestone 1" below, updated
     if a finding moved them).

   The verifier follows the start instructions on a fresh worktree of the gate commit, both
   development and `-Pui`, into `.logs/gate-9.log`; the gate-reviewer judges that log (F-41).

## Specs in build order

Decided in grilling #19; the editor moved ahead of speed by the owner on 2026-11-03. Milestone 1 is built as a **walking skeleton** first, then widened axis by
axis; each spec goes through `/grill-with-docs` → `/to-spec` → `/to-tickets` → `/run-spec` in
this order, and no spec issue exists before its `/to-spec`. Check 8 holds after every spec.
Every ticket that adds a function also adds its XSLT export (or `portable: false` in the
catalogue), its golden-corpus cases and, when portable, its differential cases; there is no
separate export spec.

| # | Spec | Scope | Proves |
| --- | --- | --- | --- |
| 1 | Walking skeleton | The build skeleton ticket first (ADR-0017 and the carry-overs on #19: modules with `module-info`, `engine/oracle`, `scripts/ratchet.java`, JaCoCo, PIT in `nightly.yml`, phrased-name reporter, Vitest thresholds, empty `ratchet/`, CI, gate scripts, branch protection; it measures the ticket-gate budget and the Playwright Chromium build). Then the thinnest path through every layer: one flat XSD as source and target, one field-to-field link without a function box (`formatVersion` 1), IR with `TargetSlots`, interpreter, serializer, `engine/api`, exporter for a direct copy; `POST /api/structures`, get/put mapping (no 409), `POST /api/mappings/{id}/test` (no trace); committed `openapi.json` and generated types; dev host, structure overview with both trees, one drag-and-drop link through a store command, save, test panel; one `decided` corpus case and one differential case in the ratchet; one `@stack` scenario. It opens the observation requests of ADR-0004, ADR-0006, ADR-0008 and #2 §7 as `ready-for-human` issues. | 8; first slices of 1, 2, 3, 5 |
| 2 | Queues, contexts and node functions | The nested XSD subset (anonymous complex types, any depth, `minOccurs`/`maxOccurs`, `elementFormDefault`; F-14). Queue block, `Lift`, full `TargetSlots` occurrence rules with SUPPRESS and `po:MINOCCURS`, the trace and display queue in the test response, every PO 7.5 node function with export and corpus. | 3 (API), 4 (node functions), 5 |
| 3 | Standard functions | Statistics, arithmetic (ADR-0005), text, boolean, `Constant`, `CopyValue`, `FixValues`, `FormatNum` and date over one F&O core (ADR-0003), the function catalogue with `portable` and `fidelity`. | 4, 5 |
| 4 | Structures in full | Full XSD (choice and effective occurrence, imports, refusals with `{file, line, column}`), WSDL 1.1/2.0, JSON Schema 2020-12 as source (JSON target a compile error), schema bundle and fingerprint, refresh and orphaned references. | 1 |
| 5 | Editor in full | Function editor (canvas, quick insert, inspector with fidelity, display queue with occurrences per parent slot), target-tree status filter, undo, 409 on save, diagnostics in the UI, the `ui/tree-20128/scroll` p95 scenario. | 2, 3 (UI), 7 (tree) |
| 6 | Speed | `corpus/bench/{orders,wide,contexts}` with seeded generators, the seven JMH benchmarks through `engine/api`, the bench rules in `scripts/ratchet.java`, spec-gate step 5; it opens the optional `po-owner-local` observation request (ADR-0021). Its last ticket writes `HANDOVER.md`. | 7 (engine), 9 |

Spec 7 "UDFs" left milestone 1 on 2026-11-10 (ADR-0030, F-33): UDFs depend on Flottila's data model.

Observation requests never block a spec. Specs 2 and 3 implement the assumed readings with
`provenance: assumed`; an answer turns the case `observed`, and a divergence from PO becomes a
finding and a fix ticket in the running spec, or the next spec touching that function. `/gate`
waits until every request named in check 4 is answered.

## After milestone 1

In this order (grilling #19): **JSON output** (constructor records, serializer and export, ADR-0008)
→ **bytecode tier** (`engine/bytecode` and the fifth metamorphic variant, ADR-0021) →
**`formatVersion` 2 and the PO importer** (ADR-0010). The XSLT front end follows them.

Waiting for Flottila's data model (ADR-0030), each starting with a grilling once the model exists:
UDFs (ADR-0020 is the starting point, #169), lookups (#165), parameters, headers, properties and
attachments (#167), several source or target messages (#168) and the test trace level (#171).

## Out of the gate

XSLT front end, XPath front end beyond what the exporter needs, PO import, streaming / burst
mode, Flottila integration, authentication, multi-user editing, shared-package references
(a design-time error until a host resolver exists), the transport export, an XSLT export
endpoint (ADR-0015).
