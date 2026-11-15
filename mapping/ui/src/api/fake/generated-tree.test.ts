import { describe, expect, test } from 'vitest';
import type { FieldDto, StructureLoaded } from '../client';
import { fakeApiClient } from './fake-api';
import { TREE_FIELDS, TREE_MAPPING, TREE_SEED, generatedMapping, generatedStructure, structurePaths } from './generated-tree';

/** Every field of a structure graph whose types are each used once, as a fully expanded tree lists them. */
function fieldsOf(structure: StructureLoaded): FieldDto[] {
  return [structure.root, ...Object.values(structure.types).flat()];
}

describe('the generated structure (ui/tree-20128)', () => {
  test('is the same for the same seed and differs for another', () => {
    expect(generatedStructure(7, 500)).toEqual(generatedStructure(7, 500));
    expect(generatedStructure(7, 500)).not.toEqual(generatedStructure(8, 500));
  });

  test.each([1, 2, 500, TREE_FIELDS])('has exactly %s fields when fully expanded, each type used by one field', (fields) => {
    const structure = generatedStructure(20128, fields);
    const typed = fieldsOf(structure).flatMap((f) => (f.type === undefined ? [] : [f.type]));

    expect(fieldsOf(structure)).toHaveLength(fields);
    expect(new Set(typed).size).toBe(typed.length);
    expect(Object.keys(structure.types).sort()).toEqual([...typed].sort());
  });

  test('reaches its size whatever the seed, as the last open structure field always gets a structure child', () => {
    for (let seed = 0; seed < 300; seed++) expect(fieldsOf(generatedStructure(seed, 40))).toHaveLength(40);
  });

  test('lists a structure field’s attributes before its elements, and gives a leaf a value type', () => {
    for (const children of Object.values(generatedStructure(3, 2000).types)) {
      const kinds = children.map((c) => c.kind).join(' ');
      expect(kinds).toMatch(/^(attribute ?)*(element ?)*$/);
      for (const child of children) expect(child.type === undefined).toBe(child.valueType !== undefined);
    }
  });

  test('has paths for every field, the structure fields expandable and the others leaves', () => {
    const structure = generatedStructure(5, 300);
    const { expandable, leaves } = structurePaths(structure);

    expect(expandable.length + leaves.length).toBe(300);
    expect(expandable[0]).toBe(`/${structure.root.step}`);
    expect(leaves.every((path) => path.startsWith(`/${structure.root.step}/`))).toBe(true);
  });
});

describe('the generated mapping', () => {
  test('maps every leaf field from the same source field, one source-field box each', () => {
    const structure = generatedStructure(5, 300);
    const mapping = generatedMapping(structure, '../structures/generated/tree.xsd');
    const { leaves } = structurePaths(structure);

    expect(Object.keys(mapping.targetFieldMappings ?? {})).toEqual(leaves);
    for (const [target, m] of Object.entries(mapping.targetFieldMappings ?? {})) {
      expect(m.boxes[m.result!]).toEqual({ kind: 'source', field: target });
    }
    expect(mapping.source).toEqual(mapping.target);
    expect(mapping.source!.bundle).toEqual(['../structures/generated/tree.xsd']);
    expect(mapping.source!.root).toEqual({ element: structure.root.step });
  });

  test('is served by the fake API with its generated structure', async () => {
    const api = fakeApiClient();
    const { model } = await api.getMapping(TREE_MAPPING);
    const structure = await api.loadStructure({ base: 'mappings', bundle: model.source!.bundle, root: model.source!.root });

    expect(structure).toEqual(generatedStructure(TREE_SEED, TREE_FIELDS));
    expect(Object.keys(model.targetFieldMappings ?? {})).toEqual(structurePaths(structure as StructureLoaded).leaves);
  });
});
