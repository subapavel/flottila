import type { MappingModel } from '../generated/mapping-model';

/**
 * Whether two mapping models hold the same content: equal JSON values, whatever the key order, with a member that is
 * `undefined` counted as absent (JSON has no `undefined`). "Unsaved" is the working copy not being the same as the
 * baseline (ADR-0013).
 */
export function sameModel(a: MappingModel, b: MappingModel): boolean {
  return sameValue(a, b);
}

function sameValue(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((item, i) => sameValue(item, b[i]));
  }
  const ka = presentKeys(a);
  const kb = presentKeys(b);
  const record = (o: object) => o as Record<string, unknown>;
  return ka.length === kb.length && ka.every((k) => sameValue(record(a)[k], record(b)[k]));
}

function presentKeys(o: object): string[] {
  return Object.entries(o)
    .filter(([, v]) => v !== undefined)
    .map(([k]) => k);
}
