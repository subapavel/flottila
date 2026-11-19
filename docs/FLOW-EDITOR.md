# The flow editor

The flow editor is a screen of the Rail8 UI. It draws a flow file of a package in the working folder and
edits it. The flow file and the editor are two views of one flow. The editor's state is the file's text:
what the editor shows is read from that text, and every edit turns it into a new text. Nothing exists in
the editor that the file cannot hold.

This document is the agreed design. It is built in three stages, and none of them reworks what an
earlier one built:

- **The picture.** A flow opens drawn: the inbound endpoint, the steps top to bottom, branches side by
  side. A click on a step shows its settings, read-only.
- **A straight flow.** Adding, deleting and moving a step, a step's settings in a form, Save. Branches
  are shown but not edited.
- **The rest.** Editing branches and processes, calling another flow, a new flow from nothing.

## Rules

- The flow file holds no layout. The editor lays the flow out on its own, top to bottom, with branches
  side by side. A file of coordinates may come later, keyed by the ids of the picture's nodes, with no
  change to the flow file's format.
- An edit changes only the step it edits. Every other byte of the file stays as it was: comments, blank
  lines, quoting, the order of keys and steps, one-line `{ … }` steps, line endings.
- A step's name is optional. The editor shows a step's default name (its type; for a `process` call, the
  process) but never writes it.
- A file changed by hand opens as it is, and a file the editor saves reads as a person would have
  written it.
- The UI edits files in the working folder and never the running node. An edited package is deployed as
  any other.

## The parts

Three entry points hold everything. Two of them are pure functions over the text, and they run in the
browser.

```ts
// 1. The working folder's flow files, through the development server.
interface FlowsApi {
  list(): Promise<FlowList>;
  open(path: string): Promise<FlowFile>;
  save(path: string, text: string, etag: string | null): Promise<FlowFile>;  // null creates the file
}

// 2. What is drawn: the same text and context always give the same view, positions included.
declare function view(text: string, context: FlowContext): FlowView;

// 3. Every change: the new text, or an EditRefused that changes nothing.
declare function edit(text: string, ...ops: Op[]): string;
```

The screen keeps the text of its working copy. Unsaved changes are a text that differs from the one
read. Undo keeps the last 100 texts.

### The working folder's flow files

The development server serves the flow files of the working folder (`RAIL8_WORKSPACE`) under
`/files/flows`, as it serves the endpoint files. It uses the same rule for who may ask (the UI's own
pages only), the same walk of the folder, the same limits (1,000 files, 16 folders deep, files up to
1 MiB) and the same read-only mode (`RAIL8_READ_ONLY=true` refuses every save).

| Route | Answer |
| --- | --- |
| `GET /files/flows` | `{configured, editable, truncated, items}`: each file of `kind: flow` as `{path, id}`, or `{path, problem}` when it cannot be read |
| `GET /files/flows/<path>` | `{path, text, editable, context}` with the file's `ETag` |
| `PUT /files/flows/<path>` `{text}` with `If-Match` | writes the text and answers as `GET` |
| `PUT /files/flows/<path>` `{text}` with `If-None-Match: *` | creates the file (from the stage "the rest") |

The refusals:

| Status | When |
| --- | --- |
| 403 | read-only mode, or a request from elsewhere |
| 404 | no such flow file |
| 412 | the file changed since it was read, or, on a create, it exists |
| 413 | the text is over 1 MiB |
| 422 | the text is no YAML, or no flow |
| 428 | neither precondition is sent |

A save is written through a temporary file renamed over the old one, one save at a time. A text equal to
the file is not written, so its `ETag` stays the same. The server reads the text it is sent with the same
reader as the browser, and refuses one that is not a flow.

The context tells the editor what the flow refers to:

```ts
interface FlowContext {
  // the endpoints of the flow's package: the nearest folder above the file that holds package.yaml
  endpoints: { id: string; path: string; scheme: string; direction: 'in' | 'out'; address?: string }[];
  // the flows of the working folder; address is the one their inbound direct endpoint serves
  flows: { path: string; id: string; address?: string }[];
  // the step types the node serves, or why they could not be read
  steps: { type: string; configSchema: unknown }[] | { missing: string };
}
```

`steps` comes from the node's `GET /admin/steps` (below), read through the UI's `/admin` proxy at the
same time as the file.

