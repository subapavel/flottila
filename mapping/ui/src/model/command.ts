import type { components } from '../generated/api';
import type { AuthoringId, BoxReference, FieldPath, MappingModel, TargetFieldMapping } from '../generated/mapping-model';

/** A refused field's refusal as `/api/structures` carries it: the reason's code and the declaration's position. */
export type FieldRefusal = components['schemas']['RefusalDto'];

/**
 * Why a command was refused at a box or mapping id, by the model check's codes (ADR-0011, ADR-0013);
 * `not-source-field-box` is the editor's own, as no model check sees a remap.
 */
export interface BoxRefusal {
  code:
    | 'duplicate-authoring-id'
    | 'cross-target-field-reference'
    | 'unknown-box-reference'
    | 'box-cycle'
    | 'wrong-arity'
    | 'context-not-ancestor'
    | 'unknown-parameter'
    | 'wrong-parameter-type'
    | 'invalid-parameter-value'
    | 'not-source-field-box';
  id: AuthoringId;
}

/** Why a command was refused at a refused field (ADR-0027): the field and the refusal it carries in its tree. */
export interface RefusedField {
  code: 'refused-field';
  side: 'source' | 'target';
  field: FieldPath;
  refusal: FieldRefusal;
}

/** Why a command was refused. */
export type Refusal = BoxRefusal | RefusedField;

/** A command's answer: the next model, or why the model stays as it was. */
export type CommandResult = { kind: 'applied'; model: MappingModel } | ({ kind: 'refused' } & Refusal);

export const applied = (model: MappingModel): CommandResult => ({ kind: 'applied', model });

export const refused = (code: BoxRefusal['code'], id: AuthoringId): CommandResult => ({ kind: 'refused', code, id });

export const refusedField = (side: RefusedField['side'], field: FieldPath, refusal: FieldRefusal): CommandResult => ({
  kind: 'refused',
  code: 'refused-field',
  side,
  field,
  refusal,
});

/** The refusal of a refused command's result: the result without its `kind`. */
export function refusalOf(result: { kind: 'refused' } & Refusal): Refusal {
  const { kind, ...refusal } = result;
  void kind;
  return refusal;
}

/** A refused field's reason and the position of the declaration that causes it, in words. */
export const describeFieldRefusal = ({ code, file, line, column }: FieldRefusal): string =>
  `${code}, declared at ${file}:${line}:${column}`;

/** What the editor tells the author about a refusal. */
export function describeRefusal(refusal: Refusal): string {
  if (refusal.code === 'refused-field') {
    return `The ${refusal.side} field ${refusal.field} is refused: ${describeFieldRefusal(refusal.refusal)}.`;
  }
  const { code, id } = refusal;
  switch (code) {
    case 'box-cycle':
      return `The wire would close a cycle: box ${id} already feeds the box it would read.`;
    case 'cross-target-field-reference':
      return `Box ${id} belongs to another target field mapping.`;
    case 'duplicate-authoring-id':
      return `The id ${id} is already used.`;
    case 'unknown-box-reference':
      return `There is no box ${id}.`;
    case 'wrong-arity':
      return `Box ${id} has no input slot there.`;
    case 'context-not-ancestor':
      return `That context is not a proper ancestor of the field box ${id} reads.`;
    case 'unknown-parameter':
      return `Box ${id} has no such parameter to set.`;
    case 'wrong-parameter-type':
      return `That value has the wrong type for the parameter of box ${id}.`;
    case 'invalid-parameter-value':
      return `That value is not one the parameter of box ${id} takes.`;
    case 'not-source-field-box':
      return `Box ${id} reads no source field, so it cannot be remapped.`;
  }
}

/**
 * Refuses a reference from the target field mapping of `targetField` to box `id` unless that mapping has the box:
 * `cross-target-field-reference` when another mapping has it, else `unknown-box-reference`.
 */
export function refuseReference(model: MappingModel, targetField: FieldPath, id: AuthoringId): CommandResult | undefined {
  if (Object.hasOwn(model.targetFieldMappings?.[targetField]?.boxes ?? {}, id)) return undefined;
  const elsewhere = Object.values(model.targetFieldMappings ?? {}).some((mapping) => Object.hasOwn(mapping.boxes, id));
  return refused(elsewhere ? 'cross-target-field-reference' : 'unknown-box-reference', id);
}

/** The input slots of a box of `mapping`; a source-field box has none. */
export function inputsOf(mapping: TargetFieldMapping, id: AuthoringId): BoxReference[] {
  const box = mapping.boxes[id]!;
  return box.kind === 'source' ? [] : (box.inputs ?? []);
}
