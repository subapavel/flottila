import { produce } from 'immer';
import type { AuthoringId, FieldPath, MappingModel } from '../generated/mapping-model';
import { applied, refuseReference, type CommandResult } from './command';
import { setResult, unwire, wire } from './wire';

/** Where a wire ends: an input slot of a box, or the target field itself (the mapping's `result`). */
export type WireEnd = { box: AuthoringId; slot: number } | 'target';

/** Moves one end of the wire from box `source` into `from` so that `source` feeds `to` instead. */
export interface RewireCommand {
  targetField: FieldPath;
  source: AuthoringId;
  from: WireEnd;
  to: WireEnd;
}

const sameEnd = (a: WireEnd, b: WireEnd) => (a === 'target' || b === 'target' ? a === b : a.box === b.box && a.slot === b.slot);

/** `source` into `end`: the wire command into a slot, the set result command into the target. */
const connect = (model: MappingModel, targetField: FieldPath, source: AuthoringId, end: WireEnd) =>
  end === 'target' ? setResult(model, { targetField, box: source }) : wire(model, { targetField, source, target: end.box, slot: end.slot });

/**
 * Leaves `from` unconnected and wires `source` into `to`, as one model change: the unwire (or set result) command, then
 * the wire (or set result) command, whose rules hold, so an occupied `to` is replaced. When `to` is `from`, only the
 * connection is made, so a wire dropped where it started changes nothing. A refusal of either step refuses the whole and
 * the old wire stays.
 */
export function rewire(model: MappingModel, command: RewireCommand): CommandResult {
  const { targetField, source, from, to } = command;
  let start = model;
  if (!sameEnd(from, to)) {
    const disconnected = from === 'target' ? setResult(model, { targetField, box: null }) : unwire(model, { targetField, target: from.box, slot: from.slot });
    if (disconnected.kind === 'refused') return disconnected;
    start = disconnected.model;
  }
  return connect(start, targetField, source, to);
}

/** Removes every wire of one box (the node menu's Disconnect). */
export interface DisconnectBoxCommand {
  targetField: FieldPath;
  box: AuthoringId;
}

/**
 * Leaves unconnected every input that reads `box`, the `result` when it names `box`, and every input slot of `box`,
 * keeping the slots. A box without wires gives back the model itself. Refuses a box the target field's mapping does not
 * have, as `wire` does.
 */
export function disconnectBox(model: MappingModel, command: DisconnectBoxCommand): CommandResult {
  const { targetField, box } = command;
  const refusal = refuseReference(model, targetField, box);
  if (refusal) return refusal;
  return applied(
    produce(model, (draft) => {
      const mapping = draft.targetFieldMappings![targetField]!;
      if (mapping.result === box) mapping.result = null;
      for (const [id, other] of Object.entries(mapping.boxes)) {
        if (other.kind === 'source' || !other.inputs) continue;
        other.inputs.forEach((input, slot) => {
          if (input !== null && (id === box || input === box)) other.inputs![slot] = null;
        });
      }
    }),
  );
}
