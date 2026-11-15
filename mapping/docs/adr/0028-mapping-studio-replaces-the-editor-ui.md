# The Mapping studio replaces the editor UI; state stays in one Zustand store per mapping

Status: accepted (2026-11-09, owner, spec #152).

This ADR supersedes the look and shape of ADR-0012:

- the split view;
- the linked-trees gutter;
- the palette;
- the page tabs.

It also supersedes ADR-0013's dev host. ADR-0012's libraries (@xyflow/react, react-virtual) and ADR-0013's ports and state split still hold. It is a new ADR rather than an amendment, because the owner replaced the UI as a whole.

## Decision

The owner's prototype is the UI. It is on branch `prototype/mapping-studio`, under `ui/prototype/mapping-studio/`. Its look is carried over to the pixel.

- **Start page.**
  - A PatternFly `Table` of mappings with the columns Name, Package, Source, Target and Description. Each row has an "Open in studio" link.
  - A "New mapping" `Modal` with Name, Package and Description only.
  - Create opens the studio in edit mode on the Signature tab, where the source and target
    messages are chosen (ADR-0029 makes them optional).
- **Studio.**
  - The masthead reads "Flottila | Mapping studio".
  - The tabs are Overview, Definition, Test, Signature and Functions. The active tab is the URL hash, and the page reads `?mapping=<id>`. There is no router library.
- **Modes.**
  - A mapping opens read-only.
  - **Edit** makes the working copy editable. **Save** sends it with `If-Match` (ADR-0027). **Discard** restores the baseline and clears undo.
  - `beforeunload` warns while the working copy differs from the baseline.
- **Terms.** Name, Mapping ID, Package and Description (GLOSSARY). There is no SWCV, no namespace and no `com.sap` package.
- **Mocked capabilities.** The engine has no support yet for these, so they work only in the browser:
  - variables;
  - grouped expressions;
  - lookups and value mapping;
  - parameters;
  - multi-message signatures;
  - the Functions (UDF) tab;
  - the test-instance library.

  All of them live in `ui/src/studio/mock/`. Each carries a "Preview" badge and is never written to the mapping file. A later spec replaces a mock with the real feature, one at a time.
- **Diagnostics** show in the Test tab's processing log. There is no Diagnostics tab and no Model JSON view.

## State

- ADR-0013 stands:
  - TanStack Query for server data;
  - one Zustand store per open mapping (baseline, working copy, undo, selection);
  - pure Immer commands in `ui/src/model/`.
- The prototype's component state moves into a **UI slice** of that store: tab, edit mode, the selected target path, open dialogs and the drilldown path.
- Viewer preferences go through `BrowserStateStore`, not raw `localStorage`: panel sizes, view modes (tree, table or text) and the last tab.
- Mock state lives in its own Zustand store in `mock/`, kept per mapping id in `BrowserStateStore`. It never reaches `ApiClient`.
- Preview edits belong to the edit session, as in the prototype:
  - while editing, they are a draft that makes the studio unsaved (Save and Check enabled, the leave guard asks);
  - Save commits the draft to browser state together with the model save;
  - Discard reverts the draft;
  - a refused or 409 save keeps the draft.
- Port to TypeScript strict. No new state library (Redux, Jotai) and no form library: the forms are small.

## Fidelity in the studio

Owner, 2026-11-11, #207. Flottila is not only for SAP PO users, so the studio shows no fidelity mark, badge or label. Exact and assumed functions look alike. A function with a deviation shows its deviation note in its tooltip only, in two places:

- the Add node catalogue;
- a placed box, in its hover hint and its accessible name.

The note is written for readers who do not know SAP PO, in `engine/mapping/src/main/catalogue/`. This supersedes ADR-0012's "fidelity and deviation badge".

## Considered options

- **Keep the prototype's single component with `useState`**: rejected. Undo, Save/Discard and the 409 reload need one owner of the working copy. The store already has it.
- **Mock features hidden until real**: rejected by the owner ("Unsupported parts stay mocked").
- **React Router for list and studio**: rejected. There are two pages, and Flottila will embed the studio, so the host owns routing (ADR-0013).