### Reading: `view`

`view` reads the text with the `yaml` library, which gives the place of every node in the text, and
answers what the canvas and the settings panel show:

```ts
interface FlowView {
  readable: boolean;            // false: the file cannot be drawn; the screen shows its text with the problems
  nodes: FlowNode[];
  edges: FlowEdge[];
  problems: { path: Path; message: string }[];
}
type Path = readonly (string | number)[];   // where a value stands in the file: ['steps', 2, 'xpath']

interface FlowNode {
  id: string;           // the lane and the effective name: 'main/validate', 'enrichOrder/map'; '#2' on a duplicate
  kind: 'from' | 'step' | 'branch' | 'end' | 'lane' | 'group';
  path: Path;           // ['steps', 2], ['processes', 'enrichOrder', 'steps', 0]
  type?: string;
  label: string;        // the step's name, else its default name
  defaultName: boolean; // true when label is the default: shown dimmed, never written
  detail?: string;      // 'flows/invoice.xsd', 'partner-domestic · http', 'calls flow order-partner'
  rows?: string[];      // a route's rules, at most 8
  opens?: { flow: string } | { endpoint: string };   // a file of the working folder the node links to
  x: number; y: number; width: number; height: number;   // whole numbers, from the layout only
  parent?: string;      // the lane or group the node is drawn in
  form?: StepForm;
  locked?: string;      // why edit refuses ops on this node
}

interface FlowEdge {
  id: string; source: string; target: string;
  kind: 'next' | 'branch' | 'join' | 'ends' | 'call';
  label?: string;
  insertAt?: Path;      // where a step dropped on this edge is added
}
```

A node's id is built from names, never from positions, so a selection survives a move, and a later
coordinates file can key on it. An effective name is the step's `name`, else its default.

### Writing: `edit`

`edit` takes the operations of JSON Patch (RFC 6902) on paths into the file, and answers the new text:

```ts
type Op =
  | { op: 'add'; path: Path; value: unknown }       // a member is set; at a list index, inserted (index = length appends)
  | { op: 'replace'; path: Path; value: unknown }
  | { op: 'remove'; path: Path }
  | { op: 'move'; from: Path; path: Path };          // steps, also between lists; path counted after the removal
```

The operations run in order, each on the result of the one before it, and either all of them apply or
none does. The screen builds them only from the paths the current view gives it (`path`, `insertAt`,
a form's field). Then `out = edit(text, ...ops)` keeps three promises, and throws `EditRefused` when it
cannot:

1. **It reads back.** `out` reads deep-equal to the file's value with the operations applied.
2. **Nothing else changes.** Every byte outside the spans the operations touch is unchanged.
3. **The style stays.** Line endings, indentation and the quoting of untouched values stay as they are.

How the text is changed:

- **A step's span** is its item in the list and the comment lines directly above it, with no blank line
  between. A move or a delete takes those comments along. Blank lines around a span stay; a delete
  that would leave two blank lines in a row also removes the one above the span.
- **A changed value** is rewritten in place:
  - A scalar keeps its key and the comment after it.
  - A changed mapping is changed key by key, so a new route or branch is one new line.
  - A new key goes after the mapping's last key, or before the `}` of a one-line mapping.
- **A new step** copies the style of the step before it in its list, else the one after it, else block
  style. It is written on one line, `{ … }`, when that step is and the line fits in 100 characters. Its
  keys go in this order: `type`, `name`, the step's own settings in the order its form shows them,
  `retryable`, `recording`. It takes the list's indentation.
- **A moved step** keeps its text, re-indented by one shift when the two lists are indented differently.
- **A new value** is written plain only where both the browser's reader and the node's would read it as
  the same text. Otherwise it is written double-quoted, and text of several lines as a `|-` block.
  Numbers and true or false are written as they are.
- **A new file** is `edit('', { op: 'add', path: [], value })`, in block style with two spaces of indent
  (a new flow, or the `direct` out endpoint a call needs).

`edit` also keeps the rules of the flow file:

- It never writes a step's default name.
- A new step without a name whose default name is taken in its list gets one: `<default>-<n>` with the
  smallest free n from 2, so the older step keeps its name and its history rows stay findable.
- Removing the last step of a list is refused, and so is removing a process that a step still calls.

### Layout

The layout is the editor's own block layout. A flow is series-parallel, and a block layout keeps
branches in the file's order, which dagre does not promise. Each block is measured (a column is as wide
as its widest node, a fork as wide as its branches side by side), then placed top to bottom, centred in
its column. Node sizes and gaps are fixed, and positions are whole numbers, so the same file always gives
the same picture.

