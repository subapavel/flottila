import type { FieldDto, StructureLoaded } from '../../api/client';
import { fieldAt, reachedSourceFields, type TreeRow } from './rows';
import type { AuthoringId, FieldPath, MappingModel, TargetFieldMapping } from '../../generated/mapping-model';
import { localPath } from '../../model/field-path';

export type Side = 'source' | 'target';

/** The filters of the Filter Tree options, in the prototype's order; the source pane offers the first two. */
export const FILTERS = [
  'Show Mapped Fields Only',
  'Show Unmapped Fields Only',
  'Show Fields Mapped Directly Only',
  'Show Fields Mapped Indirectly Only',
  'Show Selected Fields',
] as const;

/**
 * The rows of every field `keep` holds for, by its path, and of each of its ancestors, in tree order, each ancestor shown
 * expanded (the prototype's filter and search). Below a field whose type is already on its path the walk stops, so that a
 * recursive type ends (ADR-0008), as `expandAllPaths` stops.
 */
export function keptRows(structure: StructureLoaded, keep: (path: FieldPath, field: FieldDto) => boolean): TreeRow[] {
  const rows: TreeRow[] = [];
  const visit = (field: FieldDto, path: FieldPath, depth: number, types: ReadonlySet<string>) => {
    const at = rows.length;
    rows.push({ path, depth, field, expandable: field.type !== undefined, expanded: false });
    if (field.type !== undefined && !types.has(field.type)) {
      const below = new Set(types).add(field.type);
      for (const child of structure.types[field.type] ?? []) visit(child, `${path}/${child.step}`, depth + 1, below);
    }
    if (rows.length > at + 1) rows[at]!.expanded = true;
    else if (!keep(path, field)) rows.pop();
  };
  visit(structure.root, `/${structure.root.step}`, 0, new Set());
  return rows;
}

/** A value type as the XSD view names it: `xs:` becomes the prototype's `xsd:`; another name stays as it is. */
export const xsdType = (valueType: string) => valueType.replace(/^xs:/, 'xsd:');

/** The kind of a field's type icon: `structure` for a structure field, else its value type's local name. */
export function typeKind(field: FieldDto): string {
  if (field.type !== undefined) return 'structure';
  return (field.valueType ?? '').replace(/^.*[:}]/, '');
}

/** One line of the XSD view, with the field path of the element or attribute it declares. */
export interface SchemaLine {
  content: string;
  path?: FieldPath;
}

/**
 * The XSD of a loaded structure, line by line (the prototype's `schemaLines`): each element with its occurrence, a structure
 * field with a complex type of its child elements in a sequence, then its attributes. A structure field whose type is
 * already on its path, or that has no child fields, is written without a type, so a recursive type ends.
 */
export function schemaLines(structure: StructureLoaded): SchemaLine[] {
  const lines: SchemaLine[] = [];
  const line = (level: number, text: string, path?: FieldPath) => lines.push({ content: '  '.repeat(level) + text, ...(path && { path }) });
  const leafType = (field: FieldDto) => ` type="${xsdType(field.valueType ?? 'xs:string')}"`;
  const element = (field: FieldDto, path: FieldPath, level: number, types: ReadonlySet<string>) => {
    const occurs = (field.min === 1 ? '' : ` minOccurs="${field.min}"`) + (field.max === 1 ? '' : ` maxOccurs="${field.max ?? 'unbounded'}"`);
    const name = `<xsd:element name="${field.localName}"`;
    const children = field.type === undefined || types.has(field.type) ? [] : (structure.types[field.type] ?? []);
    if (children.length === 0) {
      line(level, `${name}${field.type === undefined ? leafType(field) : ''}${occurs}/>`, path);
      return;
    }
    const below = new Set(types).add(field.type!);
    const elements = children.filter((child) => child.kind !== 'attribute');
    line(level, `${name}${occurs}>`, path);
    line(level + 1, '<xsd:complexType>');
    if (elements.length > 0) {
      line(level + 2, '<xsd:sequence>');
      for (const child of elements) element(child, `${path}/${child.step}`, level + 3, below);
      line(level + 2, '</xsd:sequence>');
    }
    for (const child of children.filter((c) => c.kind === 'attribute')) {
      line(level + 2, `<xsd:attribute name="${child.localName}"${leafType(child)}${child.min === 1 ? ' use="required"' : ''}/>`, `${path}/${child.step}`);
    }
    line(level + 1, '</xsd:complexType>');
    line(level, '</xsd:element>');
  };
  line(0, '<?xml version="1.0" encoding="UTF-8"?>');
  line(0, '<xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">');
  element(structure.root, `/${structure.root.step}`, 1, new Set());
  line(0, '</xsd:schema>');
  return lines;
}

