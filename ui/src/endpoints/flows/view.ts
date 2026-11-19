import { isAlias, isMap, isNode, isScalar, isSeq, parseDocument } from 'yaml';
import { readFlow, type FlowRead } from './flowYaml';
import { layout, type Block } from './layout';
import { BUILT_IN, checkStep, checkStepTab, describeSchema, settingsOf, UNDESCRIBED, type StepType } from './steps';
import type { FlowContext, FlowEdge, FlowNode, FlowProblem, FlowView, Path, StepField, StepForm } from './types';

/** The most nodes drawn, rules listed on a route's node, names listed in `runsAs`, processes inside each other, and the deepest path. */
export const MAX_NODES = 2048;
const MAX_ROWS = 8;
const MAX_RUNS_AS = 8;
const MAX_NESTING = 8;
const MAX_NAME = 64;
const MAX_PATH = 32;

/** The sizes of the nodes, by kind. */
const SIZE = {
  from: { width: 240, height: 56 },
  step: { width: 240, height: 56 },
  row: 20,
  destination: { width: 200, height: 48 },
  flow: { width: 200, height: 48 },
  end: { width: 120, height: 32 },
} as const;

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** `path` as a JSON Pointer: `['steps', 2]` is `/steps/2`. */
export const pointer = (path: Path) => path.map((s) => `/${String(s).replaceAll('~', '~0').replaceAll('/', '~1')}`).join('');

/** The place of `path` as the node writes it in its refusals: `processes.enrich.steps[1].name`. */
const place = (path: Path) => path.reduce<string>((out, s) => (typeof s === 'number' ? `${out}[${s}]` : out === '' ? s : `${out}.${s}`), '');

const within = (path: Path, prefix: Path) => prefix.length <= path.length && prefix.every((s, i) => path[i] === s);

/** Text for a node or a label, cut to `max` characters. */
const cut = (text: string, max = 48) => (text.length > max ? `${text.slice(0, max - 1)}…` : text);

/** What locks a part of the file, by the path of the YAML node that holds it. */
interface Lock {
  path: Path;
  reason: string;
  /** True when what the YAML node holds is locked too: the items of a list on one line, or of a mapping in braces. */
  covers?: true;
}

/**
 * The locks of the file and the problems of its YAML: anchors, aliases, tags, merge keys, lists on one line, mappings in
 * braces over several lines and complex keys lock the node that holds them. The node reads an alias as the anchor's name,
 * as text, and a merge key as a key of its own, so each of those is a problem too. A key used twice is a problem and
 * locks its mapping. The node reads YAML 1.1 but keeps yes, no, on, off, y and n as text, and reads numbers with colons
 * (1:30) as text too; a plain value it reads otherwise than the browser is a problem and locks its key.
 */
