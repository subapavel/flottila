# Flottila Mapping

Turns a source message into a target message by a mapping that a person draws in the editor or
writes as XSLT, compiled to one intermediate representation and run on the JVM.

Seed glossary from the founding conversation (2026-10-30). `/domain-modeling` sharpens it; a term
marked _(open)_ is waiting for a Phase 0 decision.

## Mapping

**Mapping**:
A named, versioned transformation from one source structure to one target structure.
_Avoid_: map, transformation, message mapping (except when quoting SAP PO)

**Workspace**:
The directory, usually a git working tree, whose mapping models the service lists, opens and
saves. A mapping's id is its path in the workspace without the file suffix (ADR-0014).
_Avoid_: project, repository (for the directory the service serves)

**Mapping model**:
The stored, declarative form of a graphical mapping, the one the editor reads and writes and the
compiler reads: one file that holds everything deciding the mapping's result and refers to its
schema bundles and UDF sources. It is data, never generated code.
_Avoid_: mapping file, mapping definition

**Canonical form**:
The one way the writer spells a mapping model. Diffs and round trips compare it.
_Avoid_: pretty print, normalised form

**Format version**:
The version of the mapping model's own format. A newer one is refused, and an older one is
migrated on load.
_Avoid_: schema version, model version

**Refresh**:
Re-reading a mapping's schema bundles to take a changed structure: the fingerprint is
re-derived and the orphaned references are listed.
_Avoid_: re-import (for an unchanged mapping), sync

**Structure**:
The tree of fields a message may have, with names, types and cardinalities, read from an XSD,
WSDL, JSON Schema or similar.
_Avoid_: schema (for the in-memory tree), message type, data type

**Schema bundle**:
The schema files a structure was read from, which the mapping refers to together with the chosen
root: files in the same repository, or in a shared package at a fixed version.
_Avoid_: schema set, schema snapshot

**Shared package**:
A versioned package of artefacts (schemas, UDF sources) that several mappings refer to at a fixed
version.
_Avoid_: library (for the package itself), common folder

**Orphaned reference**:
A field path in a mapping that the mapping's current structure no longer has.
_Avoid_: broken link, dangling field

**Root selector**:
The choice of a structure's root inside its schema bundle: an XML root element, a WSDL
operation with its direction (and fault), or the JSON root.
_Avoid_: entry point, root element (for WSDL and JSON)

**Fingerprint**:
A digest of exactly what the compiler reads from a structure, stored in the mapping. A mismatch
at compile means the structure changed since the last refresh.
_Avoid_: checksum, schema hash, version

**Package reference**:
A reference to a file in a shared package at a fixed version, `name@version:/path`. Only a host's
reference resolver can read it.
_Avoid_: external reference, URL

**Reference resolver**:
The host's port through which the engine reads every file a mapping or schema refers to. The
engine reads no file in any other way.
_Avoid_: loader, file reader, schema catalog

**Refused field**:
A field that loads and shows in the tree but that a mapping may not use, such as a same-step
sibling or an abstract target element. It carries its refusal: the reason and the position of
the declaration that causes it. A field below it is refused with it.
_Avoid_: unsupported field (the code for a target field the walk cannot create), invalid field

**Source structure** / **Target structure**:
The structure a mapping reads from / writes to.

**Field**:
A node of a structure: an element, an attribute or a JSON property.
_Avoid_: node (reserved for XDM), element (too narrow)

**Array property**:
A JSON property declared as an array: one field whose occurrences are the items of its array,
whatever its `maxItems`. A value that is no array, `null` included, reads as absent (ADR-0008).
_Avoid_: repeated property, list field

**Structure field** / **Leaf field**:
A field with child fields and no value of its own, whose queue holds one empty string per
occurrence / a field with a value, whose queue holds that value. An element with a simple value
and attributes is a leaf field; its attributes are fields of their own.
_Avoid_: node, segment, complex element

**Effective occurrence**:
A field's minimum and maximum number of occurrences under one occurrence of its parent field,
after every enclosing sequence, choice and all is taken into account; a branch of a choice has
minimum 0.
_Avoid_: cardinality (for the declared value)

**Field path**:
The identity of a field: the name and namespace of every field from the root down to it, with
attributes marked. A mapping refers to source and target fields only by their field paths.
_Avoid_: XPath, field id, node path

