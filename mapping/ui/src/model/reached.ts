import type { AuthoringId, BoxReference, TargetFieldMapping } from '../generated/mapping-model';
import { inputsOf } from './command';

/**
 * The boxes a target field mapping's `result` reaches through the input slots, each once, in the order a depth-first
 * walk from `result` meets them: a box before its inputs, the inputs in slot order. Any other box is an unreachable box
 * (ADR-0013), so a mapping whose `result` is null reaches none. The walk stops at an empty input slot, a box it has met
 * (a model written by hand may hold a cycle) and a reference to no box.
 */
export function reachedBoxes(mapping: TargetFieldMapping | undefined): AuthoringId[] {
  const reached: AuthoringId[] = [];
  const met = new Set<AuthoringId>();
  const pending: BoxReference[] = [mapping?.result ?? null];
  while (pending.length > 0) {
    const id = pending.pop()!;
    if (id === null || met.has(id) || !Object.hasOwn(mapping!.boxes, id)) continue;
    met.add(id);
    reached.push(id);
    // pushed last slot first, so the first slot is walked first
    pending.push(...[...inputsOf(mapping!, id)].reverse());
  }
  return reached;
}