function yamlFindings(doc: Extract<FlowRead, { kind: 'flow' }>, text: string, problems: FlowProblem[]): Lock[] {
  const locks: Lock[] = [];
  const other = parseDocument(text, { version: '1.1', uniqueKeys: false, merge: false, logLevel: 'error' });
  const visit = (node: unknown, twin: unknown, path: Path) => {
    if (!isNode(node)) return;
    if (isAlias(node)) {
      problems.push({ path, message: `the node reads the alias *${node.source} as the text ${node.source}, not as what &${node.source} holds` });
      locks.push({ path, reason: `an alias (*${node.source})` });
      return;
    }
    if (node.anchor !== undefined) locks.push({ path, reason: `an anchor (&${node.anchor})` });
    if (node.tag !== undefined) locks.push({ path, reason: `a tag (${doc.doc.directives?.tagString(node.tag) ?? node.tag})` });
    if (isSeq(node)) {
      if (node.flow === true) locks.push({ path, reason: 'a list written on one line ([ … ])', covers: true });
      node.items.forEach((item, i) => visit(item, isSeq(twin) ? twin.items[i] : undefined, [...path, i]));
    } else if (isMap(node)) {
      const range = node.range;
      if (node.flow === true && range !== undefined && range !== null && text.slice(range[0], range[1]).includes('\n')) {
        locks.push({ path, reason: 'a mapping written over several lines in braces', covers: true });
      }
      const seen = new Set<string>();
      node.items.forEach((pair, i) => {
        if (!isScalar(pair.key)) {
          locks.push({ path, reason: 'a complex key (? )' });
          return;
        }
        const key = String(pair.key.value);
        if (key === '<<' && pair.key.type === 'PLAIN') {
          problems.push({ path: [...path, key], message: 'the node does not merge keys: it reads << as a key of its own' });
          locks.push({ path, reason: 'a merge key (<<)' });
        }
        if (seen.has(key)) {
          problems.push({ path: [...path, key], message: `the key ${key} is used twice in one mapping` });
          locks.push({ path, reason: `the key ${key} used twice` });
        }
        seen.add(key);
        const twinPair = isMap(twin) ? twin.items[i] : undefined;
        visit(pair.value, twinPair !== undefined && isScalar(twinPair.key) && String(twinPair.key.value) === key ? twinPair.value : undefined, [...path, key]);
      });
    } else if (isScalar(node) && node.type === 'PLAIN' && isScalar(twin)) {
      const browser = node.value;
      const node11 = twin.value instanceof Date ? node.source : twin.value;
      const source = node.source ?? String(browser);
      const keptAsText =
        typeof browser === 'string' &&
        ((typeof node11 === 'boolean' && /^(y|n|yes|no|on|off)$/i.test(browser)) || /^[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+(\.[0-9_]*)?$/.test(source));
      if (!keptAsText && !Object.is(browser, node11)) {
        problems.push({ path, message: `the node reads ${source} otherwise than the editor: write it in quotes` });
        locks.push({ path, reason: 'a plain value the node reads otherwise' });
      }
    }
  };
  visit(doc.doc.contents, other.contents, []);
  return locks;
}

/** One list of steps: where it stands, its items, and the process it belongs to (null for the flow's own and the lists inside them). */
interface StepList {
  path: Path;
  items: unknown[];
  owner: string | null;
}

/** The paths inside `value` that `pattern` matches, below `at`, where `*` is any key or index. */
function matching(value: unknown, pattern: readonly string[], at: Path): Path[] {
  if (pattern.length === 0) return [at];
  const [head, ...rest] = pattern;
  if (head === '*') {
    if (Array.isArray(value)) return value.flatMap((v, i) => matching(v, rest, [...at, i]));
    if (isRecord(value)) return Object.entries(value).flatMap(([k, v]) => matching(v, rest, [...at, k]));
    return [];
  }
  return isRecord(value) && head !== undefined && head in value ? matching(value[head], rest, [...at, head]) : [];
}

/** A step entry as the rules of names and calls see it. */
interface Entry {
  path: Path;
  value: Record<string, unknown> | null;
  type?: string;
  name: string;
  defaultName: boolean;
  /** The process a call calls, when it names one of the flow. */
  calls?: string;
  owner: string | null;
}

/**
 * Draws `text`, a flow file, in `context`: its nodes with their positions, its edges and its problems, as the flow screen
 * shows them. The same text and context always give the same view. `types` are the built-in step types; a test may give
 * more. A file that is no flow, or that would be drawn with more than {@link MAX_NODES} nodes, is not readable.
 */
export function view(text: string, context: FlowContext, types: Readonly<Record<string, StepType>> = BUILT_IN): FlowView {
  const read = readFlow(text);
  if (read.kind !== 'flow') {
    return { readable: false, nodes: [], edges: [], problems: [{ path: [], message: read.kind === 'problem' ? read.message : 'the file is not a flow' }] };
  }
  const flow = read.value;
  const problems: FlowProblem[] = [];
  const locks = yamlFindings(read, text, problems);
  const lockOf = (path: Path) => locks.find((l) => within(l.path, path) || (l.covers === true && within(path, l.path)))?.reason;
  const partial = context.partial === true;
  const notFound = (what: string): Pick<FlowProblem, 'message' | 'warning'> =>
    partial ? { message: `${what}: not found in the working folder`, warning: true } : { message: what };

  if (read.documents > 1) problems.push({ path: [], message: 'the file holds a second document, which the node does not read', warning: true });
  if (flow.schemaVersion !== 1) {
    problems.push({ path: ['schemaVersion'], message: `schemaVersion is ${JSON.stringify(flow.schemaVersion ?? null)}, not 1: the editor shows this flow read-only` });
  }

  // the lists of steps: the flow's, each process's, and those inside a step whose type holds lists of steps
  const processes = isRecord(flow.processes) ? flow.processes : {};
  if (flow.processes !== undefined && !isRecord(flow.processes)) problems.push({ path: ['processes'], message: 'processes names each process with its steps' });
  const processNames = Object.keys(processes);
  const lists: StepList[] = [];
  const nested = new Map<string, StepList[]>(); // a step's pointer to the lists inside it
  const collect = (path: Path, items: unknown, owner: string | null) => {
    if (!Array.isArray(items) || items.length === 0) {
      problems.push({ path, message: 'must be a list of at least one step' });
      return;
    }
    const list: StepList = { path, items, owner };
    lists.push(list);
    items.forEach((item, i) => {
      const type = isRecord(item) && typeof item.type === 'string' ? types[item.type] : undefined;
      for (const pattern of type?.stepLists ?? []) {
        for (const at of matching(item, pattern, [])) {
          const inner = [...path, i, ...at];
          if (inner.length > MAX_PATH) {
            problems.push({ path: inner, message: `a path is at most ${MAX_PATH} steps deep` });
            continue;
          }
          const before = lists.length;
          collect(inner, at.reduce<unknown>((v, s) => (v as Record<string | number, unknown>)[s], item), owner);
          const made = lists[before];
          if (made !== undefined) nested.set(pointer([...path, i]), [...(nested.get(pointer([...path, i])) ?? []), made]);
        }
      }
    });
  };
  for (const name of processNames) {
    const process = processes[name];
    if (!isRecord(process)) problems.push({ path: ['processes', name], message: 'a process has its list of steps under steps' });
    else collect(['processes', name, 'steps'], process.steps, name);
  }
  collect(['steps'], flow.steps, null);
  for (const list of lists) {
    const node = read.doc.getIn(list.path, true);
    if (isSeq(node) && node.flow === true) {
      problems.push({ path: list.path, message: 'the list is written on one line ([ … ]): the editor shows this flow read-only', warning: true });
    }
  }

  // the rules of names and calls, as the node applies them: the processes' lists before the flow's own
  const entries = new Map<string, Entry>();
  for (const list of lists) {
    const named = new Map<string, Path>();
    list.items.forEach((item, i) => {
      const path = [...list.path, i];
      if (!isRecord(item) || typeof item.type !== 'string' || item.type === '') {
        problems.push({ path, message: 'a step is a mapping with a text type' });
        locks.push({ path, reason: 'a step that is no mapping with a text type' });
        entries.set(pointer(path), { path, value: null, name: `step ${i + 1}`, defaultName: true, owner: list.owner });
        return;
      }
      const type = item.type;
      let calls: string | undefined;
      if (type === 'process') {
        for (const key of Object.keys(item)) {
          if (key !== 'type' && key !== 'name' && key !== 'process') {
            problems.push({ path, message: `a process call takes only type, name and process, not ${key}` });
            break;
          }
        }
        if (typeof item.process === 'string' && Object.hasOwn(processes, item.process)) calls = item.process;
        else if (typeof item.process === 'string') problems.push({ path: [...path, 'process'], message: `'${item.process}' names no process of this flow` });
      }
      const written = item.name !== undefined && item.name !== null;
      const fallback = type === 'process' && typeof item.process === 'string' ? item.process : type;
      const name = written ? String(item.name) : fallback;
      const at = written ? [...path, 'name'] : path;
      const what = written ? `'${name}'` : `has no name, and its default '${name}'`;
      if (name.length > MAX_NAME) problems.push({ path: at, message: `${what} is longer than the ${MAX_NAME} characters of a step name` });
      else if (name === 'ingress') problems.push({ path: at, message: `${what} is the name of the arrival in a message's history` });
      else if (named.has(name)) problems.push({ path: at, message: `${what} is the name of ${place(named.get(name)!)} too: step names are unique in a list` });
      if (!named.has(name)) named.set(name, path);
      entries.set(pointer(path), { path, value: item, type, name, defaultName: !written, ...(calls === undefined ? {} : { calls }), owner: list.owner });
    });
  }
  const callsOf = (owner: string | null) => lists.filter((l) => l.owner === owner).flatMap((l) => l.items.map((_, i) => entries.get(pointer([...l.path, i]))!)).filter((e) => e.calls !== undefined);

  // processes inside each other, as the node follows them: from the flow's calls, then from each process not reached
  const loops = new Set<string>();
  const heights = new Map<string, number>();
  const nesting = (calls: Entry[], calling: string[]): number => {
    let most = 0;
    for (const call of calls) {
      const process = call.calls!;
      let height = heights.get(process);
      if (height === undefined) {
        const at = calling.indexOf(process);
        if (at >= 0) {
          problems.push({ path: [...call.path, 'process'], message: `process '${process}' calls itself: ${[...calling.slice(at), process].join(' -> ')}` });
          loops.add(pointer(call.path));
          continue;
        }
        if (calling.length === MAX_NESTING) {
          problems.push({ path: [...call.path, 'process'], message: tooDeep([...calling, process]) });
          continue;
        }
        calling.push(process);
        height = 1 + nesting(callsOf(process), calling);
        calling.pop();
        heights.set(process, height);
      }
      if (calling.length + height > MAX_NESTING) {
        problems.push({ path: [...call.path, 'process'], message: tooDeep([...calling, process]) });
        continue;
      }
      most = Math.max(most, height);
    }
    return most;
  };
  nesting(callsOf(null), []);
  for (const name of processNames) if (!heights.has(name)) heights.set(name, 1 + nesting(callsOf(name), [name]));

  // the names each process runs under: the call chains that reach it, at most 8 deep, no loop followed
  const runs = new Map<string | null, { count: number; names: string[] }>([[null, { count: 1, names: [''] }]]);
  let layer = new Map<string | null, { count: number; names: string[] }>([[null, { count: 1, names: [''] }]]);
  for (let depth = 0; depth < MAX_NESTING; depth++) {
    const next = new Map<string | null, { count: number; names: string[] }>();
    for (const [owner, reach] of layer) {
      for (const call of callsOf(owner)) {
        if (loops.has(pointer(call.path))) continue;
        const to = next.get(call.calls!) ?? { count: 0, names: [] };
        to.count += reach.count;
        for (const prefix of reach.names) if (to.names.length < MAX_RUNS_AS) to.names.push(`${prefix}${call.name}/`);
        next.set(call.calls!, to);
      }
    }
    for (const [process, reach] of next) {
      const all = runs.get(process) ?? { count: 0, names: [] };
      all.count += reach.count;
      for (const name of reach.names) if (all.names.length < MAX_RUNS_AS) all.names.push(name);
      runs.set(process, all);
    }
    layer = next;
  }
  const runsOf = (entry: Entry) => {
    const reach = runs.get(entry.owner) ?? { count: 0, names: [] };
    return { runs: reach.count, runsAs: reach.names.slice(0, MAX_RUNS_AS).map((prefix) => `${prefix}${entry.name}`) };
  };

  // a process splits the rest of each list that calls it when its own list holds a split, or a call of one that does
  const splitsIn = new Map<string, string | null>();
  const splitOf = (process: string, seen: Set<string>): string | null => {
    if (splitsIn.has(process)) return splitsIn.get(process)!;
    if (seen.has(process)) return null;
    seen.add(process);
    let found: string | null = null;
    const own = processes[process];
    for (const item of isRecord(own) && Array.isArray(own.steps) ? own.steps : []) {
      if (!isRecord(item)) continue;
      if (item.type === 'split' && typeof item.xpath === 'string') found = item.xpath;
      else if (item.type === 'process' && typeof item.process === 'string' && Object.hasOwn(processes, item.process)) found = splitOf(item.process, seen);
      if (found !== null) break;
    }
    splitsIn.set(process, found);
    return found;
  };

  const served = Array.isArray(context.steps) ? new Map(context.steps.map((s) => [s.type, s.configSchema])) : null;
  const outEndpoints = context.endpoints.filter((e) => e.direction === 'out').map((e) => e.id);
  const endpointOf = (id: unknown) => (typeof id === 'string' ? context.endpoints.find((e) => e.id === id) : undefined);

  /** The flow a `to` of the endpoint `id` calls, through a direct endpoint, with a problem when the call cannot be followed. */
  const calledFlow = (id: string, at: Path) => {
    const endpoint = endpointOf(id);
    if (endpoint === undefined || endpoint.scheme !== 'direct' || endpoint.direction !== 'out') return { detail: undefined, flow: undefined };
    if (endpoint.overlaid === true) return { detail: 'an environment changes its address: no flow is linked', flow: undefined };
    if (endpoint.address === undefined) return { detail: undefined, flow: undefined };
    const serving = context.flows.filter((f) => f.address === endpoint.address);
    if (serving.length === 1) return { detail: undefined, flow: serving[0] };
    if (serving.length === 0) problems.push({ path: at, ...notFound(`no flow serves the direct address ${endpoint.address}`) });
    else problems.push({ path: at, message: `the direct address ${endpoint.address} is served by ${serving.map((f) => f.id).join(' and ')}: no flow is linked`, warning: true });
    return { detail: undefined, flow: undefined };
  };

  /** A reference of a `to` or `from` to an endpoint of the package, with its problem. */
  const referTo = (id: unknown, at: Path, direction: 'in' | 'out') => {
    if (typeof id !== 'string') return undefined;
    const endpoint = endpointOf(id);
    if (endpoint === undefined) problems.push({ path: at, ...notFound(`'${id}' names no endpoint of the package`) });
    else if (endpoint.direction !== direction) problems.push({ path: at, message: `'${id}' is an ${endpoint.direction} endpoint; ${direction === 'in' ? 'a flow listens on an in endpoint' : 'a to sends to an out endpoint'}` });
    return endpoint;
  };

  /** The form of a step, and its problems. */
  const formOf = (entry: Entry): StepForm | undefined => {
    if (entry.value === null || entry.type === undefined) return undefined;
    const values = entry.value;
    problems.push(...checkStepTab(values, entry.path));
    const builtIn = types[entry.type];
    if (builtIn !== undefined) {
      const fields = builtIn.fields.map((f) => withChoices(f, outEndpoints, processNames));
      const settings = settingsOf(fields, builtIn.rules);
      // a process call's keys are judged by the rules of calls above
      problems.push(...checkStep(settings, values, entry.path, false, builtIn.check));
      return { path: entry.path, settings, values };
    }
    if (served === null) {
      return { path: entry.path, settings: UNDESCRIBED, values, missing: `The node's step types could not be read: ${(context.steps as { missing: string }).missing}` };
    }
    if (!served.has(entry.type)) {
      problems.push({ path: [...entry.path, 'type'], message: `the node serves no step of the type ${entry.type}`, warning: true });
      return { path: entry.path, settings: UNDESCRIBED, values, missing: `The node serves no step of the type ${entry.type}.` };
    }
    const described = describeSchema(served.get(entry.type));
    if (described === null) return { path: entry.path, settings: UNDESCRIBED, values, missing: `The step type ${entry.type} describes no settings.` };
    problems.push(...checkStep(described.settings, values, entry.path, described.closed));
    return { path: entry.path, settings: described.settings, values };
  };

  // the nodes, the edges and the blocks of the layout
  const nodes: FlowNode[] = [];
  const edges: FlowEdge[] = [];
  const node = (n: Omit<FlowNode, 'x' | 'y' | 'runsAs' | 'runs' | 'defaultName'> & Partial<Pick<FlowNode, 'runsAs' | 'runs' | 'defaultName'>>): FlowNode => {
    const made: FlowNode = { runsAs: [], runs: 0, defaultName: false, ...n, x: 0, y: 0 };
    const locked = n.kind === 'step' || n.kind === 'from' || n.kind === 'destination' ? lockOf(n.path) : undefined;
    if (locked !== undefined) made.locked = `The file holds ${locked} here, which the editor does not change.`;
    nodes.push(made);
    return made;
  };
  const edge = (source: string, target: string, kind: FlowEdge['kind'], extra: Pick<FlowEdge, 'label' | 'insertAt'> = {}) => {
    edges.push({ id: `${source}->${target}`, source, target, kind, ...extra });
  };
  /** Where an edge leaves from, and of which kind it is. */
  type Exit = { from: string; kind: FlowEdge['kind']; label?: string };
  const join = (exits: Exit[], target: string, insertAt?: Path) => {
    for (const exit of exits) {
      edge(exit.from, target, exit.kind, {
        ...(exit.label === undefined ? {} : { label: exit.label }),
        ...(insertAt === undefined || exit.kind === 'choice' ? {} : { insertAt }),
      });
    }
  };

  /** The blocks of the list at `path` from its item `from` on, drawn in `parent`; `exits` lead into its first node. */
  const drawList = (list: StepList, from: number, exits: Exit[], parent: string | undefined): Block[] => {
    const blocks: Block[] = [];
    let into = exits;
    for (let i = from; i < list.items.length; i++) {
      const path = [...list.path, i];
      const id = pointer(path);
      const entry = entries.get(id)!;
      const value = entry.value ?? {};
      const type = entry.type;
      const form = formOf(entry);
      const builtIn = type === undefined ? undefined : types[type];
      const detailKey = builtIn?.detail ?? (type === undefined ? undefined : ['stylesheet', 'schema', 'filter', 'mapping'].find((k) => typeof value[k] === 'string'));
      const base = {
        id,
        kind: 'step' as const,
        path,
        label: entry.name,
        defaultName: entry.defaultName,
        ...runsOf(entry),
        ...(type === undefined ? {} : { type }),
        ...(parent === undefined ? {} : { parent }),
        ...(form === undefined ? {} : { form }),
        ...SIZE.step,
      };
      const detail = detailKey !== undefined && typeof value[detailKey] === 'string' ? cut(String(value[detailKey])) : undefined;
      join(into, id, path);

      if (type === 'route') {
        const rules = Array.isArray(value.routes) ? value.routes : [];
        const rows = rules.map((r) => (isRecord(r) ? `${cut(String(r.when ?? ''), 32)} → ${String(r.set ?? '')}` : '?'));
        if (value.otherwise !== undefined) rows.push(`otherwise → ${String(value.otherwise)}`);
        const shown = rows.slice(0, MAX_ROWS);
        const n = node({ ...base, detail: `sets ${String(value.prop ?? '?')}`, rows: shown, height: SIZE.step.height + SIZE.row * shown.length });
        blocks.push({ kind: 'node', node: n });
        into = [{ from: n.id, kind: 'next' }];
        continue;
      }

      if (type === 'to' && value.endpoint === undefined && isRecord(value.endpoints)) {
        const n = node({ ...base, detail: `by ${String(value.byProp ?? '?')}` });
        blocks.push({ kind: 'node', node: n });
        const labels = choiceLabels(list, i, value, problems);
        const columns: Block[] = [];
        const exitsHere: Exit[] = [];
        for (const [key, target] of Object.entries(value.endpoints)) {
          const at = [...path, 'endpoints', key];
          const endpoint = referTo(target, at, 'out');
          const call = typeof target === 'string' ? calledFlow(target, at) : { detail: undefined, flow: undefined };
          const d = node({
            id: pointer(at),
            kind: 'destination',
            path: at,
            label: String(target),
            ...(endpoint === undefined ? {} : { detail: call.detail ?? endpoint.scheme, opens: { endpoint: endpoint.path } }),
            ...(parent === undefined ? {} : { parent }),
            ...SIZE.destination,
          });
          edge(n.id, d.id, 'choice', { label: labels.get(key) ?? key });
          columns.push(call.flow === undefined ? { kind: 'node', node: d } : { kind: 'beside', main: d, side: calls(d, call.flow, at, parent) });
          exitsHere.push({ from: d.id, kind: 'join' });
        }
        blocks.push({ kind: 'fork', columns });
        into = exitsHere;
        continue;
      }

      if (type === 'to') {
        const endpoint = referTo(value.endpoint, [...path, 'endpoint'], 'out');
        const call = typeof value.endpoint === 'string' ? calledFlow(value.endpoint, path) : { detail: undefined, flow: undefined };
        const n = node({
          ...base,
          ...(typeof value.endpoint === 'string' ? { detail: cut(`${value.endpoint}${endpoint === undefined ? '' : ` · ${call.detail ?? endpoint.scheme}`}`) } : {}),
          ...(endpoint === undefined ? {} : { opens: { endpoint: endpoint.path } }),
        });
        blocks.push(call.flow === undefined ? { kind: 'node', node: n } : { kind: 'beside', main: n, side: calls(n, call.flow, path, parent) });
        into = [{ from: n.id, kind: 'next' }];
        continue;
      }

      if (type === 'filter') {
        const n = node({ ...base, ...(detail === undefined ? {} : { detail }) });
        const end = node({ id: `${id}#ends`, kind: 'end', path, label: 'ends here', ...(parent === undefined ? {} : { parent }), ...SIZE.end });
        edge(n.id, end.id, 'ends', { label: 'otherwise' });
        blocks.push({ kind: 'beside', main: n, side: end });
        into = [{ from: n.id, kind: 'next' }];
        continue;
      }

      const splitXpath = type === 'split' ? (typeof value.xpath === 'string' ? value.xpath : '?') : entry.calls === undefined ? null : splitOf(entry.calls, new Set());
      const n = node({
        ...base,
        ...(type === 'process' ? { detail: `runs process ${String(value.process ?? '?')}` } : detail === undefined ? {} : { detail }),
      });
      const lane = pointer(['processes', entry.calls ?? '', 'steps']);
      if (entry.calls !== undefined && lists.some((l) => pointer(l.path) === lane)) edge(n.id, lane, 'process');
      const inner = nested.get(id) ?? [];
      if (inner.length > 0) {
        blocks.push({ kind: 'node', node: n });
        const columns: Block[] = [];
        const exitsHere: Exit[] = [];
        for (const branch of inner) {
          const label = branch.path.slice(path.length, -1).join(' ');
          const column = drawList(branch, 0, [{ from: n.id, kind: 'choice', label }], parent);
          columns.push({ kind: 'column', blocks: column });
          exitsHere.push({ from: `${pointer(branch.path)}#end`, kind: 'join' });
        }
        blocks.push({ kind: 'fork', columns });
        into = exitsHere;
        continue;
      }
      blocks.push({ kind: 'node', node: n });
      if (splitXpath !== null && splitXpath !== undefined) {
        const frameId = `${id}#each`;
        const frame = node({ id: frameId, kind: 'group', path, label: `each part of ${cut(splitXpath)}`, ...(parent === undefined ? {} : { parent }), width: 0, height: 0 });
        const rest = drawList(list, i + 1, [{ from: n.id, kind: 'each' }], frameId);
        blocks.push({ kind: 'frame', node: frame, inner: { kind: 'column', blocks: rest } });
        return blocks;
      }
      into = [{ from: n.id, kind: 'next' }];
    }
    const end = node({
      id: `${pointer(list.path)}#end`,
      kind: 'end',
      path: list.path,
      label: list.path.length === 1 ? 'end' : list.path.length === 3 && list.path[0] === 'processes' ? 'back to the call' : 'joins',
      ...(parent === undefined ? {} : { parent }),
      ...SIZE.end,
    });
    join(into, end.id, [...list.path, list.items.length]);
    blocks.push({ kind: 'node', node: end });
    return blocks;
  };

  /** The node of the flow `flow` that the node `caller` calls, beside it. */
  const calls = (caller: FlowNode, called: FlowContext['flows'][number], at: Path, parent: string | undefined): FlowNode => {
    const n = node({
      id: `${pointer(at)}#calls`,
      kind: 'flow',
      path: at,
      label: called.id,
      detail: called.mode === 'sync' ? 'waits for its reply' : 'hands the message over',
      opens: { flow: called.path },
      ...(parent === undefined ? {} : { parent }),
      ...SIZE.flow,
    });
    edge(caller.id, n.id, 'calls', { label: `calls flow ${called.id}` });
    return n;
  };

  const from = isRecord(flow.from) ? flow.from : {};
  const inbound = referTo(from.endpoint, ['from', 'endpoint'], 'in');
  const fromNode = node({
    id: pointer(['from']),
    kind: 'from',
    path: ['from'],
    label: typeof from.endpoint === 'string' ? from.endpoint : 'no endpoint',
    ...(inbound === undefined ? {} : { detail: inbound.scheme === 'direct' && inbound.address !== undefined ? `direct · ${inbound.address}` : inbound.scheme, opens: { endpoint: inbound.path } }),
    ...SIZE.from,
  });
  const main = lists.find((l) => l.owner === null && l.path.length === 1);
  const lanes: Block[] = [];
  lanes.push({ kind: 'column', blocks: [{ kind: 'node', node: fromNode }, ...(main === undefined ? [] : drawList(main, 0, [{ from: fromNode.id, kind: 'next' }], undefined))] });
  for (const name of processNames) {
    const list = lists.find((l) => l.owner === name && l.path.length === 3);
    if (list === undefined) continue;
    const laneId = pointer(list.path);
    const lane = node({ id: laneId, kind: 'lane', path: list.path, label: name, width: 0, height: 0 });
    lanes.push({ kind: 'frame', node: lane, inner: { kind: 'column', blocks: drawList(list, 0, [], laneId) } });
  }

  if (nodes.length > MAX_NODES) {
    problems.push({ path: [], message: `the flow would be drawn with ${nodes.length} nodes, more than the ${MAX_NODES} the editor draws` });
    return { readable: false, nodes: [], edges: [], problems };
  }
  layout(lanes);
  // a frame is listed before the nodes drawn in it, so that it is drawn below them
  const order = (n: FlowNode) => (n.kind === 'lane' ? 0 : n.kind === 'group' ? 1 : 2);
  const sorted = nodes.map((n, i) => ({ n, i })).sort((a, b) => order(a.n) - order(b.n) || a.i - b.i).map(({ n }) => n);
  return { readable: true, nodes: sorted, edges, problems };
}

function tooDeep(chain: string[]): string {
  return `processes run inside each other at most ${MAX_NESTING} deep, and this call goes deeper: ${chain.join(' -> ')}`;
}

/** `field` with the choices of the references it makes: the out endpoints of the package, or the processes of the flow. */
function withChoices(field: StepField, endpoints: string[], processes: string[]): StepField {
  if (field.type === 'map') return { ...field, value: withChoices(field.value, endpoints, processes) };
  if (field.type === 'list') return { ...field, item: field.item.map((f) => withChoices(f, endpoints, processes)) };
  if ('reference' in field && field.reference === 'endpoint') return { ...field, choices: endpoints };
  if ('reference' in field && field.reference === 'process') return { ...field, choices: processes };
  return field;
}

/**
 * The label of each choice of the `to` at `index` of `list`, by its value: the value, and what makes it taken when the
 * file makes that certain. Walking back in its list, the first `route` that sets its prop must have an `otherwise`, and
 * no step between them may be a process call or a step of a module or plugin. Then a value the route sets that no entry
 * covers is a problem, and an entry it never sets is never taken.
 */
function choiceLabels(list: StepList, index: number, to: Record<string, unknown>, problems: FlowProblem[]): Map<string, string> {
  const labels = new Map<string, string>();
  const entries = isRecord(to.endpoints) ? Object.keys(to.endpoints) : [];
  let route: Record<string, unknown> | undefined;
  for (let i = index - 1; i >= 0; i--) {
    const step = list.items[i];
    if (!isRecord(step) || typeof step.type !== 'string' || step.type === 'process' || !(step.type in BUILT_IN)) return labels;
    if (step.type === 'route' && step.prop === to.byProp) {
      if (typeof step.otherwise === 'string') route = step;
      break;
    }
  }
  if (route === undefined) return labels;
  const rules = (Array.isArray(route.routes) ? route.routes : []).filter(isRecord);
  for (const key of entries) {
    const when = rules.filter((r) => r.set === key).map((r) => cut(String(r.when), 40));
    const reasons = [...(when.length > 0 ? [`when ${when.join(' or ')}`] : []), ...(route.otherwise === key ? ['otherwise'] : [])];
    labels.set(key, `${key} · ${reasons.length > 0 ? reasons.join(', ') : 'never taken'}`);
  }
  const sets = [...rules.map((r) => r.set), route.otherwise].filter((v): v is string => typeof v === 'string');
  for (const value of new Set(sets)) {
    if (!entries.includes(value)) {
      problems.push({ path: [...list.path, index, 'endpoints'], message: `the route ${String(route.name ?? 'route')} sets ${String(to.byProp)} to ${value}, which no entry of endpoints covers` });
    }
  }
  return labels;
}


