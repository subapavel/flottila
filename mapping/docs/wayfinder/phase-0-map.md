# Phase 0 map — draft for `/wayfinder`

Draft prepared 2026-10-30 from the founding conversation. `/wayfinder` (chart mode) reviews it
with the owner, then publishes the **Map** section as the `wayfinder:map` issue and the tickets as
its child issues, wiring the blocking edges in a second pass. After publishing, the issues are the
source of truth and this file is not updated again.

---

## Map: Phase 0 — decisions before milestone 1

### Destination

Milestone 1 (`docs/GATE.md`, "a graphical mapping runs, end to end") is ready to build: every
decision it stands on is an ADR or a resolution comment, `GLOSSARY.md` has no _(open)_ term the
milestone needs, `docs/GATE.md` is final, and the milestone is split into specs in build order.

### Notes

Agreed in the founding conversation (owner's statements, to be confirmed or recorded as ADRs by
the tickets that touch them — not decisions yet unless an ADR exists):

- Java 25 minimum; modern Java (records, sealed types, pattern switch, ScopedValue, Class-File
  API, virtual threads where I/O is involved).
- One compiler core with several front ends (graphical mapping first, XSLT 3.0 / XPath 3.1
  later) and several back ends (interpreter, bytecode, XSLT exporter) — the LLVM shape.
- One logical XDM; several physical runtime representations chosen by the compiler (tiny tree,
  queue block as struct-of-arrays, projected buffer, persistent maps). Generated hot-path code
  uses concrete representations, generic code the contract.
- Mapping model is declarative data, never generated source; versionable and diffable.
- Mapping speed target: at least SAP PO's compiled mappings — projection of the source,
  streamed target output, values as strings by default, compile at deploy, shape-specialised code.
