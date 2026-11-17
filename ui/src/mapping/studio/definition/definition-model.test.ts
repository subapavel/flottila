import { describe, expect, test } from 'vitest';
import type { FieldDto, StructureLoaded } from '../../api/client';
import type { MappingModel, TargetFieldMapping } from '../../generated/mapping-model';
import { connectedFields, expressionText, keptRows, mappedHow, orphanedMappings, orphans, schemaLines, typeKind, xsdType } from './definition-model';
import { fieldAt } from './rows';

const field = (step: string, extra: Partial<FieldDto> = {}): FieldDto => ({
  kind: step.startsWith('@') ? 'attribute' : 'element',
  step,
  namespace: '',
  localName: step.replace('@', ''),
  min: 1,
  max: 1,
  nillable: false,
  ...extra,
});

/** Doc: an attribute, a leaf without a value type, an empty structure field, an attribute-only one and a recursive Part. */
const doc: StructureLoaded = {
  kind: 'loaded',
  fingerprint: 'sha256:test',
  root: field('Doc', { type: 'doc' }),
  types: {
    doc: [
      field('@id', { valueType: 'xs:string' }),
      field('Plain', { min: 0, max: undefined }),
      field('Empty', { type: 'empty' }),
      field('Flags', { type: 'flags' }),
      field('Part', { type: 'part', min: 0 }),
    ],
    empty: [],
    flags: [field('@on', { min: 0, valueType: 'Q{urn:x}flag' })],
    part: [field('Name', { valueType: 'xs:token' }), field('Part', { type: 'part', min: 0 })],
  },
};

describe('the XSD view of a structure', () => {
  test('writes elements, attributes, occurrences and value types, and ends a recursive type', () => {
    expect(schemaLines(doc).map((line) => line.content)).toEqual([
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">',
      '  <xsd:element name="Doc">',
      '    <xsd:complexType>',
      '      <xsd:sequence>',
      '        <xsd:element name="Plain" type="xsd:string" minOccurs="0" maxOccurs="unbounded"/>',
      '        <xsd:element name="Empty"/>',
      '        <xsd:element name="Flags">',
      '          <xsd:complexType>',
      '            <xsd:attribute name="on" type="Q{urn:x}flag"/>',
      '          </xsd:complexType>',
      '        </xsd:element>',
      '        <xsd:element name="Part" minOccurs="0">',
      '          <xsd:complexType>',
      '            <xsd:sequence>',
      '              <xsd:element name="Name" type="xsd:token"/>',
      '              <xsd:element name="Part" minOccurs="0"/>',
      '            </xsd:sequence>',
      '          </xsd:complexType>',
      '        </xsd:element>',
      '      </xsd:sequence>',
      '      <xsd:attribute name="id" type="xsd:string" use="required"/>',
      '    </xsd:complexType>',
      '  </xsd:element>',
      '</xsd:schema>',
    ]);
  });

  test('gives each declaration its field path and the other lines none', () => {
    const paths = schemaLines(doc).map((line) => line.path);

    expect(paths.filter((path) => path !== undefined)).toEqual(['/Doc', '/Doc/Plain', '/Doc/Empty', '/Doc/Flags', '/Doc/Flags/@on', '/Doc/Part', '/Doc/Part/Name', '/Doc/Part/Part', '/Doc/@id']);
  });
});

describe('the kept rows of a search or a filter', () => {
  test('are the kept fields and their ancestors, open, and stop below a type already on the path', () => {
    const rows = keptRows(doc, (path) => path.endsWith('/Name') || path === '/Doc/Part/Part');

    expect(rows.map((row) => [row.path, row.expanded])).toEqual([
      ['/Doc', true],
      ['/Doc/Part', true],
      ['/Doc/Part/Name', false],
      ['/Doc/Part/Part', false],
    ]);
  });
});

describe('a target field mapping as text', () => {
  const mapping = (boxes: TargetFieldMapping['boxes'], result: string | null): TargetFieldMapping => ({ id: 't', result, boxes });

  test('names functions with their inputs in brackets, an unconnected input empty, and a source field by its path', () => {
    const text = expressionText(
      mapping(
        {
          c: { kind: 'function', fn: 'concat', inputs: ['s', null, 'u', 'x'] },
          s: { kind: 'source', field: '/Q{urn:a}Doc/Name' },
          u: { kind: 'udf', udf: 'shout', inputs: ['n'] },
          n: { kind: 'unresolved', name: 'legacy', origin: 'po' },
          x: { kind: 'function', fn: 'trim' },
        },
        'c',
      ),
    );

    expect(text).toBe('concat(/Doc/Name, , shout(legacy()), trim())');
  });

  test('is empty without a mapping or a result, and stops at a box already on its way', () => {
    expect(expressionText(undefined)).toBe('');
    expect(expressionText(mapping({ s: { kind: 'source', field: '/Doc' } }, null))).toBe('');
    expect(expressionText(mapping({ f: { kind: 'function', fn: 'trim', inputs: ['f'] } }, 'f'))).toBe('trim()');
  });
});

