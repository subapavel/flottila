import { produce } from 'immer';
import type { MappingModel, StructureRef } from '../generated/mapping-model';
import { applied, type CommandResult } from './command';
import { sameModel } from './same-model';

/** Sets one side of the signature (GLOSSARY: Signature): the structure the Signature tab chose for it. */
export interface SetStructureCommand {
  side: 'source' | 'target';
  structure: StructureRef;
}

/**
 * Sets the side's structure reference, its bundle, root selector and fingerprint, and leaves the other side and the
 * target field mappings: a field path the new structure lacks becomes an orphaned reference, which the refresh after
 * it reports (ADR-0027). A structure equal to the side's gives back the same model.
 */
export function setStructure(model: MappingModel, { side, structure }: SetStructureCommand): CommandResult {
  const next = produce(model, (draft) => {
    draft[side] = structure;
  });
  return applied(sameModel(next, model) ? model : next);
}
