import { describe, expect, test } from 'vitest';
import type { components } from '../../generated/api';
import type { TargetFieldMapping } from '../../generated/mapping-model';
import { expandAllPaths, fieldCount, occurrenceText, reachedSourceFields, refusalAt, searchRows, treeRows } from './rows';

type FieldDto = components['schemas']['FieldDto'];
type StructureLoaded = components['schemas']['StructureLoaded'];

const field = (step: string, min: number, max: number | undefined, extra: Partial<FieldDto> = {}): FieldDto => ({
  kind: step.startsWith('@') ? 'attribute' : 'element',
  step,
  namespace: '',
  localName: step.replace('@', ''),
  min,
  ...(max === undefined ? {} : { max }),
  nillable: false,
  ...extra,
});

/** Order holds @channel, OrderId and Item; Item holds Sku and another Item (a recursive type, ADR-0008). */
const structure: StructureLoaded = {
  kind: 'loaded',
  fingerprint: 'sha256:00',
  root: field('Q{urn:a}Order', 1, 1, { namespace: 'urn:a', localName: 'Order', type: '/Q{urn:a}Order' }),
  types: {
    '/Q{urn:a}Order': [
      field('@channel', 0, 1, { valueType: 'xs:string' }),
      field('OrderId', 1, 1, { valueType: 'xs:string' }),
      field('Item', 0, undefined, { type: 'item' }),
    ],
    item: [field('Sku', 1, 1, { valueType: 'xs:token' }), field('Item', 0, undefined, { type: 'item' })],
  },
};

const ORDER = '/Q{urn:a}Order';
const describeRows = (expanded: string[]) =>
  treeRows(structure, new Set(expanded), 'target').map((r) => `${'  '.repeat(r.depth)}${r.path}${r.expandable ? (r.expanded ? ' -' : ' +') : ''}`);

describe('the rows of a structure tree', () => {
  test('show only the root while nothing is expanded', () => {
    expect(describeRows([])).toEqual([`${ORDER} +`]);
  });

  test('list an expanded field’s child fields one level deeper, with their field paths, attributes marked', () => {
    expect(describeRows([ORDER])).toEqual([
      `${ORDER} -`,
      `  ${ORDER}/@channel`,
      `  ${ORDER}/OrderId`,
      `  ${ORDER}/Item +`,
    ]);
  });

  test('expand a recursive type only as deep as its paths are expanded', () => {
    expect(describeRows([ORDER, `${ORDER}/Item`, `${ORDER}/Item/Item`])).toEqual([
      `${ORDER} -`,
      `  ${ORDER}/@channel`,
      `  ${ORDER}/OrderId`,
      `  ${ORDER}/Item -`,
      `    ${ORDER}/Item/Sku`,
      `    ${ORDER}/Item/Item -`,
      `      ${ORDER}/Item/Item/Sku`,
      `      ${ORDER}/Item/Item/Item +`,
    ]);
  });

  test('ignore an expanded path whose parent is collapsed', () => {
    expect(describeRows([`${ORDER}/Item`])).toEqual([`${ORDER} +`]);
  });

  test('carry the field itself, so a row can show its type', () => {
    const orderId = treeRows(structure, new Set([ORDER]), 'target')[2]!;

    expect(orderId.field.valueType).toBe('xs:string');
    expect(orderId.field.step).toBe('OrderId');
  });

  test('treat a type id without fields as a structure field with no child fields', () => {
    const empty: StructureLoaded = { ...structure, types: {} };

    expect(treeRows(empty, new Set([ORDER]), 'target').map((r) => r.path)).toEqual([ORDER]);
  });
});

describe('Expand all and the field count (spec #135, stories 16 and 17)', () => {
  test('expand every structure field down to one whose type is already on its path, so a recursive type ends', () => {
    expect(expandAllPaths(structure)).toEqual([ORDER, `${ORDER}/Item`]);
    expect(describeRows(expandAllPaths(structure))).toEqual([
      `${ORDER} -`,
      `  ${ORDER}/@channel`,
      `  ${ORDER}/OrderId`,
      `  ${ORDER}/Item -`,
      `    ${ORDER}/Item/Sku`,
      `    ${ORDER}/Item/Item +`,
    ]);
  });

  test('count the rows Expand all shows', () => {
    expect(fieldCount(structure)).toBe(6);
    expect(fieldCount({ ...structure, types: {} })).toBe(1);
  });
});

