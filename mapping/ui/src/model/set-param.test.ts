import { describe, expect, test } from 'vitest';
import type { FunctionCatalogue } from '../generated/function-catalogue';
import type { MappingModel, TargetFieldMapping } from '../generated/mapping-model';
import { setParam, setParams } from './set-param';

const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ORDER_ID = `${ORDER}/OrderId`;
const NOTE = `${ORDER}/Note`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:df1714b8b2e4b09e326626a2ea72780bf6298a0ca733c69f5586ea657e4bd90d',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

/** The params of `fixValues` and `formatNumber` as the catalogue has them. */
const catalogue: FunctionCatalogue = {
  fixValues: {
    family: 'conversions',
    executionKind: 'single',
    inputs: [{ name: 'key' }],
    params: [
      { name: 'table', type: 'table', default: {} },
      { name: 'missing', type: 'string', default: 'key', values: ['key', 'default', 'error'] },
      { name: 'defaultValue', type: 'string', default: '' },
    ],
    portable: true,
    fidelity: { level: 'assumed' },
  },
  formatNumber: {
    family: 'arithmetic',
    executionKind: 'single',
    inputs: [{ name: 'value' }],
    params: [{ name: 'pattern', type: 'string', default: '0', syntax: 'numberPattern' }],
    portable: true,
    fidelity: { level: 'exact' },
  },
};

/** OrderId: a1 into fixValues x1 (missing set to error), into formatNumber n1, the result; u1 a UDF; r1 unresolved. */
const model: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'n1',
      boxes: {
        a1: { kind: 'source', field: ORDER_ID },
        x1: { kind: 'function', fn: 'fixValues', inputs: ['a1'], params: { missing: 'error' } },
        n1: { kind: 'function', fn: 'formatNumber', inputs: ['x1'] },
        u1: { kind: 'udf', udf: 'shout', params: { loud: 'yes' } },
        r1: { kind: 'unresolved', name: 'FormatByExample', origin: 'po' },
      },
    },
    [NOTE]: { id: 'tn', result: 'm1', boxes: { m1: { kind: 'function', fn: 'formatNumber' } } },
  },
};

const orderId = (result: { kind: string; model?: MappingModel }): TargetFieldMapping | undefined =>
  result.kind === 'applied' ? result.model?.targetFieldMappings?.[ORDER_ID] : undefined;