- **Lanes.** The flow's `steps` are the main lane. Each process under `processes` is a lane of its own
  to the right, in file order, titled with its name, and drawn once however many steps call it. A
  process that no step calls is drawn too.
- **`from`.** The top node: the inbound endpoint's id and scheme, and the address of a `direct` one.
- **A step** is a node with its label and type, and one setting as its detail, such as a stylesheet.
- **`route`.** One node that lists its rules, `when → set` and `otherwise`. The flow does not fork at a
  route; it only sets a prop.
- **`to` with `endpoint`.** One node with the endpoint and its scheme. When the endpoint's scheme is
  `direct` and a flow of the working folder serves its address, the node reads "calls flow X" and opens
  that flow. When none does, it is marked as a problem.
- **`to` with `byProp`.** The only fork: one branch per entry of `endpoints`, side by side in the file's
  order.
  - Each branch is labelled with its value and with the `when` of the nearest earlier `route` that sets
    the prop to that value, or "otherwise".
  - The branches join at the next step.
  - A value such a route sets that no entry covers is drawn as a warning branch.
- **`filter`.** A node with an edge down ("passes") and an edge to an end node beside it ("ends").
- **`split`.** A frame around the rest of its list, titled "each part of <xpath>". A split inside a
  process also frames the rest of each list that calls that process.
- **`process` call.** A node with a dashed edge to its process's lane.
- **A step of a type the node does not serve** is drawn as a step with a warning, in its place.

At most 2,048 nodes are drawn. A larger flow is not drawn: `readable` is false, and the text is shown.

### Settings

Every form is drawn from a settings description, as an endpoint's is, by the same controls and with the
same raw JSON editor for what is not described:

```ts
interface StepForm {
  path: Path;                       // the step's place in the file
  settings: SettingsDescription;    // as GET /admin/components serves for a component
  values: Record<string, unknown>;
  branches: string[];               // the fields that hold branches: read-only until "the rest"
}
```

The description of a step comes from one of these sources:

1. **Built-in steps** (`to`, `route`, `filter`, `extractKey`, `validate`, `split`, `process`).
   - They are described in the UI.
   - The choices of `to`'s `endpoint` and of `process`'s `process` are filled from the context and the
     file.
   - A test checks these descriptions against the required keys of the flow schema.