describe('the rows of a search (spec #135, story 15)', () => {
  const searched = (query: string) =>
    searchRows(structure, query, 'target').map((r) => `${'  '.repeat(r.depth)}${r.path}${r.expandable ? (r.expanded ? ' -' : ' +') : ''}`);

  test('are the fields whose local name holds the search, ignoring case, with their ancestors expanded', () => {
    expect(searched('SKU')).toEqual([`${ORDER} -`, `  ${ORDER}/Item -`, `    ${ORDER}/Item/Sku`]);
    expect(searched('chan')).toEqual([`${ORDER} -`, `  ${ORDER}/@channel`]);
  });

  test('match the local name, not the namespace, the attribute mark or the path', () => {
    expect(searched('urn')).toEqual([]);
    expect(searched('@')).toEqual([]);
    expect(searched('order/')).toEqual([]);
    expect(searched('order')).toEqual([`${ORDER} -`, `  ${ORDER}/OrderId`]);
  });

  test('end a recursive type where Expand all ends it, a matching field there collapsed', () => {
    expect(searched('item')).toEqual([`${ORDER} -`, `  ${ORDER}/Item -`, `    ${ORDER}/Item/Item +`]);
  });

  test('carry the refusal nearest the root on their path', () => {
    const refusal = { code: 'abstract-target-element', side: 'target' as const, file: 'r.xsd', line: 3, column: 4 };
    const refusedItem: StructureLoaded = {
      ...structure,
      types: { ...structure.types, '/Q{urn:a}Order': [field('Item', 0, undefined, { type: 'item', refusal })] },
    };

    expect(searchRows(refusedItem, 'sku', 'target').map((r) => r.refusal?.code)).toEqual([undefined, 'abstract-target-element', 'abstract-target-element']);
    expect(searchRows(refusedItem, 'sku', 'source').map((r) => r.refusal?.code)).toEqual([undefined, undefined, undefined]);
  });
});

describe('a refused field in the tree (ADR-0008, ADR-0027)', () => {
  const ambiguousNote = { code: 'ambiguous-field-path', side: 'both' as const, file: 'r.xsd', line: 8, column: 65 };
  const ambiguousPart = { code: 'ambiguous-field-path', side: 'both' as const, file: 'r.xsd', line: 20, column: 53 };
  const abstractBox = { code: 'abstract-target-element', side: 'target' as const, file: 'r.xsd', line: 12, column: 40 };

  /**
   * Doc holds Id, Note twice (same-step siblings), and Box, refused in a target only; Box holds Sku and Part twice,
   * whose type holds Name. Part's type is shared, so Name carries no refusal of its own.
   */
  const refused: StructureLoaded = {
    kind: 'loaded',
    fingerprint: 'sha256:00',
    root: field('Doc', 1, 1, { type: 'doc' }),
    types: {
      doc: [
        field('Id', 1, 1, { valueType: 'xs:string' }),
        field('Note', 0, 1, { valueType: 'xs:string', refusal: ambiguousNote }),
        field('Note', 0, 1, { valueType: 'xs:string', refusal: ambiguousNote }),
        field('Box', 0, 1, { type: 'box', refusal: abstractBox }),
      ],
      box: [
        field('Sku', 1, 1, { valueType: 'xs:string' }),
        field('Part', 1, 1, { type: 'part', refusal: ambiguousPart }),
        field('Part', 0, 2, { type: 'part', refusal: ambiguousPart }),
      ],
      part: [field('Name', 0, 1, { valueType: 'xs:string' })],
    },
  };
  const all = ['/Doc', '/Doc/Box', '/Doc/Box/Part'];
  const rowRefusals = (side: 'source' | 'target') =>
    treeRows(refused, new Set(all), side).map((r) => `${r.path} ${r.refusal ? `${r.refusal.code} ${r.refusal.line}` : '-'}`);

  test('a target row carries its field’s refusal, and a row below a refused field the refusal nearest the root', () => {
    expect(rowRefusals('target')).toEqual([
      '/Doc -',
      '/Doc/Id -',
      '/Doc/Note ambiguous-field-path 8',
      '/Doc/Note ambiguous-field-path 8',
      '/Doc/Box abstract-target-element 12',
      '/Doc/Box/Sku abstract-target-element 12',
      '/Doc/Box/Part abstract-target-element 12',
      '/Doc/Box/Part/Name abstract-target-element 12',
      '/Doc/Box/Part abstract-target-element 12',
      '/Doc/Box/Part/Name abstract-target-element 12',
    ]);
  });

  test('a source row passes a refusal that holds in a target only, as compile does (#94, #95)', () => {
    expect(rowRefusals('source')).toEqual([
      '/Doc -',
      '/Doc/Id -',
      '/Doc/Note ambiguous-field-path 8',
      '/Doc/Note ambiguous-field-path 8',
      '/Doc/Box -',
      '/Doc/Box/Sku -',
      '/Doc/Box/Part ambiguous-field-path 20',
      '/Doc/Box/Part/Name ambiguous-field-path 20',
      '/Doc/Box/Part ambiguous-field-path 20',
      '/Doc/Box/Part/Name ambiguous-field-path 20',
    ]);
  });

  test.each([
    ['/Doc/Id', 'target', undefined],
    ['/Doc/Note', 'source', ambiguousNote],
    ['/Doc/Box/Part/Name', 'target', abstractBox],
    ['/Doc/Box/Part/Name', 'source', ambiguousPart],
    ['/Doc/Box/Sku', 'source', undefined],
    ['/Doc/Gone', 'target', undefined],
    ['/Other/Id', 'target', undefined],
  ] as const)('the refusal at %s in a %s tree, whatever the rows show', (path, side, refusal) => {
    expect(refusalAt(refused, path, side)).toEqual(refusal);
  });

  test('which side a refusal holds on comes from its side, never from its code', () => {
    const later = { code: 'a-later-target-reason', side: 'target' as const, file: 'r.xsd', line: 3, column: 4 };
    const targetOnlyId: StructureLoaded = {
      ...refused,
      types: { ...refused.types, doc: [field('Id', 1, 1, { valueType: 'xs:string', refusal: later })] },
    };

    expect(refusalAt(targetOnlyId, '/Doc/Id', 'source')).toBeUndefined();
    expect(refusalAt(targetOnlyId, '/Doc/Id', 'target')).toEqual(later);
  });

  test('the root carries its own refusal', () => {
    const rootRefused: StructureLoaded = { ...refused, root: { ...refused.root, refusal: ambiguousNote } };

    expect(refusalAt(rootRefused, '/Doc/Id', 'source')).toEqual(ambiguousNote);
    expect(treeRows(rootRefused, new Set(['/Doc']), 'source')[1]!.refusal).toEqual(ambiguousNote);
  });
});

