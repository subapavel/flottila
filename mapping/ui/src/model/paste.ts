import { produce } from 'immer';
import type { AuthoringId, Box, FieldPath, MappingModel } from '../generated/mapping-model';
import { applied, refused, type CommandResult } from './command';

/** Pastes copied boxes into the target field mapping of a target field (ADR-0010, "Authoring ids"). */
export interface PasteCommand {
  targetField: FieldPath;
  /** The fragment: the copied boxes by the ids they had when copied. */
  boxes: Readonly<Record<AuthoringId, Box>>;
  /** Draws the fresh id of a copied box; called once per box. */
  freshId: (copied: AuthoringId) => AuthoringId;
  /** The id of the new target field mapping; used only when the target field has none. */
  mappingId: AuthoringId;
}

/**
 * Adds each copied box under a fresh id. An input that reads a copied box reads its copy; an input that reads any
 * other box becomes `null`, as does nothing else. `result` stays; a target field without a mapping gets
 * `{id, result: null, boxes}`. An empty fragment leaves the model as it was.
 *
 * Ids are never reused: the command refuses as `duplicate-authoring-id` a fresh id that names a box or a target field
 * mapping anywhere in the model, that is a copied id, or that was drawn before, and a new mapping id that is any of
 * these or a fresh id.
 */
export function paste(model: MappingModel, command: PasteCommand): CommandResult {
  const { targetField, boxes, freshId, mappingId } = command;
  const copied = Object.keys(boxes);
  if (copied.length === 0) return applied(model);

  const used = new Set<AuthoringId>(copied);
  for (const mapping of Object.values(model.targetFieldMappings ?? {})) {
    used.add(mapping.id);
    for (const id of Object.keys(mapping.boxes)) used.add(id);
  }
  const fresh = new Map<AuthoringId, AuthoringId>();
  for (const id of copied) {
    const drawn = freshId(id);
    if (used.has(drawn)) return refused('duplicate-authoring-id', drawn);
    used.add(drawn);
    fresh.set(id, drawn);
  }
  if (model.targetFieldMappings?.[targetField] === undefined && used.has(mappingId)) return refused('duplicate-authoring-id', mappingId);

  return applied(
    produce(model, (draft) => {
      const mapping = ((draft.targetFieldMappings ??= {})[targetField] ??= { id: mappingId, result: null, boxes: {} });
      for (const id of copied) {
        const box = boxes[id]!;
        mapping.boxes[fresh.get(id)!] =
          box.kind === 'source' || box.inputs === undefined
            ? box
            : { ...box, inputs: box.inputs.map((input) => (input === null ? null : (fresh.get(input) ?? null))) };
      }
    }),
  );
}