**Target field mapping**:
The function graph that produces the values of one target field.
_Avoid_: rule, assignment, line

**Function graph**:
The acyclic graph of source-field boxes and function boxes wired into one target field mapping;
one box's output may feed several inputs.
_Avoid_: expression (reserved for XPath), formula, tree

**Function box**:
One call in a function graph (a standard function, a UDF, a constant or an unresolved function)
with its inputs and box parameters.
_Avoid_: node, brick

**Source-field box**:
The box of a function graph that reads one source field's queue in one context.
_Avoid_: source node

**Box parameter**:
A constant setting of a function box (a string or a table of strings), named as in the function
catalogue, such as a date pattern or the mode of `splitByValue`. A parameter the box leaves
out takes the catalogue's default.
_Avoid_: argument, input

**Unresolved function**:
A function box whose function is neither in the function catalogue nor among the mapping's UDFs.
It keeps the original name, its inputs and its parameters, and compiling it is a design-time
error.
_Avoid_: unknown function, missing function

**Layout**:
The integer positions of one target field mapping's boxes and its target, stored as `layout` in
the mapping file and ignored by the engine (ADR-0029). A file without one is laid out with dagre
in memory. Zoom, collapse and split stay the viewer's own state.
_Avoid_: diagram, view data

**Box position**:
One box's (or the target's) integer x and y in a layout. A position whose box no longer exists is
dropped on save.
_Avoid_: coordinates (alone)

**Mapping description**:
A mapping's free text, at most 4,000 characters, which the engine ignores.
_Avoid_: comment

**Signature**:
A mapping's source and target structures. A new mapping has none until the Signature tab chooses them.
_Avoid_: interface

**Structure overview**:
The top half of the editor. The source tree and the target tree stand side by side, with field
links drawn between them.
_Avoid_: mapping overview, tree view

**Field link**:
The line in the structure overview from a source field to a target field whose target field
mapping reads it. A pair of fields has one link, however many source-field boxes stand behind it.
_Avoid_: edge (reserved for the function graph), connection, line

**Mapping studio**:
The UI where a mapping opens (ADR-0028): a masthead, the tabs Overview, Definition, Test,
Signature and Functions, a read-only view mode and an edit mode. The start page lists the mappings.
_Avoid_: editor (for the whole UI), IDE, designer

**Mapping package**:
The workspace folder of a mapping's id, shown as Package in the studio. It is not a shared package.
_Avoid_: namespace, SWCV

**View mode**:
How a mapping opens in the studio: read-only. Edit switches to **edit mode**; Save or Discard ends it.
_Avoid_: lock, checkout

**Preview feature**:
A studio feature the engine does not support yet, working only in the browser and never written
to the mapping file (ADR-0028). It carries a "Preview" badge.
_Avoid_: fake, stub (those are test doubles)

**Mapping expression**:
The Definition tab's panel with the canvas of the selected target field mapping's function graph,
the function drilldown and the tool strip. It replaces the function editor (ADR-0028).
_Avoid_: function editor (superseded), data-flow editor

**Function editor**:
Superseded by the mapping expression (ADR-0028). The bottom half of the old editor. It holds the canvas with the function graph of the selected
target field mapping, the palette, quick insert and the inspector.
_Avoid_: data-flow editor

