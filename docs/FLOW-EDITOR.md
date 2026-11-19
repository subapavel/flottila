# The flow editor

The flow editor is a screen of the Rail8 UI. It draws a flow file of a package in the working folder and
edits it. The flow file and the editor are two views of one flow. The editor's state is the file's text:
what the editor shows is read from that text, and every edit turns it into a new text. Nothing exists in
the editor that the file cannot hold.

This document is the agreed design. It is built in three stages, and none of them reworks what an
earlier one built:

- **The picture.** A flow opens drawn as one line, top to bottom: the inbound endpoint, then its steps.
  The line fans out only at a `to` that picks its endpoint by a prop, to the endpoints it picks among,
  side by side. A click on a step shows its settings, read-only.
- **A straight flow.** Adding, deleting and moving a step, a step's settings in a form, Save. The rules
  of a `route` and the endpoints of a `to` are shown but not edited.
- **The rest.** Editing those and the processes, calling another flow, a new flow from nothing.

A step that holds steps of its own, one list for each condition, does not exist yet and will come as
work of its own. Every part below already holds steps inside a step; "Steps inside a step" says how such
a step is drawn and edited, and what its coming changes.

## Rules

- The flow file holds no layout. The editor lays the flow out on its own, top to bottom. A file of
  coordinates may come later, keyed by the ids of the picture's nodes, with no change to the flow file's
  format.
- Opening a file never changes it. The file is written only by Save, and only with what `edit` made of
  it.
- An edit changes only what it edits. Every other byte of the file stays as it was: comments, blank
  lines, quoting, the order of keys and steps, one-line `{ … }` steps, line endings. This holds within
  the YAML forms and edits listed under "What `edit` supports"; any other edit is refused, with the
  reason, and nothing changes.
- A step type or a setting the editor does not know is kept, byte for byte, through every edit and save.
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
  flows: { path: string; id: string; mode: 'sync' | 'at-least-once' | 'at-most-once'; address?: string }[];
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
  id: string;           // its list's id and its effective name: 'main/validate', 'enrichOrder/map'; '#2' on a duplicate
  kind: 'from' | 'step' | 'destination' | 'flow' | 'end' | 'lane' | 'group';
  path: Path;           // the step it comes from: ['steps', 2], ['processes', 'enrichOrder', 'steps', 0]
  runsAs: string[];     // the names its runs have in a message's history: 'validate'; 'enrich/map' for each call
  type?: string;
  label: string;        // the step's name, else its default name
  defaultName: boolean; // true when label is the default: shown dimmed, never written
  detail?: string;      // 'flows/invoice.xsd', 'partner-domestic · http', 'calls flow order-partner'
  rows?: string[];      // a route's rules, at most 8
  opens?: { flow: string } | { endpoint: string };   // a file of the working folder the node links to
  x: number; y: number; width: number; height: number;   // whole numbers, from the layout only
  parent?: string;      // the lane or frame the node is drawn in
  form?: StepForm;
  locked?: string;      // why edit refuses ops on this node
}

