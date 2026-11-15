import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';
import type { MappingModel } from '../generated/mapping-model';
import { canonicalJson } from './canonical-json';

/** A corpus mapping file's bytes: the service's canonical writer wrote them (ADR-0010). */
const corpusBytes = (name: string) => readFileSync(new URL(`../../../corpus/mappings/${name}`, import.meta.url));

const structure = { bundle: ['order.xsd'] as [string], fingerprint: 'sha256:00', root: { element: 'Q{urn:a}Order' } };

describe('the canonical form of a model (ADR-0010)', () => {
  test.each([
    // a JSON source, an XSD bundle of three files, attribute steps that sort before element steps
    'gate-order-po.mapping.json',
    // boxes with inputs, a string and a table param, a context, escapes and a surrogate pair in table keys and values
    'fix-values-escapes.mapping.json',
  ])('of the corpus mapping %s, parsed, is the file byte for byte', (name) => {
    const bytes = corpusBytes(name);
    const model = JSON.parse(bytes.toString('utf8')) as MappingModel;

    expect(Buffer.from(canonicalJson(model), 'utf8').equals(bytes)).toBe(true);
  });

  test('sorts keys by UTF-16 code unit, also keys that look like array indices', () => {
    const model: MappingModel = {
      formatVersion: 1,
      source: structure,
      target: structure,
      targetFieldMappings: {
        '/Q{urn:a}Order/Id': {
          id: 't1',
          result: 'f1',
          // a table whose keys an object would hold in numeric order: 2 before 10
          boxes: { f1: { kind: 'function', fn: 'fixValues', inputs: [null], params: { table: { '2': 'b', '10': 'a', B: 'c', a: 'd' } } } },
        },
      },
    };

    const text = canonicalJson(model);

    // the table's members are 14 spaces deep, the input slot 12
    expect(text).toContain('"table": {\n              "10": "a",\n              "2": "b",\n              "B": "c",\n              "a": "d"\n            }');
    expect(text).toContain('"inputs": [\n            null\n          ],');
  });

  test('writes an empty table as {}, leaves out a member held as undefined, and ends with one LF', () => {
    const model: MappingModel = {
      formatVersion: 1,
      source: structure,
      target: structure,
      udfs: undefined,
      targetFieldMappings: {
        '/Q{urn:a}Order/Id': { id: 't1', result: null, boxes: { f1: { kind: 'function', fn: 'fixValues', params: { table: {} } } } },
      },
    };

    const text = canonicalJson(model);

    expect(text).toContain('"table": {}');
    expect(text).not.toContain('udfs');
    expect(text).toMatch(/^\{\n {2}"formatVersion": 1,\n/);
    expect(text).toMatch(/\n\}\n$/);
  });
});