describe('the set param command', () => {
  test('sets a string param on a box that has no params yet', () => {
    const result = setParam(model, { targetField: ORDER_ID, box: 'n1', name: 'pattern', value: '#,##0.00' }, catalogue);

    expect(orderId(result)?.boxes.n1).toEqual({ kind: 'function', fn: 'formatNumber', inputs: ['x1'], params: { pattern: '#,##0.00' } });
    expect(model.targetFieldMappings?.[ORDER_ID]?.boxes.n1).toEqual({ kind: 'function', fn: 'formatNumber', inputs: ['x1'] });
  });

  test('sets a table param and keeps the other params', () => {
    const result = setParam(model, { targetField: ORDER_ID, box: 'x1', name: 'table', value: { A: 'Alpha', B: 'Beta' } }, catalogue);

    expect(orderId(result)?.boxes.x1).toEqual({
      kind: 'function',
      fn: 'fixValues',
      inputs: ['a1'],
      params: { missing: 'error', table: { A: 'Alpha', B: 'Beta' } },
    });
  });

  test('a value equal to the catalogue default is left out, and so is params once it is empty', () => {
    const result = setParam(model, { targetField: ORDER_ID, box: 'x1', name: 'missing', value: 'key' }, catalogue);

    expect(orderId(result)?.boxes.x1).toEqual({ kind: 'function', fn: 'fixValues', inputs: ['a1'] });
  });

  test('a table equal to the default table is left out, whatever the order of its rows', () => {
    const withTable = setParam(model, { targetField: ORDER_ID, box: 'x1', name: 'table', value: { A: '1' } }, catalogue);
    const tableCatalogue: FunctionCatalogue = {
      ...catalogue,
      fixValues: { ...catalogue.fixValues!, params: [{ name: 'table', type: 'table', default: { B: '2', A: '1' } }] },
    };
    const back = withTable.kind === 'applied' ? setParam(withTable.model, { targetField: ORDER_ID, box: 'x1', name: 'table', value: { A: '1', B: '2' } }, tableCatalogue) : withTable;

    expect(orderId(back)?.boxes.x1).toEqual({ kind: 'function', fn: 'fixValues', inputs: ['a1'], params: { missing: 'error' } });
  });

  test('a table that differs from the default in one value, or in one key, is kept', () => {
    const tableCatalogue: FunctionCatalogue = {
      ...catalogue,
      fixValues: { ...catalogue.fixValues!, params: [{ name: 'table', type: 'table', default: { A: '1' } }] },
    };
    const set = (value: Record<string, string>) => orderId(setParam(model, { targetField: ORDER_ID, box: 'x1', name: 'table', value }, tableCatalogue));

    expect(set({ A: '2' })?.boxes.x1).toMatchObject({ params: { table: { A: '2' } } });
    expect(set({ B: '1' })?.boxes.x1).toMatchObject({ params: { table: { B: '1' } } });
    expect(set({ A: '1', B: '1' })?.boxes.x1).toMatchObject({ params: { table: { A: '1', B: '1' } } });
  });

  test('the default on a box that leaves the param out gives back the same model', () => {
    const result = setParam(model, { targetField: ORDER_ID, box: 'n1', name: 'pattern', value: '0' }, catalogue);

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  test('the value a box already has gives back the same model', () => {
    const result = setParam(model, { targetField: ORDER_ID, box: 'x1', name: 'missing', value: 'error' }, catalogue);

    expect(result.kind === 'applied' && result.model).toBe(model);
  });

  test('a table with the rows the box already has, in another order, gives back the same model', () => {
    const withTable = setParam(model, { targetField: ORDER_ID, box: 'x1', name: 'table', value: { A: '1', B: '2' } }, catalogue);
    const first = withTable.kind === 'applied' ? withTable.model : model;

    const again = setParam(first, { targetField: ORDER_ID, box: 'x1', name: 'table', value: { B: '2', A: '1' } }, catalogue);

    expect(again.kind === 'applied' && again.model).toBe(first);
  });

  describe('refuses', () => {
    test.each([
      ["another target field mapping's box", 'm1', 'pattern', '#', 'cross-target-field-reference', 'm1'],
      ['no box', 'zz', 'pattern', '#', 'unknown-box-reference', 'zz'],
      ['a param the catalogue entry lacks', 'n1', 'scale', '2', 'unknown-parameter', 'n1'],
      ['a param of a function the catalogue lacks', 'x9', 'pattern', '#', 'unknown-parameter', 'x9'],
      ['a param on a source-field box', 'a1', 'pattern', '#', 'unknown-parameter', 'a1'],
      ['a param on a udf box, whose params are read-only', 'u1', 'loud', 'no', 'unknown-parameter', 'u1'],
      ['a param on an unresolved box, whose params are read-only', 'r1', 'pattern', '#', 'unknown-parameter', 'r1'],
      ['a table given for a string param', 'x1', 'missing', { a: 'b' }, 'wrong-parameter-type', 'x1'],
      ['a string given for a table param', 'x1', 'table', 'a=b', 'wrong-parameter-type', 'x1'],
      ['a string outside the param’s values', 'x1', 'missing', 'ignore', 'invalid-parameter-value', 'x1'],
    ])('%s', (_, box, name, value, code, id) => {
      const unknownFn: MappingModel = structuredClone(model);
      unknownFn.targetFieldMappings![ORDER_ID]!.boxes.x9 = { kind: 'function', fn: 'noSuchFunction' };

      expect(setParam(unknownFn, { targetField: ORDER_ID, box, name, value }, catalogue)).toEqual({ kind: 'refused', code, id });
    });
  });
});

describe('the set params command (the parameter dialog’s Apply)', () => {
  test('sets several params as one model change, each by the set param rules', () => {
    const result = setParams(model, { targetField: ORDER_ID, box: 'x1', params: { missing: 'key', defaultValue: 'n/a', table: { A: '1' } } }, catalogue);

    expect(orderId(result)?.boxes.x1).toEqual({ kind: 'function', fn: 'fixValues', inputs: ['a1'], params: { defaultValue: 'n/a', table: { A: '1' } } });
  });

  test('a refused param refuses the whole and sets none', () => {
    expect(setParams(model, { targetField: ORDER_ID, box: 'x1', params: { defaultValue: 'n/a', missing: 'maybe' } }, catalogue)).toEqual({
      kind: 'refused',
      code: 'invalid-parameter-value',
      id: 'x1',
    });
  });

  test('no change gives back the same model', () => {
    const result = setParams(model, { targetField: ORDER_ID, box: 'x1', params: { missing: 'error', defaultValue: '' } }, catalogue);

    expect(result.kind === 'applied' && result.model).toBe(model);
  });
});
