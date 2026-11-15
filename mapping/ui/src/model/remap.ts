import { produce } from 'immer';
import type { AuthoringId, FieldPath, MappingModel, SourceFieldBox } from '../generated/mapping-model';
import { applied, refused, refusedField, refuseReference, type CommandResult, type FieldRefusal } from './command';
import { properAncestors } from './set-context';

/** Points source-field box `box` at the source field `field` (GLOSSARY: Remap), by drop or from the inspector. */
export interface RemapCommand {
  targetField: FieldPath;
  box: AuthoringId;
  field: FieldPath;
  /** The refusal `field` carries in the source tree, when it is a refused field (ADR-0027). */
  sourceRefusal?: FieldRefusal;
}

/**
 * Remaps a source-field box: it reads `field` and keeps its authoring id, and every wire that reads it stays. Its
 * context is kept while it is a proper ancestor of `field` other than the parent; otherwise it is left out, so the box
 * reads in the parent's context (ADR-0010). A refused `field` is refused first, `refused-field` on the source side, as
 * link refuses it; then the command refuses, at the box:
 * - `cross-target-field-reference` or `unknown-box-reference` when the box is not in the target field's mapping;
 * - `not-source-field-box` when the box reads no source field.
 */
export function remap(model: MappingModel, command: RemapCommand): CommandResult {
  const { targetField, box, field, sourceRefusal } = command;
  if (sourceRefusal) return refusedField('source', field, sourceRefusal);
  const refusal = refuseReference(model, targetField, box);
  if (refusal) return refusal;
  const before = model.targetFieldMappings![targetField]!.boxes[box]!;
  if (before.kind !== 'source') return refused('not-source-field-box', box);
  if (before.field === field) return applied(model);

  const ancestors = properAncestors(field);
  const context = before.context !== undefined && ancestors.indexOf(before.context) > 0 ? before.context : undefined;
  return applied(
    produce(model, (draft) => {
      const after: SourceFieldBox = { kind: 'source', field };
      if (context !== undefined) after.context = context;
      draft.targetFieldMappings![targetField]!.boxes[box] = after;
    }),
  );
}
