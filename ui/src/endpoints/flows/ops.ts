import { edit, EditRefused, follow, placeOf, type Op } from './edit';
import { STEP_KEYS, STEP_NAME } from './steps';
import type { FlowContext, FlowNode, FlowView, Path } from './types';

/** A step's name is at most this long. */
const MAX_NAME = 64;

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

const samePath = (a: Path, b: Path) => a.length === b.length && a.every((s, i) => b[i] === s);

const valueAt = (value: unknown, keys: readonly string[]) => keys.reduce<unknown>((v, k) => (isRecord(v) ? v[k] : undefined), value);

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/** The name a step of `type` has when it is given none: the process a call calls, else its type. */
export const defaultName = (type: string, settings: Record<string, unknown> = {}) =>
  type === 'process' && typeof settings.process === 'string' ? settings.process : type;

/**
 * The operations that add a step of `type` at `at`, an edge's `insertAt`, with `settings`. Its keys come in this order:
 * `type`, `name`, its own settings in the order of `fields` (the fields of its form), any other settings, `retryable`,
 * `recording`. When it has no name and its default name is taken in its list, it is named `<default>-<n>` with the
 * smallest free n from 2, the default cut so that the name fits in 64 characters.
 */
export function addStep(view: FlowView, at: Path, type: string, settings: Record<string, unknown> = {}, fields: readonly string[] = []): Op[] {
  return [{ op: 'add', path: at, value: stepValue(stepsIn(view, at.slice(0, -1)), type, settings, fields) }];
}

/** The steps of the list at `list`, in its order. */
const stepsIn = (view: FlowView, list: Path): FlowNode[] =>
  view.nodes
    .filter((n) => n.kind === 'step' && typeof n.path.at(-1) === 'number' && samePath(n.path.slice(0, -1), list))
    .sort((a, b) => (a.path.at(-1) as number) - (b.path.at(-1) as number));

/** A new step of `type` with `settings`, among the steps `siblings` of its list, as {@link addStep} writes it. */
function stepValue(siblings: FlowNode[], type: string, settings: Record<string, unknown>, fields: readonly string[]): Record<string, unknown> {
  const taken = new Set(siblings.map((n) => n.label));
  const value: Record<string, unknown> = { type };
  if (settings.name !== undefined) value.name = settings.name;
  else {
    const base = defaultName(type, settings);
    if (taken.has(base)) {
      for (let n = 2; ; n++) {
        const suffix = `-${n}`;
        const name = `${base.slice(0, MAX_NAME - suffix.length)}${suffix}`;
        if (!taken.has(name)) {
          value.name = name;
          break;
        }
      }
    }
  }
  const own = [...fields, ...Object.keys(settings)].filter((k) => !STEP_KEYS.has(k));
  for (const k of own) if (settings[k] !== undefined && !Object.hasOwn(value, k)) value[k] = settings[k];
  for (const k of ['retryable', 'recording']) if (settings[k] !== undefined) value[k] = settings[k];
  return value;
}

/** The operations that move the step at `from` to `to`, counted after it is taken out of its list. */
export const moveStep = (from: Path, to: Path): Op[] => [{ op: 'move', from, path: to }];

/** The operations that delete the step at `path`. */
export const deleteStep = (path: Path): Op[] => [{ op: 'remove', path }];

/**
 * The operations that set the field `name` (dotted for a key inside a key: `recording.level`) of the step at `step`,
 * whose keys are `values`, to `value`. Emptying an optional field (undefined) removes its key, and the mapping that held
 * it when nothing else is left in it; a required field keeps its key, with empty text as its value. Nothing changes when
 * the value is the one the file holds.
 */
export function setField(step: Path, values: Record<string, unknown>, name: string, value: unknown, required: boolean): Op[] {
  const keys = name.split('.');
  const path = [...step, ...keys];
  const current = valueAt(values, keys);
  if (value === undefined && required) value = '';
  if (value === undefined) {
    if (current === undefined) return [];
    for (let i = 1; i < keys.length; i++) {
      const holder = valueAt(values, keys.slice(0, i));
      if (isRecord(holder) && Object.keys(holder).length === 1) return [{ op: 'remove', path: [...step, ...keys.slice(0, i)] }];
    }
    return [{ op: 'remove', path }];
  }
  if (current !== undefined) return same(current, value) ? [] : [{ op: 'replace', path, value }];
  for (let i = 1; i < keys.length; i++) {
    if (!isRecord(valueAt(values, keys.slice(0, i)))) {
      const nested = keys.slice(i).reduceRight<unknown>((v, k) => ({ [k]: v }), value);
      return [{ op: 'add', path: [...step, ...keys.slice(0, i)], value: nested }];
    }
  }
  return [{ op: 'add', path, value }];
}

