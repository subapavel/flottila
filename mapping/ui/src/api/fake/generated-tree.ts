import type { MappingModel, StructureRef, TargetFieldMapping } from '../../generated/mapping-model';
import type { FieldDto, StructureLoaded } from '../client';

/** The size of the `ui/tree-20128/scroll` scenario's tree, fully expanded (ADR-0012, GATE 7). */
export const TREE_FIELDS = 20_128;
/** The seed of that tree. */
export const TREE_SEED = 20_128;
/** The fake API's mapping over the generated tree, every leaf field linked. */
export const TREE_MAPPING = 'mappings/tree-20128';
/** Its bundle, relative to the mapping, and the workspace path the fake API resolves it to. */
export const TREE_BUNDLE = '../structures/generated/tree-20128.xsd';
export const TREE_FILE = 'structures/generated/tree-20128.xsd';

const NAMESPACE = 'urn:flottila:fake:tree';
const ELEMENTS = ['Segment', 'Item', 'Partner', 'Address', 'Amount', 'Date', 'Text', 'Code', 'Qualifier', 'Reference'];
const ATTRIBUTES = ['id', 'type', 'unit', 'lang', 'currency'];
const VALUE_TYPES = ['xs:string', 'xs:decimal', 'xs:date', 'xs:integer'];

/** mulberry32: a small seeded generator, so the same seed gives the same structure on every run. */
function seeded(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * A synthetic structure of exactly `fields` fields when fully expanded (ADR-0013: generated, never committed). Each
 * structure field has a type of its own, so a fully expanded tree has one row per field. Breadth first: each structure
 * field gets up to two attributes, then elements, about four in ten of them structure fields themselves.
 */
export function generatedStructure(seed: number, fields: number): StructureLoaded {
  const random = seeded(seed);
  const pick = <T>(values: readonly T[]) => values[Math.floor(random() * values.length)]!;
  const types: Record<string, FieldDto[]> = {};
  const open = ['t0'];
  let remaining = fields - 1;
  for (let next = 0; next < open.length; next++) {
    const width = Math.min(remaining, 3 + Math.floor(random() * 12));
    const attributes = Math.min(width - 1, Math.floor(random() * 3));
    const children: FieldDto[] = [];
    for (let i = 0; i < width; i++) {
      const lastOpen = i === width - 1 && next === open.length - 1 && remaining > width;
      if (i < attributes) {
        const name = `${pick(ATTRIBUTES)}${i}`;
        children.push(field('attribute', `@${name}`, name, random() < 0.5 ? 0 : 1, 1, pick(VALUE_TYPES)));
      } else if (lastOpen || random() < 0.4) {
        const type = `t${open.length}`;
        open.push(type);
        children.push({ ...field('element', `${pick(ELEMENTS)}${i}`, '', 0, undefined), type });
      } else {
        children.push(field('element', `${pick(ELEMENTS)}${i}`, '', random() < 0.5 ? 0 : 1, 1, pick(VALUE_TYPES)));
      }
    }
    types[open[next]!] = children;
    remaining -= width;
  }
  const root = { ...field('element', `Q{${NAMESPACE}}Tree`, 'Tree', 1, 1), namespace: NAMESPACE, type: 't0' };
  return { kind: 'loaded', fingerprint: `sha256:generated-${seed}-${fields}`, root, types };
}

function field(
  kind: FieldDto['kind'],
  step: string,
  localName: string,
  min: number,
  max: number | undefined,
  valueType?: string,
): FieldDto {
  return {
    kind,
    step,
    namespace: '',
    localName: localName || step,
    min,
    ...(max === undefined ? {} : { max }),
    ...(valueType === undefined ? {} : { valueType }),
    nillable: false,
  };
}

/**
 * Every field path of a generated structure, in tree order: structure fields and leaves. Each of its types is used
 * once and has an entry, as `generatedStructure` writes them.
 */
export function structurePaths(structure: StructureLoaded): { expandable: string[]; leaves: string[] } {
  const expandable: string[] = [];
  const leaves: string[] = [];
  const visit = (f: FieldDto, path: string) => {
    if (f.type === undefined) {
      leaves.push(path);
      return;
    }
    expandable.push(path);
    for (const child of structure.types[f.type]!) visit(child, `${path}/${child.step}`);
  };
  visit(structure.root, `/${structure.root.step}`);
  return { expandable, leaves };
}

/** A mapping from the structure to itself that links every leaf field from the same source field. */
export function generatedMapping(structure: StructureLoaded, bundle: string): MappingModel {
  const side: StructureRef = { bundle: [bundle], fingerprint: structure.fingerprint, root: { element: structure.root.step } };
  const targetFieldMappings: Record<string, TargetFieldMapping> = {};
  structurePaths(structure).leaves.forEach((path, i) => {
    targetFieldMappings[path] = { id: `t${i}`, result: `a${i}`, boxes: { [`a${i}`]: { kind: 'source', field: path } } };
  });
  return { formatVersion: 1, source: side, target: { ...side }, targetFieldMappings };
}
