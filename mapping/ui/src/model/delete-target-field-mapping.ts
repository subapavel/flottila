import { produce } from 'immer';
import type { FieldPath, MappingModel } from '../generated/mapping-model';
import { applied, type CommandResult } from './command';

/** Removes the target field mapping of a target field, such as an orphaned target field mapping (GLOSSARY). */
export interface DeleteTargetFieldMappingCommand {
  targetField: FieldPath;
}

/**
 * Removes the target field mapping with all its boxes; no other mapping can read them (ADR-0013). `targetFieldMappings`
 * goes with the last one, as the canonical writer leaves an empty member out. A target field without a mapping leaves
 * the model as it is.
 */
export function deleteTargetFieldMapping(model: MappingModel, command: DeleteTargetFieldMappingCommand): CommandResult {
  const { targetField } = command;
  if (!Object.hasOwn(model.targetFieldMappings ?? {}, targetField)) return applied(model);
  return applied(
    produce(model, (draft) => {
      const all = draft.targetFieldMappings!;
      delete all[targetField];
      if (Object.keys(all).length === 0) delete draft.targetFieldMappings;
    }),
  );
}
