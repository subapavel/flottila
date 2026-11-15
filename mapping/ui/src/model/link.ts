import type { AuthoringId, FieldPath, MappingModel } from '../generated/mapping-model';
import { refusedField, type CommandResult, type FieldRefusal } from './command';
import { insertBox } from './insert-box';

/** Drop a source field on a target field (ADR-0012, "Field links"). */
export interface LinkCommand {
  sourceField: FieldPath;
  targetField: FieldPath;
  /** The id of the new source-field box. */
  boxId: AuthoringId;
  /** The id of the new target field mapping; used only when the target field has none. */
  mappingId: AuthoringId;
  /** The refusal the source field carries in the source tree, when it is a refused field (ADR-0027). */
  sourceRefusal?: FieldRefusal;
  /** The refusal the target field carries in the target tree, when it is a refused field (ADR-0027). */
  targetRefusal?: FieldRefusal;
}

/**
 * Adds a source-field box reading `sourceField` to the target field mapping of `targetField`: the insert box command
 * with a source-field box, so the same rules for `result` and the same refusals hold. A refused source field is
 * refused first, `refused-field` on the source side; then the insert box command's refusals.
 */
export function link(model: MappingModel, command: LinkCommand): CommandResult {
  const { sourceField, sourceRefusal, ...rest } = command;
  if (sourceRefusal) return refusedField('source', sourceField, sourceRefusal);
  return insertBox(model, { ...rest, box: { kind: 'source', field: sourceField } });
}