describe('how a target field is mapped', () => {
  test('directly from a source-field box, indirectly through a box that reaches one, else not', () => {
    expect(mappedHow(undefined)).toBeUndefined();
    expect(mappedHow({ id: 't', result: null, boxes: {} })).toBeUndefined();
    expect(mappedHow({ id: 't', result: 's', boxes: { s: { kind: 'source', field: '/Doc' } } })).toBe('direct');
    expect(
      mappedHow({ id: 't', result: 'f', boxes: { f: { kind: 'function', fn: 'trim', inputs: ['s'] }, s: { kind: 'source', field: '/Doc' } } }),
    ).toBe('indirect');
    expect(mappedHow({ id: 't', result: 'f', boxes: { f: { kind: 'function', fn: 'constant' } } })).toBeUndefined();
  });
});

describe('the connected fields', () => {
  test('are the source fields a reached box reads and the target fields whose result is set', () => {
    const model: MappingModel = {
      formatVersion: 1,
      targetFieldMappings: {
        '/Doc/A': { id: 't1', result: 's1', boxes: { s1: { kind: 'source', field: '/Doc/X' } } },
        '/Doc/B': { id: 't2', result: null, boxes: { s2: { kind: 'source', field: '/Doc/Y' } } },
      },
    };

    const connected = connectedFields(model);

    expect([...connected.source]).toEqual(['/Doc/X']);
    expect([...connected.target]).toEqual(['/Doc/A']);
    expect([...connectedFields({ formatVersion: 1 }).target]).toEqual([]);
  });
});

describe('the orphaned references (GLOSSARY: Orphaned reference, Field status)', () => {
  test('a field is found at any depth of a recursive type; a missing step, a step below a leaf or another root finds none', () => {
    expect(fieldAt(doc, '/Doc')).toBe(doc.root);
    expect(fieldAt(doc, '/Doc/@id')?.valueType).toBe('xs:string');
    expect(fieldAt(doc, '/Doc/Part/Part/Part/Name')?.valueType).toBe('xs:token');
    expect(fieldAt(doc, '/Doc/Gone')).toBeUndefined();
    expect(fieldAt(doc, '/Doc/Plain/Below')).toBeUndefined();
    expect(fieldAt(doc, '/Other/Name')).toBeUndefined();
  });

  test('are the source-field boxes reading a field the source structure lacks, and the target fields holding one', () => {
    const model: MappingModel = {
      formatVersion: 1,
      targetFieldMappings: {
        '/T/A': { id: 't1', result: 'f1', boxes: { a1: { kind: 'source', field: '/Doc/Gone' }, f1: { kind: 'function', fn: 'toUpperCase', inputs: ['a1'] } } },
        '/T/B': { id: 't2', result: 'a2', boxes: { a2: { kind: 'source', field: '/Doc/Part/Part/Name' } } },
        '/T/C': { id: 't3', result: 'a3', boxes: { a3: { kind: 'source', field: '/Doc/@id' }, a4: { kind: 'source', field: '/Other/X' } } },
      },
    };

    const found = orphans(model, doc);

    expect([...found.boxes]).toEqual(['a1', 'a4']);
    expect([...found.targets]).toEqual(['/T/A', '/T/C']);
  });

  test('are none while the source structure is not loaded, nor in a model without target field mappings', () => {
    const model: MappingModel = { formatVersion: 1, targetFieldMappings: { '/T/A': { id: 't1', result: 'a1', boxes: { a1: { kind: 'source', field: '/Doc/Gone' } } } } };

    expect(orphans(model, undefined)).toEqual({ boxes: new Set(), targets: new Set() });
    expect(orphans({ formatVersion: 1 }, doc)).toEqual({ boxes: new Set(), targets: new Set() });
  });
});

describe('the orphaned target field mappings (GLOSSARY, #174)', () => {
  const mapped = (...paths: string[]): MappingModel => ({
    formatVersion: 1,
    targetFieldMappings: Object.fromEntries(paths.map((path, i) => [path, { id: `t${i}`, result: null, boxes: {} }])),
  });

  test('are the target field paths the target structure lacks, in the model order, at any depth', () => {
    const model = mapped('/Doc/Part/Part/Gone', '/Doc/@id', '/Other/Name', '/Doc/Part/Part/Name', '/Doc/Plain/Below');

    expect(orphanedMappings(model, doc)).toEqual(['/Doc/Part/Part/Gone', '/Other/Name', '/Doc/Plain/Below']);
  });

  test('are none while the target structure is not loaded, nor in a model without target field mappings', () => {
    expect(orphanedMappings(mapped('/Doc/Gone'), undefined)).toEqual([]);
    expect(orphanedMappings({ formatVersion: 1 }, doc)).toEqual([]);
  });
});

describe('names and types', () => {
  test('a value type shows with the prototype’s xsd prefix, and its icon kind is its local name', () => {
    expect(xsdType('xs:decimal')).toBe('xsd:decimal');
    expect(xsdType('Q{urn:x}flag')).toBe('Q{urn:x}flag');
    expect(typeKind(field('A', { valueType: 'xs:decimal' }))).toBe('decimal');
    expect(typeKind(field('A', { valueType: 'Q{urn:x}flag' }))).toBe('flag');
    expect(typeKind(field('A'))).toBe('');
    expect(typeKind(field('A', { type: 't' }))).toBe('structure');
  });
});