/**
 * The operations that turn the keys `before` of the step at `step` into `after`, as a raw editor gives them: each key
 * left out is removed, each changed one replaced, each new one added after the others.
 */
export function setKeys(step: Path, before: Record<string, unknown>, after: Record<string, unknown>): Op[] {
  const ops: Op[] = [];
  for (const k of Object.keys(before)) if (!Object.hasOwn(after, k)) ops.push({ op: 'remove', path: [...step, k] });
  for (const [k, v] of Object.entries(after)) {
    if (!Object.hasOwn(before, k)) ops.push({ op: 'add', path: [...step, k], value: v });
    else if (!same(before[k], v)) ops.push({ op: 'replace', path: [...step, k], value: v });
  }
  return ops;
}

/** The lists of steps of the view: the flow's own, then each process's lane, by path. */
export const stepListsOf = (view: FlowView): Path[] => [['steps'], ...view.nodes.filter((n) => n.kind === 'lane').map((n) => n.path)];

/** The operations that move the step at `from` to the end of the list of steps at `list`, another list than its own. */
export const moveToList = (view: FlowView, from: Path, list: Path): Op[] => [{ op: 'move', from, path: [...list, stepsIn(view, list).length] }];

const processesOf = (flow: Record<string, unknown>) => (isRecord(flow.processes) ? Object.keys(flow.processes) : []);

/** Why `name` cannot name a new process of `flow`, or null. */
function badProcessName(flow: Record<string, unknown>, name: string): string | null {
  if (!new RegExp(`^${STEP_NAME}$`).test(name)) return `a process name matches ${STEP_NAME}`;
  if (name.length > MAX_NAME) return `a process name is at most ${MAX_NAME} characters long`;
  if (processesOf(flow).includes(name)) return `the flow has a process ${name} already`;
  return null;
}

/**
 * The operations that add the process `name` to `flow`, whose view is `view`, holding one step of `type`: a process
 * holds at least one step. `processes` is added after the flow's last key when the flow has none. Refused with
 * {@link EditRefused} when the name is no step name or is taken.
 */
export function addProcess(view: FlowView, flow: Record<string, unknown>, name: string, type: string): Op[] {
  const bad = badProcessName(flow, name);
  if (bad !== null) throw new EditRefused(bad);
  const process = { steps: [stepValue([], type, {}, [])] };
  return isRecord(flow.processes) ? [{ op: 'add', path: ['processes', name], value: process }] : [{ op: 'add', path: ['processes'], value: { [name]: process } }];
}

/** The calls of the process `name` in the view: its steps of the type process that name it. */
export const callsOf = (view: FlowView, name: string): FlowNode[] =>
  view.nodes.filter((n) => n.kind === 'step' && n.type === 'process' && n.form?.values.process === name);

/**
 * The operations that rename the process `from` of `flow` to `to`: a move of its key within `processes`, which keeps
 * its place and its comments, and a replace of `process` in every call of it. Refused with {@link EditRefused} when the
 * new name is no step name or is taken, or when a call without a name, which is named after its process, would take a
 * name another step of its list has.
 */
export function renameProcess(view: FlowView, flow: Record<string, unknown>, from: string, to: string): Op[] {
  if (to === from) return [];
  const bad = badProcessName(flow, to);
  if (bad !== null) throw new EditRefused(bad);
  const calls = callsOf(view, from);
  for (const call of calls.filter((c) => c.defaultName)) {
    const clash = stepsIn(view, call.path.slice(0, -1)).find((n) => n.label === to && !calls.some((c) => c.defaultName && c.id === n.id));
    if (clash !== undefined) throw new EditRefused(`${placeOf(call.path)} has no name and would be named ${to}, the name of ${placeOf(clash.path)} in its list`);
  }
  const move: Op = { op: 'move', from: ['processes', from], path: ['processes', to] };
  return [move, ...calls.map((c): Op => ({ op: 'replace', path: [...(follow(c.path, [move]) ?? c.path), 'process'], value: to }))];
}