/**
 * A target field mapping as text (the prototype's `expression`): its `result` box, a source-field box as its field's path,
 * a function box as its name and its inputs in brackets; an unconnected input is empty. Empty when `result` is null or the
 * field has no mapping.
 */
export function expressionText(mapping: TargetFieldMapping | undefined): string {
  if (!mapping) return '';
  const render = (id: AuthoringId | null, seen: ReadonlySet<AuthoringId>): string => {
    const box = id === null || seen.has(id) ? undefined : mapping.boxes[id];
    if (!box) return '';
    if (box.kind === 'source') return `/${localPath(box.field)}`;
    const name = box.kind === 'function' ? box.fn : box.kind === 'udf' ? box.udf : box.name;
    const inner = new Set(seen).add(id!);
    return `${name}(${(box.inputs ?? []).map((input) => render(input, inner)).join(', ')})`;
  };
  return render(mapping.result, new Set());
}

/**
 * How a target field is mapped: `direct` when its `result` is a source-field box, `indirect` when it is another box whose
 * inputs reach a source field, and `undefined` otherwise.
 */
export function mappedHow(mapping: TargetFieldMapping | undefined): 'direct' | 'indirect' | undefined {
  const id = mapping?.result;
  const result = id === undefined || id === null ? undefined : mapping!.boxes[id];
  if (result === undefined) return undefined;
  if (result.kind === 'source') return 'direct';
  return reachedSourceFields(mapping).length > 0 ? 'indirect' : undefined;
}

/** The orphaned references on the source side (GLOSSARY: Orphaned reference), as `orphans` finds them. */
export interface Orphans {
  /** The source-field boxes, in any target field mapping, that read a field the source structure lacks. */
  boxes: ReadonlySet<AuthoringId>;
  /** The target fields whose target field mapping holds such a box: their rows carry the orphaned field status. */
  targets: ReadonlySet<FieldPath>;
}

/**
 * The orphaned source-field references of the working copy against the loaded source structure (GLOSSARY: Orphaned
 * reference, Field status): derived from the working copy, so a remap clears them at once. None while `source` is not
 * loaded.
 */
export function orphans(model: MappingModel, source: StructureLoaded | undefined): Orphans {
  const boxes = new Set<AuthoringId>();
  const targets = new Set<FieldPath>();
  if (source === undefined) return { boxes, targets };
  for (const [path, mapping] of Object.entries(model.targetFieldMappings ?? {})) {
    for (const [id, box] of Object.entries(mapping.boxes)) {
      if (box.kind !== 'source' || fieldAt(source, box.field) !== undefined) continue;
      boxes.add(id);
      targets.add(path);
    }
  }
  return { boxes, targets };
}

/**
 * The orphaned target field mappings (GLOSSARY): the field paths of the working copy's target field mappings that the
 * loaded target structure lacks, in the model's order. They have no row in the target tree. None while `target` is not
 * loaded.
 */
export function orphanedMappings(model: MappingModel, target: StructureLoaded | undefined): FieldPath[] {
  if (target === undefined) return [];
  return Object.keys(model.targetFieldMappings ?? {}).filter((path) => fieldAt(target, path) === undefined);
}

/** The fields each pane marks as connected: the source fields any target field mapping reads, and the mapped target fields. */
export function connectedFields(model: MappingModel): Record<Side, ReadonlySet<FieldPath>> {
  const mappings = Object.entries(model.targetFieldMappings ?? {});
  return {
    source: new Set(mappings.flatMap(([, mapping]) => reachedSourceFields(mapping))),
    target: new Set(mappings.filter(([, mapping]) => mapping.result !== null).map(([path]) => path)),
  };
}
