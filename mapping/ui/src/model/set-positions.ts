import { produce } from 'immer';
import type { BoxPosition, FieldPath, Layout, MappingModel } from '../generated/mapping-model';
import { applied, refuseReference, type CommandResult } from './command';

/** Writes box positions and the target's position into a target field mapping's layout (ADR-0029). */
export interface SetPositionsCommand {
  targetField: FieldPath;
  /** The positions to write, by authoring id, and the target's; each coordinate is rounded. */
  layout: Layout;
}

const rounded = ({ x, y }: BoxPosition): BoxPosition => ({ x: Math.round(x), y: Math.round(y) });
const samePosition = (a: BoxPosition | undefined, b: BoxPosition) => a !== undefined && a.x === b.x && a.y === b.y;

/**
 * Sets each given position, rounded to integers so that a sub-pixel drag never changes the file (ADR-0029), and keeps
 * the positions it is not given. Positions equal to the kept ones give back the model itself. A target field without a
 * mapping has no layout to hold a position, so the model stays as it was. Refuses a box the mapping does not have as
 * `cross-target-field-reference` or `unknown-box-reference`, and then writes no position.
 */
export function setPositions(model: MappingModel, command: SetPositionsCommand): CommandResult {
  const { targetField, layout } = command;
  const mapping = model.targetFieldMappings?.[targetField];
  if (mapping === undefined) return applied(model);
  const boxes = Object.entries(layout.boxes ?? {});
  for (const [id] of boxes) {
    const refusal = refuseReference(model, targetField, id);
    if (refusal) return refusal;
  }

  return applied(
    produce(model, (draft) => {
      const changed = draft.targetFieldMappings![targetField]!;
      if (layout.target) {
        const target = rounded(layout.target);
        if (!samePosition(changed.layout?.target, target)) (changed.layout ??= {}).target = target;
      }
      for (const [id, position] of boxes) {
        const box = rounded(position);
        if (!samePosition(changed.layout?.boxes?.[id], box)) ((changed.layout ??= {}).boxes ??= {})[id] = box;
      }
    }),
  );
}
