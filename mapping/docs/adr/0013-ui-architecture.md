# The editor is a router-free component over injected ports, with a Zustand working copy and TanStack Query for server data

Status: accepted (2026-10-30, grilling #11); paths amended by F-34 (2026-11-10, owner): the Mapping studio (ADR-0028, #161) replaced the editor, so the public entry is `Studio` (`ui/src/studio/shell/Studio.tsx`) and the store and its coverage floor are `ui/src/studio/store/`. Every `MappingEditor` and `ui/src/editor/` below reads as these; the decisions are unchanged.

FOUNDING §12 says the UI is React + PatternFly and will become Flottila's mapping UI. ADR-0012
fixed the editor's shape and its canvas, tree and layout libraries. This ADR fixes how `ui/` is
built around them. Flottila has no UI architecture yet, so nothing here may assume its shell.

## The editor and its host

- `MappingEditor` (`ui/src/editor/index.ts`) is the only public entry. It takes:
  - `mappingId`;
  - an `ApiClient`, the port to the service;
  - a `BrowserStateStore`, the port for layout and view state;
  - the selected target field path as controlled state (`selectedField`, `onSelectField`).
- It imports no router and nothing from `app/`.
- `ui/src/app/` is the **dev host**, a thin page for milestone 1 that is dropped when Flottila
  embeds the editor:
  - it picks one mapping;
  - it reads `?mapping=<id>&field=<path>&api=fake` once at start;
  - it writes nothing to the URL.
- There is no router library.

## State

- **Server data** goes through TanStack Query 5 (MIT): the list of mappings, the loaded mapping
  file, the structures, the function catalogue, compile results and test runs. The editor never
  changes this data in the cache.
- **The working copy** lives in a Zustand 5 (MIT) store, one per open mapping. The store holds:
  - the baseline, which is the last canonical model the service returned;
  - the working copy;
  - undo and redo, as stacks of immutable snapshots;
  - the selection.
- Every edit is a pure command `(model, command) → model` in `ui/src/model/`. Commands use Immer
  (MIT) and depend on neither React nor Zustand. The store's actions only call them.
- **Save is explicit:**
  - the client sends the working copy;
  - the service validates stages 1 and 2 (ADR-0011) and writes canonical form;
  - the service returns the canonical model, which becomes the new baseline. The Java writer is
    the only writer (ADR-0010), so the client has none of its own.
  - "Unsaved" means the working copy differs from the baseline.
- There is no autosave. Leaving with unsaved changes asks first.

## Types and validation

- No wire payload has a hand-written TypeScript type. Generated types live in
  `ui/src/generated/`. They are committed and never edited, and CI regenerates them and requires
  an empty diff.
  - The mapping model and the function catalogue come from their JSON Schemas, through
    json-schema-to-typescript 16 (ADR-0011).
  - API payload types come from whatever source #12 makes the contract source.
- The commands keep the model check's invariants by construction. They refuse:
  - a cycle;
  - a reference to a box of another target field mapping;
  - a duplicate id;
  - a context that is not a proper ancestor of its field.
- The client derives its marks live: `result` is `null`, an input is unconnected, a reference
  is orphaned against the loaded structure. These feed the status filter.
- The client has no JSON Schema validator. The service is the authority for all three stages.
- Compile and test run take the working copy in the request body, so an unsaved mapping can be
  tested. Compile runs about 500 ms after the last edit, and a newer edit cancels it. Test runs
  only on request.
- A box that `result` cannot reach is an **unreachable box**:
  - it is kept on save, and the canvas dims it;
  - compiling ignores it with a warning, never an error;
  - ADR-0011's "unconnected input" therefore applies only to boxes that `result` reaches.

## Modules of `ui/src`

```
generated/   model/   api/   app/
editor/{index.ts, store/, structure-overview/, function-editor/, inspector/, test-panel/, browser-state/}
```

- `model` imports only `generated` and `immer`.
- `api` imports only `generated`.
- `editor/*` imports `model`, `api` and `generated`.
- Only `app` imports `editor/index.ts`, and nothing imports `app`.
- `@xyflow/react` and `@dagrejs/dagre` are imported only in `function-editor/`.
  `@tanstack/react-virtual` is imported only in `structure-overview/`.
- Editor subfolders do not import each other. They meet through the store.
- ESLint `no-restricted-imports`, with an override per folder in the flat config, enforces these
  rules. The ticket gate's lint step checks them. A violation failed lint as expected when tried
  on 2026-10-30.

## Browser state

- `BrowserStateStore` defaults to `localStorage` under the prefix `flottila-mapping:v1:`. Every
  read and write is wrapped, and a failure falls back to the defaults.
- What is kept, and per what:
  - per user: the splitter ratios and the collapsed halves and inspector;
  - per mapping: the expanded tree branches and the status filter;
  - per target field mapping: the box positions and the zoom. Positions are kept only for ids
    the model still has, and "Arrange" clears them.
- The store keeps the 50 most recently opened mappings and evicts the oldest first.
- A host may supply another implementation, for example a user profile in Flottila.

## Tests and browser feedback

- **Unit tests** run in Vitest 5 in node mode. They cover `model/`, the store and the editor's
  pure logic: tree flattening, link geometry and the dagre layout.
- **Component tests** run in Vitest browser mode on Chromium, and only where a real DOM is
  needed. jsdom is not used: jsdom 30 needs Node ≥ 22.22.2, and the VM has 22.22.0.
- **E2E tests** use Playwright, with `@playwright/test` pinned to the VM's preinstalled 1.63.0
  and run against `vite preview`. There are two suites:
  - the default suite runs against the fake `ApiClient` (`api/fake/`) over synthetic fixtures
    from `corpus/`, and the ticket gate runs it;
  - the `@stack` suite runs against the real service in the spec gate and covers GATE checks 2
    and 3.
  - Selectors are by role, or by a `data-testid` built from the `AuthoringId` or the field path.
  - Every test fails on `console.error` or `pageerror`.
- **Performance:** one Playwright scenario scrolls a tree of 20,128 fields. Its numbers go to
  `bench/RESULTS.md`, and the ratchet keeps them.
- `npm run shot -- '<query>' <name>` writes `ui/.logs/<ticket>-<name>.png` and a console log.
  It exits non-zero on a console error. UI hand-backs name their screenshots.
- `check:pw` compares the pinned Playwright version with the preinstalled one. On a mismatch it
  stops with `ENVIRONMENT_BLOCKED`. Agents never run `playwright install`.
- There are no pixel-diff tests (`toHaveScreenshot`).
- Tooling, which is easy to change: TypeScript 6.0.3 (typescript-eslint 8.72 accepts < 6.1),
  ESLint 10 with typescript-eslint and `react-hooks`, Prettier 3, `@vitest/coverage-v8`, and
  Node 22 ≥ 22.12. Versions are pinned exactly, and `package-lock.json` is committed.

## Considered options

- **TanStack Router**: rejected. Its hard dependency `isbot` is Unlicense, which is not on the
  licence list.
- **React Router 8**: rejected. It is MIT and was observed to build, but the dev host needs no
  routes, and Flottila's shell will own navigation.
- **The working copy in the Query cache** (`setQueryData`): rejected. Undo, the unsaved state
  and refetch fight over it.
- **Redux Toolkit**: rejected as too much ceremony for one document. **Jotai**: rejected,
  because the model is one tree, not many atoms. **MobX** (Kaoto's choice): rejected, because
  mutable proxies fight the immutable snapshots.
- **A client canonical writer**: rejected, because there is one writer (ADR-0010). **ajv in the
  client**: rejected, because it is a second validator that can disagree with json-sKema.
- **Compile and test only on the saved model**: rejected, because every try would need a save
  and a commit.
- **Autosave**: rejected. It writes too often and fills the git history.
- **Network-level mocks (MSW, `page.route`)**: rejected. The injected `ApiClient` makes a fake
  simpler. **Cypress**: rejected, because it is a second browser tool next to the VM's
  Playwright.
- **`sessionStorage`** (the prototype): rejected, because the layout is lost when the tab closes.

## Consequences

- A UI ticket sits in one folder of `editor/`. Three writers can work in parallel there.
- #12 makes compile and test take the model in the request body. It also picks the source of
  the API payload types; the owner prefers Quarkus OpenAPI → TypeScript.
- #13 wires `ui/` into the build (Quinoa or a separate job), installs the browser on CI, and
  puts Node 22 ≥ 22.12 there.
- #14 sets the coverage floor for `ui/src/model/` and `ui/src/editor/store/`. The proposal is
  ≥ 90 % of lines. Milestone 1 has no mutation testing on `ui/`.
- Facts behind this ADR were observed on 2026-10-30 in a scratch directory and not committed:
  - Vite 8.3.5 with React 19.3.1, PatternFly 6.7.0, `@xyflow/react` 12.12.0, Query 5.105,
    Zustand 5.0.16 and Immer 11.2.9: `tsc` and `vite build` pass;
  - all production licences are MIT, ISC, BSD-3-Clause or 0BSD;
  - Playwright 1.63.0 e2e passes against `vite preview` with no install, and Vitest 5 passes in
    node mode and in browser mode;
  - `json2ts` gives byte-identical output on a rerun.
