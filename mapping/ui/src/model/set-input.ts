import type { AuthoringId, FieldPath, MappingModel } from '../generated/mapping-model';
import type { CommandResult } from './command';
import { unwire, wire } from './wire';

/** Sets input slot `slot` of box `target` to box `source`, or to unconnected with `null`: the inspector's slot picker. */
export interface SetInputCommand {
  targetField: FieldPath;
  target: AuthoringId;
  slot: number;
  source: AuthoringId | null;
}

/** The wire command for a box, the unwire command for `null`, so the same refusals hold as on the canvas. */
export function setInput(model: MappingModel, command: SetInputCommand): CommandResult {
  const { source, ...slot } = command;
  return source === null ? unwire(model, slot) : wire(model, { ...slot, source });
}