- Every mapping exports to readable XSLT 3.0; the export doubles as the differential oracle.
- UI: React + PatternFly (it will also be Flottila's UI). Repo, packages: `io.flottila.mapping`,
  separate from Flottila until integration.
- Open source (Apache 2.0); synthetic corpora only; Saxon-HE at test scope only.
- Streaming later as burst mode, not full XSLT streamability.
- Way of working: ADR-0001; the environment is Claude Code Cloud (4 vCPU).

Skills every session uses here: `grilling` and `domain-modeling` for HITL tickets, `research` for
AFK tickets, `prototype` for UI questions; the `designer` agent drafts class-C-like shapes before a
grilling session so the owner reacts to a concrete proposal.

### Decisions so far

(empty)

### Not yet specified

- How the engine is embedded in Flottila (step SPI, deployment of mappings, monitoring hooks).
- Importing SAP PO message mappings from ESR exports; porting their UDFs.
- XSLT front end and the XPath engine beyond what the exporter needs; W3C conformance strategy.
- Burst-mode streaming for large files.
- Mapping lifecycle: versions, deployment, value mappings / lookups to external systems.
- Authentication, multi-user editing, collaboration on one mapping.

### Out of scope

- Branding and the final product name (owner, 2026-10-30).
- Writing production code: Phase 0 produces decisions, prototypes on throwaway branches, and the
  build skeleton only if a task ticket needs it to decide something.

---

## Tickets

Type label in brackets. `Blocked by` names other tickets of this map.

### 1. What exactly do SAP PO graphical mappings do with queues and contexts? [research]

Collect, from SAP's public documentation and other primary sources, the semantics of queues,
contexts, context changes and SUPPRESS; the full catalogue of standard and node functions with
their context behaviour (removeContexts, splitByValue, collapseContexts, useOneAsMany,
createIf, exists, mapWithDefault, formatByExample, sort, sortByKey, ifWithoutElse, …); UDF kinds
and their signatures; how target fields are created from queue values. Output: a cited note
`docs/research/po-mapping-semantics.md` with open ambiguities listed. Blocked by: none.

### 2. Which standard functions and node functions are in milestone 1? [grilling]

From ticket 1's catalogue: the families in scope, the ones deferred, and which behaviours must
match PO exactly versus where we may differ (and how a difference is shown to the user).
Blocked by: 1.

### 3. What are the XDM contract and the queue representation for milestone 1? [grilling]

The subset of XDM the mapping front end needs now and what is reserved for XSLT later; the
physical queue representation (values, context boundaries, SUPPRESS) and its operations; how a
queue meets a tiny-tree node and an atomic value. Designer draft first. Blocked by: 1.

### 4. What shape does the IR have, and how do queues lower into it? [grilling]

Sealed IR node families, typing, where context semantics live (IR operations vs runtime
library), how a function graph per target field becomes IR, and what XSLT will need later so
nothing here blocks it. Designer draft first. Blocked by: 3.

### 5. What is the mapping-model format? [grilling]

Serialisation (JSON or other), schema and its versioning, stable ids for diffs and review,
how source and target fields are referenced (paths vs ids), separation of semantics from editor
layout, where UDF source lives. Designer draft first. Blocked by: 1.

### 6. How do we read XSD, WSDL and JSON Schema in Java 25? [research]

Libraries (licence, maintenance, Java 25 compatibility) for reading XSD and WSDL into a usable
model (e.g. Xerces XSModel, Apache XmlSchema) and JSON Schema; known gaps (substitution groups,
redefines, recursion, imports across files). Output: a cited note. Blocked by: none.

### 7. What is the structure model? [grilling]

How XSD constructs (choice, sequence, all, recursion, abstract types, substitution groups,
namespaces, attributes, mixed content) and JSON Schema map onto one structure tree the editor and
compiler share; what milestone 1 refuses with a clear message. Blocked by: 6.

### 8. Which frontend stack and editor libraries fit React + PatternFly? [research]

Current PatternFly and React versions; libraries for a node-and-edge mapping canvas and large
virtualised trees (React Flow, PatternFly topology, others) with licences; how others draw
two-tree mapping editors. Output: a cited note. Blocked by: none.

### 9. What should the mapping editor look like? [prototype]

Three radically different UI variants on one throwaway route: (a) PO-style source tree, target
tree and a function canvas per target field; (b) one data-flow canvas for the whole mapping;
(c) a table-first editor with inline function chains. Includes the display queue and the test
panel. The owner picks or combines. Blocked by: 8.

### 10. What is the UI architecture? [grilling]

Build (Vite), routing, server-state layer, generated model types from the mapping-model schema,
component boundaries for the editor, e2e testing (Playwright) and how agents get browser feedback
in the cloud VM. Blocked by: 5, 9.

### 11. What does the backend service look like? [grilling]

HTTP framework (Vert.x 5 for Flottila alignment vs a minimal JDK server), endpoints for
structures, mappings, compile, test run and display queue, persistence of mappings for milestone 1
(files vs database). Blocked by: 5.

### 12. How is the repo laid out and built? [grilling]

Maven modules and their dependency rules (ArchUnit), where `ui/` lives and how it builds,
JDK 25 and Node on the cloud VM, CI jobs on GitHub Actions, the scoped commands the ticket gate
runs in seconds. Blocked by: 4, 10, 11.

### 13. How do the oracles and the ratchet work? [grilling]

Differential runner (our runtime vs Saxon-HE on exported XSLT), golden-corpus layout and case
ids, ratchet file formats and regeneration command, coverage and mutation floors for class-C
modules. Blocked by: 5, 12, 14.

### 14. Can PO queue semantics be exported to readable XSLT 3.0? [research]

Patterns for expressing contexts, context changes and SUPPRESS in XSLT 3.0 (grouping, maps,
arrays, `xsl:iterate`), which functions export cleanly and which need helper functions, and
whether the result stays readable. Output: a cited note with small worked examples.
Blocked by: 1.

### 15. How are UDFs written, compiled and isolated? [grilling]

Authoring (in the editor vs a jar), compilation (`javax.tools` in-process vs build time),
loading (hidden classes, class loaders), what a UDF may touch, and how UDFs export to XSLT
(extension function, or not exportable). Blocked by: 4.

### 16. Interpreter or bytecode first, and what speed must milestone 1 show? [grilling]

Whether milestone 1 ships the Class-File-API back end or the interpreter only; JMH scenarios and
the numbers gate check 7 needs; the machine label. Blocked by: 4.

### 17. Set up the GitHub repo and the cloud environment [task]

Owner: create the repo, push, run `scripts/bootstrap-github.sh`, protect `main`, create the Claude
Code Cloud environment (JDK 25 + Node, network allowlist incl. `mcp.context7.com`, Maven Central,
npm registry). Record what was done and any facts later tickets need. Blocked by: none.

### 18. Final gate and the milestone's specs [grilling]

Finalise `docs/GATE.md` (targets, floors) and split milestone 1 into specs in build order, each
ready for `/grill-with-docs` and `/to-spec`. Blocked by: 2, 7, 10, 12, 13, 15, 16.