/** The operations that delete the process `name` of `flow`, and `processes` with its last process. */
export const deleteProcess = (flow: Record<string, unknown>, name: string): Op[] =>
  processesOf(flow).length === 1 ? [{ op: 'remove', path: ['processes'] }] : [{ op: 'remove', path: ['processes', name] }];

/**
 * The operations that add the row `row` to the list field `name` of the step at `step`, whose keys are `values`, at
 * its end; the field is added with the row when it is not set.
 */
export function addRow(step: Path, values: Record<string, unknown>, name: string, row: unknown): Op[] {
  const items = values[name];
  return Array.isArray(items) ? [{ op: 'add', path: [...step, name, items.length], value: row }] : [{ op: 'add', path: [...step, name], value: [row] }];
}

/** The operations that move row `from` of the list field at `at` to `to`. */
export const moveRow = (at: Path, from: number, to: number): Op[] => [{ op: 'move', from: [...at, from], path: [...at, to] }];

/** The operations that remove row `index` of the list field at `at`, or the entry `index` of the map field there. */
export const removeRow = (at: Path, index: number | string): Op[] => [{ op: 'remove', path: [...at, index] }];

/**
 * The operations that add an entry to the map field `name` of the step at `step`, whose keys are `values`: its key the
 * first of `value`, `value-2`, … not taken, and `value` as its value. The field is added with the entry when it is not
 * set.
 */
export function addEntry(step: Path, values: Record<string, unknown>, name: string, value: unknown): Op[] {
  const entries = values[name];
  const taken = isRecord(entries) ? entries : {};
  let key = 'value';
  for (let n = 2; Object.hasOwn(taken, key); n++) key = `value-${n}`;
  return isRecord(entries) ? [{ op: 'add', path: [...step, name, key], value }] : [{ op: 'add', path: [...step, name], value: { [key]: value } }];
}

/** The operations that rename the key `from` of the map field at `at` to `to`, in place. */
export const renameEntry = (at: Path, from: string, to: string): Op[] => (from === to ? [] : [{ op: 'move', from: [...at, from], path: [...at, to] }]);

/** The flows a flow at `path` can call: those of the working folder whose inbound `direct` endpoint serves an address. */
export const callable = (context: Pick<FlowContext, 'flows'>, path: string) => context.flows.filter((f) => f.address !== undefined && f.path !== path);

/**
 * How the flow at `path` calls the flow `called`: through the `direct` out endpoint of its package whose address
 * `called` serves, or, when there is none, through a new one, to be created as the file `create.path` with the text
 * `create.text` before the call is added. The new endpoint is named `call-<flow id>`, with the smallest free n from 2
 * when that is taken, and goes beside the package's out endpoints, else beside its endpoints, else beside the flow.
 */
export function callOf(context: Pick<FlowContext, 'endpoints'>, path: string, called: { id: string; address?: string }): { endpoint: string; create?: { path: string; text: string } } {
  const address = called.address ?? '';
  const existing = context.endpoints.find((e) => e.scheme === 'direct' && e.direction === 'out' && e.address === address && e.overlaid !== true);
  if (existing !== undefined) return { endpoint: existing.id };
  const folderOf = (file: string) => file.slice(0, file.lastIndexOf('/') + 1);
  const near = context.endpoints.find((e) => e.direction === 'out') ?? context.endpoints[0];
  const folder = folderOf(near?.path ?? path);
  const taken = (id: string) => context.endpoints.some((e) => e.id === id || e.path === `${folder}${id}.yaml`);
  const base = `call-${called.id}`.slice(0, MAX_NAME);
  let id = base;
  for (let n = 2; taken(id); n++) id = `${base.slice(0, MAX_NAME - `-${n}`.length)}-${n}`;
  const text = edit('', { op: 'add', path: [], value: { schemaVersion: 1, kind: 'endpoint', id, scheme: 'direct', direction: 'out', config: { address } } });
  return { endpoint: id, create: { path: `${folder}${id}.yaml`, text } };
}