**Inspector**:
The panel of the function editor that shows the selected box: its catalogue documentation (with
a deviation's note, ADR-0028), its box parameters, its context and its display queue.
_Avoid_: properties panel

**Palette**:
The narrow list of the function editor that offers every function of the function catalogue,
grouped by function family, with its portability marks. Fidelity is not shown; a deviation shows
its note in the tooltip only (ADR-0028).
_Avoid_: toolbox, function list

**Quick insert**:
Adding a function box by typing its name on the canvas (or `/`), without the palette.
_Avoid_: command palette, autocomplete

**Field status**:
The mark of a target row in the structure overview: mapped, unmapped mandatory, orphaned or error.
The status filter shows the rows of one status. Unmapped mandatory and error come from the last
compile; mapped and orphaned are derived from the working copy.
_Avoid_: field state, validity

**Orphaned target field mapping**:
A target field mapping whose target field path the current target structure no longer has. It
has no row in the target tree, so the structure overview lists it in a group of its own.
_Avoid_: dangling mapping

**Remap**:
Pointing an orphaned source-field box at another source field, keeping its authoring id and its
wiring.
_Avoid_: relink, re-import

**Diagnostics panel**:
The editor's list of the latest diagnostics of the working copy, from compile, refresh or a refused
save, each leading to the box, row or schema position it names.
_Avoid_: problems panel (a problem is an HTTP error), error list

**Stale trace**:
The trace of the last test run after the working copy has changed. The display queue still shows
it, marked as stale, until the next test run.
_Avoid_: old run, cached trace

**Save conflict**:
A save refused because the mapping file changed on disk since the editor loaded it (409
`mapping-changed`, ADR-0027). The author reloads or keeps editing; nothing is overwritten.
_Avoid_: merge conflict (that is git's), lock

**Working copy**:
The mapping model as the author is editing it in the editor, saved or not. Compile and test run
on it.
_Avoid_: draft, local model

**Baseline**:
The mapping model as the service last saved it, in canonical form. The working copy has unsaved
changes when it differs from the baseline.
_Avoid_: original, server copy

**Unreachable box**:
A function box or source-field box that the target field mapping's `result` does not reach. It
is kept on save and dimmed on the canvas, and compiling ignores it with a warning (ADR-0013).
_Avoid_: dead box, orphaned box (orphaned is for references)

**Dev host**:
The thin standalone page that opens one mapping in the editor for development and tests, before
Flottila embeds the editor (ADR-0013).
_Avoid_: app, shell (reserved for Flottila's)

**Standard function**:
A built-in function of the graphical editor (concat, if, formatDate, removeContexts …).
_Avoid_: built-in, node function

**User-defined function (UDF)**:
A static Java method a mapping author writes in a source file of the workspace and declares in
the mapping, with one execution kind (ADR-0020).
_Avoid_: custom function, extension (reserved for XSLT extension functions)

**UDF library**:
The UDF source files of one mapping that share a source root and are compiled and loaded
together. Two versions of a UDF are two libraries.
_Avoid_: UDF jar, UDF package (a shared package is something else)

**UDF declaration**:
A mapping's entry in `udfs` that names a UDF, its execution kind, inputs, box parameters and
source method. Compiling checks it against the method.
_Avoid_: UDF signature (that is the Java method's)

**Mapping run**:
One transformation of one input message by a compiled mapping. The state a UDF keeps lives for
one mapping run and is shared by all its UDFs.
_Avoid_: global container, session

**Allow-list**:
The Java APIs a UDF may call. A call outside it is a design-time error.
_Avoid_: sandbox, security manager

**Execution kind**:
What one call of a standard function or UDF sees: _single value_ (one value at a time),
_context_ (all values of one context) or _queue_ (all values of the queue). A single-value
function never sees SUPPRESS: its `Lift` passes a SUPPRESS slot on without a call (F-45).
_Avoid_: function type, UDF type, cache (SAP PO's UI word)

**Context function**:
A standard function of execution kind _context_ or _queue_: one that sees whole contexts and may
change their boundaries or the number of values (removeContexts, splitByValue, sort, sum …), or
one that reads or produces SUPPRESS (createIf, replaceValue, F-45). SAP PO files most of them
under "Node functions".
_Avoid_: node function

**Function catalogue**:
The data that lists every standard function: its camelCase name, its function family, execution
kind, parameters, whether it is portable, and its fidelity. The editor's palette, the function
documentation and migration tools read it. Engine-internal from spec 2 (`function-catalogue.json`
in `engine/mapping`, ADR-0025), published in spec 3.
_Avoid_: function registry, function list

**Function family**:
The standard functions of one SAP PO palette group (context, text, arithmetic, date …): one file
per engine module, found by the build, never by a hand-written list (ADR-0025, F-15).
_Avoid_: category, group

**Runtime error**:
An error that stops a mapping run, named by a QName: from the input data (`po:MINOCCURS`, a
context function's count mismatch) or from a test run's limit (`flottila:TIMEOUT`). The output is
dropped; a test run returns the code, the message and the authoring id.
_Avoid_: exception, failure, diagnostic

**Runtime library**:
The engine's context and queue functions: the algebra over queue blocks in `engine/xdm`, bound by
catalogue name at compile in `engine/runtime` (ADR-0025). The helper module is its export
counterpart.
_Avoid_: function registry

**Fidelity**:
How a standard function relates to SAP PO/CPI behaviour: _exact_, _deviation_ (a documented
difference, with a short text) or _assumed_ (an assumed reading waiting for an observed case). A
function is assumed while one of its corpus cases is (ADR-0026). The studio shows no fidelity
mark, only a deviation's note in the tooltip (ADR-0028).
_Avoid_: compatibility level

**Portable function**:
A function whose exported XSLT 3.0 runs on any conforming processor without Flottila's own Java
functions. Only portable functions are covered by the differential oracle.

**Export profile**:
The target of an XSLT export: _portable_ (pure XSLT 3.0) or, later, _flottila_ (may call
Flottila's Java functions).

## Queues and contexts

**Queue**:
The values a function graph sees for one source field, split into contexts.
_Avoid_: list, stream, array (for the logical concept)

**Context**:
One segment of a queue: the values under one occurrence of the context field. The context field
is the parent field by default (an attribute's own element) and can be set to any ancestor.
_Avoid_: group, partition

**Context change**:
The boundary between two contexts of a queue.

**Slot**:
One position of a queue: a value or SUPPRESS. Target occurrences and child contexts are counted
in slots.
_Avoid_: entry, element, item (reserved for XDM); empty slot (say empty context, or a slot holding `""`)

**Empty context**:
A context with no slots, as under a parent occurrence without children. A source queue whose
context element never occurs in the message is one empty context (F-17). A queue with no contexts
at all (`{0}`) is a different thing; only a function result can be one.

**SUPPRESS**:
The marker for a value that is present as a slot but produces no target field. In a queue block,
a set bit with a null value; in the export, `$po:SUPPRESS`.
_Avoid_: null, empty (both are different things)

**Slot variable**:
The variable from which a `Lift` body reads one input's value at the current slot, `in1` … `inN`.
_Avoid_: argument, parameter; never the `TargetSlots` slot-number variable (that is the slot-number variable)

**Slot-number variable**:
The variable of a `TargetSlots` (`slotVar`) that holds the slot number of the occurrence being
written; its children read their queues under it as their parent slot. Its sibling `valueVar`
holds that slot's value.
_Avoid_: slot variable (that is a `Lift` input)

**Parent slot**:
The slot number of the parent occurrence under which a target field's slots are read.
_Avoid_: parent index

**Slot number**:
The position of a slot counted across all contexts of a queue, starting at 1. A target
occurrence's slot number is the index of the context its children read.
_Avoid_: index, position (both are per context)

**Slot index**:
A slot number minus 1, so it counts from 0. Only code uses it, as an array offset into a queue
block; tickets, tests and diagnostics use the slot number.
_Avoid_: index alone

**Generating function**:
A function graph whose result is one value for every occurrence of its target field: a graph with
no source field whose result is a single value, such as a constant or `concat` of two constants
(one that yields a queue, such as `count` of a constant, is a queue, F-21), or a graph whose result
is a generating block (F-46). On a target field it gives `max(1, minOccurs)` occurrences per parent
occurrence. A single value is computed once per occurrence; on a structure field it is traced,
never written. A generating block is read once per run, and when its value is SUPPRESS or missing
no occurrence is written.
_Avoid_: constant (for the general case)

**Generating block**:
The result of a queue function the function catalogue marks `generating`, today `copyValue`: a
queue of one context whose first slot is the value every occurrence of the target field takes,
under every parent occurrence, as a constant's value. At a context or queue function's input it is
that queue; a single-value function's input cannot take it (F-46, ADR-0006).
_Avoid_: generating value, constant queue

**Test run**:
Running a mapping's working copy on one input message in the editor. It returns the target
message or a runtime error, the diagnostics and the trace (ADR-0015).
_Avoid_: simulation, preview

**Trace**:
What a test run records: the queue or value each evaluated box produced, by authoring id, and
the occurrences of each target field per parent slot: each slot created, suppressed or truncated,
the count created, and the field's min and max. The display queue shows it.
_Avoid_: log, debug output

**Trace limit**:
The most slots, contexts or occurrence records one trace entry holds, 10,000 by default and
configurable. An entry above it is _capped_ and keeps its totals.
_Avoid_: truncated (a slot cut by `maxOccurs`)

**Truncated slot**:
A slot that comes after `maxOccurs` occurrences were created in its context. It creates nothing
and keeps its slot number, so it still consumes its child context.
_Avoid_: dropped value

**Diagnostic catalogue**:
The stable codes of design-time errors and warnings, declared as constants in one
`<Module>Diagnostics` class per engine module.
_Avoid_: error list, message catalogue

**Diagnostic**:
A design-time error or warning, with a stable code and one location: an authoring id, a JSON
pointer into the mapping model, a field path, or a schema file position (ADR-0015).
_Avoid_: validation message, problem (reserved for HTTP problem details)

**Display queue**:
The view that shows the queue (or single value) one function box, source field or target field
produces, with its contexts and SUPPRESS markers, for a given test input. For a target field it
also shows the occurrences created per parent slot, and why a slot created none (ADR-0012).

## Engine

**XDM**:
The logical data model shared by every front end: items, sequences, nodes, atomic values, maps,
arrays (XPath 3.1 data model).

**Runtime representation**:
A physical form of an XDM value chosen by the compiler (tiny tree, queue block, projected buffer,
persistent map). Several exist; the XDM contract is one.
_Avoid_: implementation (too vague)

**Queue block**:
The runtime representation of a queue: its values, where each context starts, and which slots
are SUPPRESS.
_Avoid_: queue (for the physical form), result list

**IR**:
The front-end-independent intermediate representation every mapping and stylesheet compiles to,
and the only input of the optimiser and the back ends.
_Avoid_: AST (that is a front end's own tree), bytecode

**Lift**:
Applying a single-value function to queues slot by slot: contexts are paired by position,
shorter inputs reuse their last value inside a context, and SUPPRESS passes through.
_Avoid_: map, broadcast, loop

**Authoring id**:
The stable id of the authoring element (target field mapping, function box, source-field box,
later an XSLT instruction) that a piece of compiled mapping came from. Tracing, the display
queue and design-time errors refer to it.
_Avoid_: node id, element id

**Front end**:
A compiler stage that turns one authoring form (mapping model, XSLT, XPath) into IR.

**Back end**:
A stage that runs IR: the interpreter, the bytecode tier, the XSLT exporter.

**Tier**:
One of the back ends that execute a mapping: tier 0 is the interpreter, tier 1 the bytecode tier.
Every tier gives byte-identical output.
_Avoid_: engine, mode

**Interpreter**:
Tier 0. It runs a compiled mapping as a tree of executable nodes built once from the IR, over
frames with slots. It is milestone 1's only tier and always the editor's.
_Avoid_: evaluator, tree walker

**Bytecode tier**:
Tier 1. It generates one hidden JVM class per mapping from the IR and unloads it with the
mapping. It comes after milestone 1.
_Avoid_: JIT, code generator (alone), compiled mode

**Traced compile**:
A compile of a mapping that reports every box's value to a tracer for the display queue. An
untraced compile has no trace hooks at all.
_Avoid_: debug mode, instrumented run

**Projection**:
Reading only the source fields a compiled mapping uses and skipping the rest of the input.

**Engine module**:
A JPMS module of the engine, tested on the module path. The service is not one; it uses the
engine through the engine facade.
_Avoid_: library, package (a Java package is something else)

**Engine facade**:
The one engine module the service calls to compile, test run and export a mapping. It carries no
compatibility promise and is not the embedding API for Flottila.
_Avoid_: engine API (sounds public and stable), SDK

**Contract**:
The files the service and the editor must agree on: the mapping-model schema, the
function-catalogue schema and the committed OpenAPI document. Both sides' types are generated
from or checked against them.
_Avoid_: API spec, interface definition

**Workspace path**:
An id, base directory or bundle reference that matches its pattern and whose real path lies
inside the workspace (ADR-0024).
_Avoid_: safe path, sandboxed path

**Workspace escape**:
A path that would resolve outside the workspace: `..`, an absolute path, or a symlink pointing
out. The service's reference resolver refuses it before any read.
_Avoid_: jailbreak

**Problem**:
The RFC 9457 `application/problem+json` body of a transport error, with a `type` from the
service's problem types. Diagnostics are a result; a problem is a failed request.
_Avoid_: error response, API error

## Verification

**Oracle**:
An independent source of the expected output for a case: the W3C test suites, Saxon-HE running
the exported XSLT, or the golden corpus.

**Differential oracle**:
Running a corpus case's mapping both in our runtime and, exported to XSLT, in Saxon-HE, and
requiring the same output. It applies to every case whose functions are all portable.
_Avoid_: differential case (it is a second oracle over the same cases, not a kind of case)

**Helper module**:
The XSLT module `po-runtime.xsl` that every portable export imports. It holds the PO queue
semantics as `po:` functions in `urn:flottila:mapping:po` (`po:slots`, later `po:lift`,
`po:at-least`, `po:show`). The build joins `core.xsl` and one module per function family into it
(F-15).
_Avoid_: runtime library (that is the engine's), helper stylesheet

**Golden corpus**:
Synthetic mappings with inputs and the outputs SAP PO semantics demand, kept in the repo.
_Avoid_: test data, samples

**Corpus case**:
One input of one synthetic mapping with its expectation (an output, an error, or none), named by
a permanent case id such as `fn/removeContexts/001`.

**Provenance**:
Where a corpus case's expected output comes from: observed, documented (the SAP documentation),
assumed (an assumed reading) or decided (our own rule or a deviation).

**Observed case**:
A golden-corpus case whose expected output was recorded by running the same synthetic mapping
on SAP PO 7.5 and SAP Cloud Integration. When the two differ, PO 7.5's output is expected and
the difference is a deviation.

**Observation request**:
An owner issue describing a synthetic mapping and its inputs to run on PO 7.5 and CPI, and the
question each input decides.

**Metamorphic variant**:
Another way of running the same corpus case that must give a byte-identical output: optimised or
unoptimised IR, with or without a tracer, and from the bytecode tier on, interpreter against
bytecode tier (the fifth variant).

**Assumed reading**:
Our interpretation of a PO behaviour the SAP documentation leaves open, used until an observed
case confirms or corrects it.
_Avoid_: default, guess

**Ratchet**:
A committed record of what passes (oracle cases, coverage, mutation scores, benchmark
baselines). Merges into `main` may only keep or raise it; the verifier regenerates it once per
spec, nobody edits it by hand.

**Floor**:
A fixed minimum of coverage or mutation score below which the build fails, whatever the ratchet
says.

**Benchmark scenario**:
One measured quantity of one benchmark mapping on one message size, such as the latency of
`orders` on a small message. It is named `<mapping>/<size>/<metric>`, and its baseline is a line
of the ratchet.
_Avoid_: benchmark (alone), test case

**Speed target**:
An absolute bound a benchmark scenario must meet at the milestone gate, independent of its
baseline.
_Avoid_: SLA, budget

## Way of working

**Wayfinder map**:
A GitHub issue (`wayfinder:map`) indexing the decisions that lead to a destination, with one child
issue per decision ticket.

**Spec**:
A GitHub issue (`spec`) describing one deliverable from the user's side, parent of its tickets.

**Ticket**:
A tracer-bullet slice of a spec, sized for one fresh context, with blocking edges and one class.

**Seam**:
An outer interface of a part of the system where a test gives input and checks output without
knowing the inside. A spec names its seams before its tickets exist.
_Avoid_: test boundary, integration point

**Frontier**:
The open, unblocked, unclaimed tickets of a spec or map.

**Integration branch**:
`spec/<issue>-<slug>`, where a spec's tickets are merged one by one before one PR to `main`.

**Ticket gate**:
The automatic checks the verifier runs on one ticket's worktree before it merges: the scoped
build of the touched modules, a compile of the whole build, and the ratchet check.
_Avoid_: CI (that runs the spec gate's checks), pre-merge review

**Spec gate**:
The full checks the verifier runs on an integration branch before the spec's PR: every build,
test, oracle and UI suite, the ratchet regeneration and the benchmarks.
_Avoid_: full build (only one part of it)

**Walking skeleton**:
The first spec of a milestone: the build skeleton plus the thinnest path through every layer
(structure, mapping model, compile, run, service, editor, oracle, ratchet), which later specs widen.
_Avoid_: prototype (throwaway, never merged), MVP

**Gate**:
A milestone check list in `docs/GATE.md`, verified by the gate-reviewer on one commit.

**Finding**:
A written contradiction between practice and an ADR or the architecture, with the smallest
change that keeps the documented intent.
