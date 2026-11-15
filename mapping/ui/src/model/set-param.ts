import { produce } from 'immer';
import type { FunctionCatalogue } from '../generated/function-catalogue';
import type { AuthoringId, FieldPath, MappingModel, ParamValue, StandardFunctionBox } from '../generated/mapping-model';
import { applied, refused, refuseReference, type CommandResult } from './command';

/** Sets box parameter `name` of standard function box `box` to `value`, from the inspector. */
export interface SetParamCommand {
  targetField: FieldPath;
  box: AuthoringId;
  name: string;
  value: ParamValue;
}

/**
 * Sets a box parameter of a standard function box. A value equal to the catalogue's default is left out, and so is
 * `params` once it holds none (ADR-0010); a table equals another with the same rows in any order. The command refuses,
 * at the box:
 * - `cross-target-field-reference` or `unknown-box-reference` when the box is not in the target field's mapping;
 * - `unknown-parameter` when the box is not a standard function box whose catalogue entry has the param: a
 *   source-field box has none, and the params of a `udf` and an `unresolved` box are read-only;
 * - `wrong-parameter-type` when a table is given for a string param, or a string for a table param;
 * - `invalid-parameter-value` when a string is outside the param's `values`.
 */
export function setParam(model: MappingModel, command: SetParamCommand, catalogue: FunctionCatalogue): CommandResult {
  const { targetField, box, name, value } = command;
  const refusal = refuseReference(model, targetField, box);
  if (refusal) return refusal;
  const target = model.targetFieldMappings![targetField]!.boxes[box]!;
  const param = target.kind === 'function' ? catalogue[target.fn]?.params?.find((p) => p.name === name) : undefined;
  if (param === undefined) return refused('unknown-parameter', box);
  if ((param.type === 'table') !== (typeof value === 'object')) return refused('wrong-parameter-type', box);
  if (param.type === 'string' && param.values && !param.values.includes(value as string)) {
    return refused('invalid-parameter-value', box);
  }

  const before = (target as StandardFunctionBox).params?.[name];
  const after = sameValue(value, param.default) ? undefined : value;
  if (before === after || (before !== undefined && after !== undefined && sameValue(before, after))) return applied(model);

  return applied(
    produce(model, (draft) => {
      const changed = draft.targetFieldMappings![targetField]!.boxes[box] as StandardFunctionBox;
      const params = (changed.params ??= {});
      if (after === undefined) delete params[name];
      else params[name] = after;
      if (Object.keys(params).length === 0) delete changed.params;
    }),
  );
}

/** Sets several box parameters of one standard function box at once, from the parameter dialog. */
export interface SetParamsCommand {
  targetField: FieldPath;
  box: AuthoringId;
  params: Readonly<Record<string, ParamValue>>;
}

/**
 * Sets each param in turn as the set param command does, so that the edit is one model change; a refused param refuses
 * the whole and none is set. Params equal to what the box has give back the model itself.
 */
export function setParams(model: MappingModel, command: SetParamsCommand, catalogue: FunctionCatalogue): CommandResult {
  const { targetField, box, params } = command;
  let next = model;
  for (const [name, value] of Object.entries(params)) {
    const result = setParam(next, { targetField, box, name, value }, catalogue);
    if (result.kind === 'refused') return result;
    next = result.model;
  }
  return applied(next);
}

/** Whether two param values are equal: the same string, or tables with the same rows. */
function sameValue(a: ParamValue, b: ParamValue): boolean {
  if (typeof a === 'string' || typeof b === 'string') return a === b;
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every((key) => a[key] === b[key]);
}