interface FlowEdge {
  id: string; source: string; target: string;
  kind: 'next' | 'choice' | 'join' | 'ends' | 'each' | 'process' | 'calls';
  label?: string;
  insertAt?: Path;      // where a step dropped on this edge is added
}
```

The kinds of edge tell apart what a message does:

| Kind | What it means |
| --- | --- |
| `next` | the next step, for every message |
| `choice` | from a step that chooses to one of its alternatives, taken when the edge's label holds: today from a `to` by prop to one destination |
| `join` | from the end of an alternative to the step after the one that chose |
| `ends` | from a `filter` to the end: the message ends there when its condition fails |
| `each` | from a `split` into the rest of its list, run once per part |
| `process` | from a `process` call to its process's lane, run in place |
| `calls` | from a `to` to another flow, through a `direct` endpoint |

A node's id is built from names, never from positions, so a selection survives a move. An effective name
is the step's `name`, else its default. A list's id is its lane: `main` for the flow's `steps`, the
process's name for a process's. A rename, or a new type for a step with no name, changes the node's id,
as it changes the step's name in the message history.

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
  - A changed mapping is changed key by key, so a new route or destination is one new line.
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

#### What `edit` supports

`edit` changes these forms of YAML:

- **Block mappings and block lists**, at any indentation that is the same for all siblings.
- **One-line mappings** `{ … }` as a step, or as a value inside a step.
- **Scalars**, written plain, single-quoted or double-quoted. A block scalar (`|`, `>`) is replaced
  whole when its value changes, and is otherwise kept as written.
- **Comments** anywhere. A comment directly above a step is that step's. Any other comment stays where
  it is.

It supports these edits:

- adding, deleting and moving a step in a list of steps;
- setting, adding and removing a step's key, and a key of a mapping or an item of a list inside a step;
- the same on the flow's own keys and on `processes`;
- writing a new file whole.

It refuses an edit whose span holds any of these, with the reason, and changes nothing:

- an anchor, an alias, a tag or a merge key (`<<`);
- a list written on one line (`[ … ]`), a mapping written over several lines in braces, or a complex
  key (`? `);
- a second document in the file.

When `steps`, or the list of steps of a process, is itself in such a form, the flow opens read-only and
says why. Anywhere else, only an edit whose span holds such a form is refused.

#### The flow file's own rules

`edit` also keeps the rules of the flow file:

- It never writes a step's default name.
- A new step without a name whose default name is taken in its list gets one: `<default>-<n>` with the
  smallest free n from 2, so the older step keeps its name and its history rows stay findable.
- Removing the last step of a list is refused, and so is removing a process that a step still calls.

### Layout

The layout is the editor's own block layout, which keeps the file's order; dagre does not promise to.
It has two kinds of block: a column, blocks one below the other, and a fork, columns side by side that
leave one node and join at the next. A block holds blocks to any depth. Each block is measured (a column
is as wide as its widest block, a fork as wide as its columns and the gaps between them), then placed top
to bottom, centred in its column. Node sizes and gaps are fixed, and positions are whole numbers, so the
same file always gives the same picture.

- **Lanes.** The flow's `steps` are the main lane. Each process under `processes` is a lane of its own
  to the right, in file order, titled with its name, and drawn once however many steps call it. A
  process that no step calls is drawn too.
- **`from`.** The top node: the inbound endpoint's id and scheme, and the address of a `direct` one.
- **A step** is a node with its label and type, and one setting as its detail, such as a stylesheet.
- **`route`.** One node in the line of steps, listing its rules (`when → set`, `otherwise`) and the prop
  it sets. The flow does not fork at a route: every message goes on to the next step. A route only sets
  a prop, which a later `to` reads.
- **`to` with `endpoint`.** One node with the endpoint and its scheme.
- **`to` with `byProp`.** Today the only fork: a column of one destination node per entry of
  `endpoints`, side by side in the file's order, each reached by a `choice` edge:
  - The edge is labelled with the prop's value. It also carries the `when` of the nearest earlier
    `route` in the run that sets the prop to that value, or "otherwise".
  - Every destination joins the step after the `to`, as the flow goes on with the reply.
  - A value such a route sets that no entry covers is drawn as a destination with a problem.
- **A call of another flow.** A `to` whose endpoint has the scheme `direct` and an address that a flow
  of the working folder serves has a `calls` edge to a `flow` node beside it. That node shows the flow
  called and whether the caller waits for its reply (a `sync` flow) or hands the message over. It opens
  that flow. A `direct` endpoint whose address no flow serves is marked as a problem.
- **`filter`.** A node with a `next` edge down and an `ends` edge to an end node beside it.
- **`split`.** An `each` edge into a frame around the rest of its list, titled "each part of <xpath>".
  A split inside a process also frames the rest of each list that calls that process.
- **`process` call.** A node with a dashed `process` edge to its process's lane. A step in a lane lists
  in `runsAs` the name it runs under for each call (`enrich/map`).
- **A step of a type the node does not serve** is drawn as a step with a warning, in its place.

At most 2,048 nodes are drawn. A larger flow is not drawn: `readable` is false, and the text is shown.

### Settings

Every form is drawn from a settings description, as an endpoint's is, by the same controls and with the
same raw JSON editor for what is not described:

```ts
interface StepForm {
  path: Path;                       // the step's place in the file
  settings: StepSettings;
  values: Record<string, unknown>;
}