2. **Steps of modules and plugins** (`xslt`, `xsd`, `jq`, `mapping` and a plugin's types).
   - Their `configSchema`, from `GET /admin/steps`, becomes fields: a string becomes text with its
     pattern, an integer a whole number with its bounds, a boolean a switch, and a list of strings a
     choice. `description`, `default` and `required` carry over.
   - Any other property becomes a raw field.
   - A schema without top-level properties is not described, and the form is one raw JSON editor.
   - When the node later serves a step's own settings description, the form takes that instead, with no
     change to the flow file.
3. **A step of a type the node does not serve**, or every non-built-in step while `/admin/steps` cannot
   be read, gets the raw editor and says why. It can still be moved and deleted.

Every step also has the tab "Step":

- `name`, with its default name as the placeholder. An empty name removes the key, and a rename warns
  that the message history keeps the old name on older rows.
- `retryable`.
- `recording.level` and `recording.payload`.

A key no field describes keeps its own raw field and its own lines.

**Endpoints** are shown through their component's settings description, as on the Endpoints screen. A
click on `from` or on a `to`'s endpoint or branch shows the endpoint file's form read-only in the panel,
with "Open endpoint", which opens its own tab, where it is edited. The `from` node's panel also shows the
flow's own settings (`delivery`, `recording`, `limits` and the others): read-only until the stage
"the rest", where a new flow needs them.

**Check** judges in the browser:
- each step's settings against its description;
- unique names in each list, and the reserved name `ingress`;
- default names that clash;
- references to endpoints and processes;
- calls of processes that loop or nest deeper than 8.

`rail8 validate` and the deploy stay the authority.

### Files the editor cannot fully understand

- **No YAML, not a mapping, not a flow, or a `steps` that is no list.** The file is listed with its
  problem. Its tab shows the text read-only, with the problem, and offers no Edit.
- **A step that is no mapping with a text `type`.** It is drawn in its place as a step with a problem,
  locked.
- **Anchors, aliases, tags, merge keys, a second document, or `steps` written as `[ … ]`.** Operations
  on the spans that hold them are refused, with the reason on the node. Replacing a scalar outside them
  still works.
- **A plain value the node would read differently from the browser.** The node reads YAML with Jackson,
  by YAML 1.1 rules, but keeps `yes`, `no`, `on`, `off`, `y` and `n` as text. `0o17`, `1_000` or a date
  are examples. Such a value is a problem, and its key is locked.
- **A key used twice in one mapping.** A problem; that mapping is locked.
- **Unknown keys, unknown step types and unknown top-level keys.** They are shown raw and kept byte for
  byte.
- **Duplicate names, a missing process or endpoint, or a call of a process that loops.** The flow is
  drawn, with the problems marked.

## The node: `GET /admin/steps`

The admin API gains one route. It lists the step types of the node's modules and plugins, sorted by type:
`{"items": [{"type": "jq", "configSchema": {…}}]}`. The built-in steps have no factory and are not
listed. It is behind the same browser rule and token as every other `/admin` route. Nothing in
`rail8-api` changes: `StepFactory.configSchema()` exists today.

## Where it lives

- **The screen.** The flow screen sits with the Endpoints screen in the UI, and both screens edit files
  of the working folder. A flow's tab is the canvas (React Flow, in PatternFly's tokens, as the mapping
  studio's) with a drawer for the settings. It opens read-only: Edit, then Cancel, Check and Save, as
  the endpoint form does. Save sends the text with `If-Match`; a refusal with 412 opens the dialog that
  says the file changed on disk. Closing a tab with unsaved changes asks first.
- **The development server.** It shares one helper for the working folder with the endpoint files: the
  walk, finding a path inside the folder, the `ETag` and the write through a temporary file.
- **The code.**
  - `view` and `edit` are pure TypeScript, with no browser or Node API. The development server imports
    the reader from them.
  - `yaml` (2.x, ISC licence, no dependencies of its own) joins the UI's dependencies.
  - `js-yaml` stays where it is used today.

## Tests

- **`edit`, on golden pairs of texts.**
  - Before, operations, after, for every flow under `examples/`.
  - Files written to be awkward: comments in every place, one-line steps, CRLF, anchors, unknown keys
    and types, processes.
- **A property test.** Random series of operations on those files, checking that:
  - every byte outside the touched spans stays;
  - the text reads back as the patched value;
  - `view` of the result draws what the operations say.
- **`view`.** A golden view per file, positions included, which also shows that the same text gives the
  same picture.
- **The development server.** Tested on a temporary folder, as the endpoint files' server is.
- **The screen.** Tested against a fake `FlowsApi` that keeps texts in memory with a counter as
  `ETag`, and answers 412 and 403 as the server does.
- **The node's route.** Tested with the admin API's tests.

## Limits

| What | Limit |
| --- | --- |
| A flow file | 1 MiB |
| Files listed | 1,000 |
| Nodes drawn | 2,048 |
| Rules shown on a route's node | 8 |
| A path | 32 steps deep |
| Operations in one `edit` | 256 |
| Undo | 100 texts |

## What each stage builds

1. **The picture.**
   - The routes `GET /files/flows` and `GET /files/flows/<path>`, and the context.
   - `GET /admin/steps` on the node.
   - `view`, with every node kind, lane and fork above.
   - The canvas, and the settings panel read-only, endpoints included.
2. **A straight flow.**
   - `edit` for adding, deleting and moving a step in a list, and for the keys of a step.
   - Edit, Check and Save, and `PUT` with `If-Match`.
   - The forms' fields editable, except the branches.
3. **The rest.**
   - The branch fields: the routes of a `route`, the `endpoints` of a `to`.
   - Processes in their lanes: adding, renaming and deleting a process, and moving steps between lanes.
   - A call of another flow: a `to` a `direct` out endpoint whose address the flow called serves,
     creating that endpoint file with `If-None-Match: *` when none exists.
   - A new flow from nothing.
   - The flow's own settings editable.
