import { produce } from 'immer';
import type { AuthoringId, Box, FieldPath, MappingModel, SourceFieldBox, TargetFieldMapping } from '../generated/mapping-model';
import { applied, inputsOf, refused, refuseReference, type CommandResult } from './command';

/** A box with input slots: the commands below refuse or skip a source-field box before they write. */
type WithInputs = Exclude<Box, SourceFieldBox>;

/** Wires the output of box `source` into input slot `slot` of box `target`, both of one target field mapping. */
export interface WireCommand {
  targetField: FieldPath;
  source: AuthoringId;
  target: AuthoringId;
  slot: number;
}

/**
 * Sets input slot `slot` of `target` to `source`, replacing what it read; the slots before it that the model lacks
 * become `null`. One output may feed several inputs. The command refuses:
 * - `cross-target-field-reference` or `unknown-box-reference` when either box is not in the target field's mapping,
 *   the target checked first;
 * - `wrong-arity` when `target` is a source-field box, which has no input slot;
 * - `box-cycle` at `target` when `target` already feeds `source`, or is `source`.
 */
export function wire(model: MappingModel, command: WireCommand): CommandResult {
  const { targetField, source, target, slot } = command;
  const refusal = refuseReference(model, targetField, target) ?? refuseReference(model, targetField, source);
  if (refusal) return refusal;
  const mapping = model.targetFieldMappings![targetField]!;
  if (mapping.boxes[target]!.kind === 'source') return refused('wrong-arity', target);
  if (upstream(mapping, source).has(target)) return refused('box-cycle', target);

  return applied(
    produce(model, (draft) => {
      const inputs = ((draft.targetFieldMappings![targetField]!.boxes[target] as WithInputs).inputs ??= []);
      while (inputs.length < slot) inputs.push(null);
      inputs[slot] = source;
    }),
  );
}

/** `start` and every box it reads, directly or through other boxes. A hand-written model may hold a cycle. */
function upstream(mapping: TargetFieldMapping, start: AuthoringId): Set<AuthoringId> {
  const seen = new Set<AuthoringId>();
  const pending = [start];
  while (pending.length > 0) {
    const id = pending.pop()!;
    // a box met before, a reference to no box
    if (seen.has(id) || !Object.hasOwn(mapping.boxes, id)) continue;
    seen.add(id);
    for (const input of inputsOf(mapping, id)) if (input !== null) pending.push(input);
  }
  return seen;
}

/** Removes the wire into input slot `slot` of box `target`. */
export interface UnwireCommand {
  targetField: FieldPath;
  target: AuthoringId;
  slot: number;
}

/**
 * Sets input slot `slot` of `target` to `null`; a slot that reads no box leaves the model as it was. Refuses a
 * `target` that is not in the target field's mapping, as `wire` does.
 */
export function unwire(model: MappingModel, command: UnwireCommand): CommandResult {
  const { targetField, target, slot } = command;
  const refusal = refuseReference(model, targetField, target);
  if (refusal) return refusal;
  if (inputsOf(model.targetFieldMappings![targetField]!, target)[slot] == null) return applied(model);

  return applied(
    produce(model, (draft) => {
      (draft.targetFieldMappings![targetField]!.boxes[target] as WithInputs).inputs![slot] = null;
    }),
  );
}

/** Connects box `box` to the sink of the target field, or disconnects the sink with `null`. */
export interface SetResultCommand {
  targetField: FieldPath;
  box: AuthoringId | null;
}

/**
 * Sets the `result` of the target field's mapping. A box must be in that mapping (refused as `wire` refuses); `null`
 * on a target field without a mapping leaves the model as it was.
 */
export function setResult(model: MappingModel, command: SetResultCommand): CommandResult {
  const { targetField, box } = command;
  if (box !== null) {
    const refusal = refuseReference(model, targetField, box);
    if (refusal) return refusal;
  }
  return applied(
    produce(model, (draft) => {
      const mapping = draft.targetFieldMappings?.[targetField];
      if (mapping) mapping.result = box;
    }),
  );
}