// A settings description as GET /admin/components serves one, whose fields may also be lists and maps.
interface StepSettings extends Omit<SettingsDescription, 'fields'> {
  fields: StepField[];
}
type StepField =
  | SettingsField                                                         // text, secret, integer, boolean, choice
  | (Omit<SettingsField, 'type'> & { type: 'list'; item: StepField[] })   // ordered rows, each of these fields
  | (Omit<SettingsField, 'type'> & { type: 'map'; key: { pattern?: string }; value: StepField });
```

An endpoint's description knows five types of field. A step's needs two more: a `route`'s `routes` is an
ordered list of rules, and a `to`'s `endpoints` maps a value to an endpoint. The node's settings
description stays as it is; these two types are the UI's own, for step forms only:

- **`list`.** Ordered rows, each of the fields in `item`, shown as a table. A row can be added, removed
  and moved.
- **`map`.** Rows of a key and its value, in the file's order, shown as a table. Each key is unique and
  matches `pattern`.

Both are shown read-only until the stage "the rest", which edits them. A row's change is an `add`,
`replace`, `remove` or `move` on its path, as any other field's.

The description of a step comes from one of these sources:

1. **Built-in steps** (`to`, `route`, `filter`, `extractKey`, `validate`, `split`, `process`).
   - They are described in the UI.
   - The choices of `to`'s `endpoint` and of `process`'s `process` are filled from the context and the
     file.
   - A test checks these descriptions against the required keys of the flow schema.
2. **Steps of modules and plugins** (`xslt`, `xsd`, `jq`, `mapping` and a plugin's types).
   - Their `configSchema`, from `GET /admin/steps`, becomes fields:
     - a string becomes text with its pattern, and a string with an `enum` a choice;
     - an integer becomes a whole number with its bounds, and a boolean a switch;
     - an array whose items are an object of such properties becomes a list;
     - an object whose `additionalProperties` is one of them becomes a map.

     `description`, `default` and `required` carry over.
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
click on `from` or on a `to`'s endpoint or destination shows the endpoint file's form read-only in the
panel, with "Open endpoint", which opens its own tab, where it is edited.

**The flow's own settings**, which no node of the picture stands for (`id`, `version`, `description`,
`delivery`, `recording`, `limits`, `partitionBy`, `forwardHeaders`, `retry`), are in the panel while
nothing is selected, and below the endpoint's form for `from`. They are read-only until the stage
"the rest", where a new flow needs them.

**Problems** are found after every edit, in the whole flow and not only in the step edited, and marked
at once on the canvas and in the form. An edit can break a rule far from the step it changes: a step
moved into a process may call that process.

- The text is checked against the flow schema the node checks it against, taken from the platform's
  schemas when the UI is built, and each step of a module or plugin against its `configSchema`. A JSON
  Schema validator runs in the browser, under one of the allowed licences and with no dependencies of
  its own (`@cfworker/json-schema`, MIT, is one).
- The node's rules that the schema cannot state are checked as the node checks them:
  - no list of steps is empty;
  - a name is unique in its list, at most 64 characters long, and never `ingress`;
  - a step names an endpoint of the package, and a process of the flow;
  - no process calls itself, and calls nest at most 8 deep;
  - a flow runs at most 1,024 steps once its processes are expanded;
  - no key or prop a step names starts with `rail8.`;
  - the rules of `delivery`, `limits` and `retry`.
- Each step's settings are checked against its description, and default names that clash are found.
- One file of cases, each a flow and the problems it must give, is run by the node's tests and by the
  UI's, so that both judge a flow alike.

Check lists every problem in one place. Save stays possible with problems, as on the Endpoints screen,
since a file may have them when it opens: Save is refused only while a field holds text it cannot turn
into a value. `rail8 validate` and the deploy stay the authority.

### Files the editor cannot fully understand

- **No YAML, not a mapping, not a flow, or a `steps` that is no list.** The file is listed with its
  problem. Its tab shows the text read-only, with the problem, and offers no Edit.
- **A step that is no mapping with a text `type`.** It is drawn in its place as a step with a problem,
  locked.
- **Anchors, aliases, tags, merge keys, a second document, or `steps` written as `[ … ]`.** Operations
  on the spans that hold them are refused, with the reason on the node. An edit elsewhere still works.
- **A plain value the node would read differently from the browser.** The node reads YAML with Jackson,
  by YAML 1.1 rules, but keeps `yes`, `no`, `on`, `off`, `y` and `n` as text. `0o17`, `1_000` or a date
  are examples. Such a value is a problem, and its key is locked.
- **A key used twice in one mapping.** A problem; that mapping is locked.
- **Unknown keys, unknown step types and unknown top-level keys.** They are shown raw and kept byte for
  byte.
- **Duplicate names, a missing process or endpoint, or a call of a process that loops.** The flow is
  drawn, with the problems marked.

## Steps inside a step

No step holds steps of its own today. A branching step, with one list of steps for each condition, will
come as work of its own. The editor is built so that its coming changes little:

- **Lists of steps are found, not named.** `view` and `edit` know a list of steps by its path:
  `['steps']`, `['processes', 'enrich', 'steps']`, and later perhaps `['steps', 3, 'when', 0, 'steps']`.
  Which keys of a step hold lists of steps is part of the built-in step's description in the UI
  (`stepLists`, patterns such as `['when', '*', 'steps']`); no step has one today. `view` walks every
  list it finds, to any depth within the limits. What holds for a list holds for a nested one: a step's
  span and its comments, new and moved steps, default names, unique names, the last step.
- **Ids.** A nested list's id is its step's id and the list's place in it, such as `main/router/when-1`,
  and its steps' ids follow, such as `main/router/when-1/map`. Today's ids do not change.
- **Drawing.** A branching step is a node in the line with a fork below it: one column per list, in the
  file's order, each entered by a `choice` edge labelled with its condition, all joining the step after
  it. This is the fork a `to` by prop draws today, with a column of steps where a destination stands.
- **Editing.** Its conditions are a `list` field in its form. Adding, removing and moving a condition is
  an `add`, `remove` or `move` of its item, and the condition's steps go with it. The steps inside are
  added, moved and deleted on the canvas as in any list, and moved between a branch and the line around
  it, which a `move` between lists does today.
- **What its coming changes.** The step's description, with its `stepLists` and its fields; how its node
  is drawn; the name its steps run under in a message's history (`runsAs`); whether a branch may be
  empty; and the tests. `view`'s walk, the layout's blocks, the ids, the paths, `edit` and the problems
  stay as they are.

The tests of `view` and `edit` show this before such a step exists: they give a made-up branching type a
`stepLists`, draw it, and add, move and delete steps inside it.

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
  says the file changed on disk. Closing a tab with unsaved changes asks first. The canvas's keys
  (Delete, the arrows, undo) act only while the canvas has the focus, never while a field of the form
  has it.
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
- **Steps inside a step.** A made-up branching type, drawn and edited, as "Steps inside a step" says.
- **Problems.** The shared file of cases, run against the node's rules and the browser's.
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
   - `view`, walking every list of steps by its path, with every node kind, lane and fork above.
   - The problems of the whole flow: the flow schema, the step schemas, the node's rules and their
     shared cases.
   - The canvas, and the settings panel read-only, endpoints and the flow's own settings included.
2. **A straight flow.**
   - `edit` for adding, deleting and moving a step in a list, and for the keys of a step.
   - Edit, Check and Save, and `PUT` with `If-Match`.
   - The forms' fields editable, except `list` and `map` fields.
3. **The rest.**
   - `list` and `map` fields editable: the routes of a `route`, the `endpoints` of a `to`, and those of
     the steps of modules and plugins.
   - Processes in their lanes: adding, renaming and deleting a process, and moving steps between lanes.
   - A call of another flow: a `to` a `direct` out endpoint whose address the flow called serves,
     creating that endpoint file with `If-None-Match: *` when none exists.
   - A new flow from nothing.
   - The flow's own settings editable.
