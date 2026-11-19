import type { Op } from './edit';
import { STEP_KEYS } from './steps';
import type { FlowView, Path } from './types';

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
  const list = at.slice(0, -1);
  const taken = new Set(view.nodes.filter((n) => n.kind === 'step' && typeof n.path.at(-1) === 'number' && samePath(n.path.slice(0, -1), list)).map((n) => n.label));
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
  return [{ op: 'add', path: at, value }];
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
