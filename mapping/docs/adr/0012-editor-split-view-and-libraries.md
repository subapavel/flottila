# The mapping editor is a split view: structure overview on top, function editor below

Status: accepted (2026-10-30, prototype #10)

FOUNDING §12 left the editor's shape to a prototype with three variants. The prototype grew to
six variants on the throwaway branch `prototype/mapping-editor` (screenshots and measurements in
`ui/prototype-screenshots/` and `ui/prototype-mapping-editor-MEASUREMENTS.md` there). The owner
chose the SAP Integration Suite shape, which SAP PO does not have, and combined parts of three
variants (d, e, f).

## Shape

- **Top half: structure overview.** The source tree and the target tree sit side by side. A
  link gutter between them draws the field links. There is no schema-files or "References"
  panel.
- **Bottom half: function editor.** It shows the function graph of the selected target field
  mapping on a canvas. A narrow palette lists the function catalogue. `/` or typing on the
  canvas opens quick insert. A collapsible inspector on the right shows the selected box:
  - its catalogue documentation, with the fidelity and deviation badge;
  - its box parameters as constant inputs;
  - the context of a source-field box;
  - its display queue.
- The test panel opens as a tab or a drawer: input, run, target output, and errors by
  `AuthoringId`.
- A splitter sets the ratio between the halves. A second splitter sets the ratio between source
  and target. Double-click resets a splitter, and either half collapses fully. The ratios are the
  viewer's own state in the browser, like the layout.
- The target tree has a status filter: all, mapped, unmapped mandatory, orphaned, error. Each
  row carries a status mark. It has no function-chain column.
- One target field mapping is open at a time. Tabs for several open mappings are left out.
- Rejected variants:
  - one data-flow canvas for the whole mapping (b): a tall column of crossing edges at real
    size, and it suggests links between mappings, which ADR-0010 refuses;
  - a table-first editor (c): no free graph editing.

## Field links

- A field link is one line per pair of source field and target field. When the target field
  mapping has several source-field boxes for that source field, the fx mark shows their count. A
  source-field box whose context is not the parent marks its line.
- The selected target field mapping's links are highlighted. The others are dimmed.
- A link whose row is scrolled away ends at the gutter edge, with a ↑n or ↓n count. A link to a
  collapsed row runs dashed to the nearest visible ancestor. Under a status filter, links to
  hidden rows are not drawn.
- Dropping a source row on a target row adds a source-field box to that target field mapping.
  If the target field had no mapping, the box becomes its `result`.

## Layout

- When a mapping opens, the editor lays out each function graph with dagre. The layout is
  deterministic: boxes go in sorted `AuthoringId` order.
- After that the layout is stable. Boxes already on the canvas stay where they are. A new box is
  placed next to its neighbour. "Arrange" runs the full layout again.
- Positions, zoom and splitter ratios are browser state only (ADR-0010). Dragging a box leaves
  the mapping model byte-identical (observed in the prototype).

## Display queue of a target field

The display queue of a target field shows two things, one above the other:

1. the result queue, with its contexts and SUPPRESS;
2. the occurrences created per parent slot: the slot number, whether an occurrence was created
   or not and why (SUPPRESS, truncated by `maxOccurs`), and any `po:MINOCCURS` error.

## Libraries

All of these were measured in the prototype (4 vCPU, headless Chromium capped at 60 Hz):

- `@xyflow/react` 12 (MIT) for the canvas.
- `@dagrejs/dagre` (MIT) for the layout: 2.7–4.1 ms per target field graph. elkjs is rejected:
  it took 17–21 ms, and it is EPL-2.0 OR GPL-3.0.
- `@tanstack/react-virtual` (MIT) over our own flat list of visible rows, for the trees.
  PatternFly `TreeView` is not virtualised (#9). A tree of 20,128 fields, fully expanded, scrolls
  at 60 fps with 54 rows in the DOM.
- An SVG overlay of our own draws the field links: 60 fps while scrolling 20,128 fields with all
  links drawn.
- React 19 and PatternFly 6.7 for everything else.
- The licence list in `CLAUDE.md` gains ISC and 0BSD. They are as permissive as MIT, and they
  reach us transitively: `d3-*` through React Flow, `tslib` through PatternFly.

## Considered options

- **Variant (a), PO style** with the trees left and right and the canvas in the middle: the
  canvas was 670 px wide at 1440 px, and a 14-box graph fitted only at 50 % zoom.
- **The PatternFly topology view**: rejected in #9 (MobX, `popper.js@1`).
- **Full layout after every edit**: rejected. Boxes jumped on every rewiring.
- **A layout file next to the mapping**: deferred. ADR-0010 keeps it possible later.

## Consequences

- #11 (UI architecture) builds on this shape and these libraries.
- The test API (#12) must return created occurrences per parent slot, with a reason for each
  occurrence that was not created, next to the queues.
- F-2 amends ADR-0010: a target field mapping's `result` may be `null` while it is unfinished.