describe('the effective occurrence of a field', () => {
  test.each([
    [1, 1, '1..1'],
    [0, 1, '0..1'],
    [0, undefined, '0..unbounded'],
    [2, 5, '2..5'],
  ])('min %s and max %s read %s', (min, max, text) => {
    expect(occurrenceText(field('X', min, max))).toBe(text);
  });
});

describe('the source fields a target field mapping links (ADR-0013, "reached")', () => {
  const mapping = (result: string | null, boxes: TargetFieldMapping['boxes']): TargetFieldMapping => ({ id: 't1', result, boxes });
  const source = (field: string) => ({ kind: 'source' as const, field });
  const fn = (inputs?: (string | null)[]) => ({ kind: 'function' as const, fn: 'concat', ...(inputs ? { inputs } : {}) });

  test('are none for a target field without a mapping', () => {
    expect(reachedSourceFields(undefined)).toEqual([]);
  });

  test('are none while result is null, even with a leftover source-field box', () => {
    expect(reachedSourceFields(mapping(null, { a1: source(`${ORDER}/OrderId`) }))).toEqual([]);
  });

  test('come from the box result reaches, not from an unreachable box', () => {
    const m = mapping('a2', { a1: source(`${ORDER}/Note`), a2: source(`${ORDER}/OrderId`) });

    expect(reachedSourceFields(m)).toEqual([`${ORDER}/OrderId`]);
  });

  test('follow the inputs of a function box, skip an empty input slot, and list a field read twice once', () => {
    const m = mapping('f1', {
      f1: fn(['a1', null, 'f2', 'a3']),
      f2: fn(['a1', 'f3', 'a5']),
      f3: fn(),
      a1: source(`${ORDER}/OrderId`),
      a3: source(`${ORDER}/@channel`),
      a4: source(`${ORDER}/Note`),
      a5: source(`${ORDER}/OrderId`),
    });

    expect(reachedSourceFields(m)).toEqual([`${ORDER}/OrderId`, `${ORDER}/@channel`]);
  });

  test('stop at a box cycle and at a reference to a missing box', () => {
    const m = mapping('f1', { f1: fn(['f2', 'gone']), f2: fn(['f1', 'a1']), a1: source(`${ORDER}/OrderId`) });

    expect(reachedSourceFields(m)).toEqual([`${ORDER}/OrderId`]);
  });
});
