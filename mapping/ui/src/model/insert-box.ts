import { produce } from 'immer';
import type { AuthoringId, Box, FieldPath, MappingModel } from '../generated/mapping-model';
import { applied, refused, refusedField, type CommandResult, type FieldRefusal } from './command';

/** Adds a box to the target field mapping of a target field: from the palette, by quick insert or by a field link. */
export interface InsertBoxCommand {
  targetField: FieldPath;
  box: Box;
  /** The id of the new box. */
  boxId: AuthoringId;
  /** The id of the new target field mapping; used only when the target field has none. */
  mappingId: AuthoringId;
  /** The refusal the target field carries in the target tree, when it is a refused field (ADR-0027). */
  targetRefusal?: FieldRefusal;
}

/**
 * Adds `box` to the target field mapping of `targetField`, creating `{id, result, boxes}` when the target field has
 * none. The box becomes the mapping's `result` when `result` is `null` or the mapping is new; a mapping that already
 * has a result keeps it, and the new box is an unreachable box.
 *
 * Ids share one space across the mapping (ADR-0010). The command refuses, in this order:
 * - `refused-field` when the command carries a `targetRefusal`, whatever the ids;
 * - `cross-target-field-reference` when `boxId` names a box of another target field mapping;
 * - `duplicate-authoring-id` when any other id it would add is already used, or the two new ids are equal.
 */
export function insertBox(model: MappingModel, command: InsertBoxCommand): CommandResult {
  if (command.targetRefusal) return refusedField('target', command.targetField, command.targetRefusal);
  const mappings = model.targetFieldMappings ?? {};
  const newMapping = mappings[command.targetField] === undefined;
  const used = new Set<AuthoringId>();
  for (const [target, mapping] of Object.entries(mappings)) {
    used.add(mapping.id);
    for (const box of Object.keys(mapping.boxes)) {
      if (box === command.boxId && target !== command.targetField) return refused('cross-target-field-reference', box);
      used.add(box);
    }
  }
  if (used.has(command.boxId)) return refused('duplicate-authoring-id', command.boxId);
  if (newMapping && (used.has(command.mappingId) || command.mappingId === command.boxId)) {
    return refused('duplicate-authoring-id', command.mappingId);
  }

  return applied(
    produce(model, (draft) => {
      const all = (draft.targetFieldMappings ??= {});
      const mapping = (all[command.targetField] ??= { id: command.mappingId, result: null, boxes: {} });
      mapping.boxes[command.boxId] = command.box;
      mapping.result ??= command.boxId;
    }),
  );
}
