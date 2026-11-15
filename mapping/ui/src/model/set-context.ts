import { produce } from 'immer';
import type { AuthoringId, FieldPath, MappingModel, SourceFieldBox } from '../generated/mapping-model';
import { applied, refused, refuseReference, type CommandResult } from './command';
import { fieldPathSteps } from './field-path';

/** Sets the context of source-field box `box` to the field `context`, from the inspector's context picker. */
export interface SetContextCommand {
  targetField: FieldPath;
  box: AuthoringId;
  context: FieldPath;
}

/** The proper ancestors of a field, by its path text as the model check reads it: the parent first, the root last. */
export function properAncestors(field: FieldPath): FieldPath[] {
  const steps = fieldPathSteps(field);
  return steps.slice(0, -1).map((_, i, above) => `/${above.slice(0, above.length - i).join('/')}`);
}

/**
 * Sets a source-field box's context (GLOSSARY: Context). A context equal to the parent is left out (ADR-0010). The
 * command refuses, at the box:
 * - `cross-target-field-reference` or `unknown-box-reference` when the box is not in the target field's mapping;
 * - `context-not-ancestor` when `context` is not a proper ancestor of the box's field, or the box reads no field.
 */
export function setContext(model: MappingModel, command: SetContextCommand): CommandResult {
  const { targetField, box, context } = command;
  const refusal = refuseReference(model, targetField, box);
  if (refusal) return refusal;
  const target = model.targetFieldMappings![targetField]!.boxes[box]!;
  const ancestors = target.kind === 'source' ? properAncestors(target.field) : [];
  if (!ancestors.includes(context)) return refused('context-not-ancestor', box);

  const after = context === ancestors[0] ? undefined : context;
  if ((target as SourceFieldBox).context === after) return applied(model);

  return applied(
    produce(model, (draft) => {
      const changed = draft.targetFieldMappings![targetField]!.boxes[box] as SourceFieldBox;
      if (after === undefined) delete changed.context;
      else changed.context = after;
    }),
  );
}
