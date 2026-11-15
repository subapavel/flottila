import { produce } from 'immer';
import type { MappingModel } from '../generated/mapping-model';
import { applied, type CommandResult } from './command';

/** Sets the mapping description (GLOSSARY), the free text the engine ignores (ADR-0029). */
export interface SetDescriptionCommand {
  description: string;
}

/**
 * Sets the description; an empty one removes the member, as the canonical writer leaves an empty description out
 * (ADR-0029). The same description gives back the same model. The 4,000 code point limit is the service's to check.
 */
export function setDescription(model: MappingModel, { description }: SetDescriptionCommand): CommandResult {
  if ((model.description ?? '') === description) return applied(model);
  return applied(
    produce(model, (draft) => {
      if (description === '') delete draft.description;
      else draft.description = description;
    }),
  );
}
