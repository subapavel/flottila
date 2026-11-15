import type { FieldDto, StructureLoaded } from '../../api/client';
import type { FieldPath, SourceFieldBox, TargetFieldMapping } from '../../generated/mapping-model';
import type { FieldRefusal } from '../../model/command';
import { fieldPathSteps } from '../../model/field-path';
import { reachedBoxes } from '../../model/reached';

type Side = 'source' | 'target';

/** One visible row of a structure tree. */
export interface TreeRow {
  /** The field path (ADR-0007): the root's step, then each child's step, each after a `/`. */
  path: string;
  depth: number;
  field: FieldDto;
  /** A structure field: it has a type with child fields, even when that type lists none. */
  expandable: boolean;
  expanded: boolean;
  /** The refusal that refuses the row's field in its tree, nearest the root (`refusalAt`); absent when none does. */
  refusal?: FieldRefusal;
}

/**
 * A field's own refusal, when it refuses the field on `side`: a refusal whose side is `target` holds in a target only,
 * and a source reads the field (ADR-0008, #94, #95).
 */
const refusing = (field: FieldDto, side: Side): FieldRefusal | undefined =>
  side === 'target' || field.refusal?.side !== 'target' ? field.refusal : undefined;

/**
 * The refusal of a row of `field` on `side` whose parent row has the refusal `above`: the one nearest the root, so the
 * parent's when it has one, else the field's own when it refuses the field on `side`.
 */
export const rowRefusal = (field: FieldDto, side: Side, above: FieldRefusal | undefined): FieldRefusal | undefined =>
  above ?? refusing(field, side);

/**
 * The refusal that refuses the field at `path` on `side`, as compile reports it (ADR-0008 Shape, ADR-0027): the
 * refusal of the refused field nearest the root on the path, counting one that holds in a target only on the target
 * side only. The wire gives each field its own refusal, since a type's fields are shared (ADR-0015). `undefined` when
 * no field on the path refuses it, or the structure has no field at `path`. A step is matched against its first
 * same-step sibling: same-step siblings all carry one `ambiguous-field-path`, so the walk ends there anyway.
 */
export function refusalAt(structure: StructureLoaded, path: FieldPath, side: Side): FieldRefusal | undefined {
  const steps = fieldPathSteps(path);
  let field: FieldDto | undefined = steps[0] === structure.root.step ? structure.root : undefined;
  for (const step of steps.slice(1)) {
    if (field === undefined || refusing(field, side)) break;
    field = field.type === undefined ? undefined : structure.types[field.type]?.find((c) => c.step === step);
  }
  return field && refusing(field, side);
}

/**
 * The field at `path` in a structure, a step matched against its first same-step sibling as `refusalAt` matches it; any
 * depth of a recursive type is found. `undefined` when the structure has no field at `path`, as for an orphaned reference.
 */
export function fieldAt(structure: StructureLoaded, path: FieldPath): FieldDto | undefined {
  const steps = fieldPathSteps(path);
  let field: FieldDto | undefined = steps[0] === structure.root.step ? structure.root : undefined;
  for (const step of steps.slice(1)) field = field?.type === undefined ? undefined : structure.types[field.type]?.find((c) => c.step === step);
  return field;
}

/**
 * Flattens the structure graph (ADR-0015) into the visible rows of the `side` tree: the root, then the child fields of
 * every expanded row. A recursive type is expanded only as deep as its paths are, so the rows are always finite
 * (ADR-0008). Each row carries the refusal `refusalAt` gives its path.
 */
export function treeRows(structure: StructureLoaded, expanded: ReadonlySet<string>, side: Side): TreeRow[] {
  const rows: TreeRow[] = [];
  const visit = (field: FieldDto, path: string, depth: number, above: FieldRefusal | undefined) => {
    const open = field.type !== undefined && expanded.has(path);
    const refusal = rowRefusal(field, side, above);
    rows.push({ path, depth, field, expandable: field.type !== undefined, expanded: open, ...(refusal && { refusal }) });
    if (!open) return;
    for (const child of structure.types[field.type!] ?? []) visit(child, `${path}/${child.step}`, depth + 1, refusal);
  };
  visit(structure.root, `/${structure.root.step}`, 0, undefined);
  return rows;
}

/**
 * The rows of every field `keep` holds for, and of each of its ancestors, in tree order, whatever is expanded. Below a
 * field whose type is already on its path the walk stops, so that a recursive type (ADR-0008) ends there. A row with
 * rows below it is shown expanded, any other collapsed. Each row carries the refusal `treeRows` gives it (ADR-0027).
 */
function keptRows(structure: StructureLoaded, side: Side, keep: (field: FieldDto) => boolean): TreeRow[] {
  const rows: TreeRow[] = [];
  const visit = (field: FieldDto, path: string, depth: number, above: FieldRefusal | undefined, types: ReadonlySet<string>) => {
    const refusal = rowRefusal(field, side, above);
    const at = rows.length;
    rows.push({ path, depth, field, expandable: field.type !== undefined, expanded: false, ...(refusal && { refusal }) });
    if (field.type !== undefined && !types.has(field.type)) {
      const below = new Set(types).add(field.type);
      for (const child of structure.types[field.type] ?? []) visit(child, `${path}/${child.step}`, depth + 1, refusal, below);
    }
    if (rows.length > at + 1) rows[at]!.expanded = true;
    else if (!keep(field)) rows.pop();
  };
  visit(structure.root, `/${structure.root.step}`, 0, undefined, new Set());
  return rows;
}

/**
 * The field paths Expand all opens (spec #135): every structure field that has child fields, down to a field whose type
 * is already on its path, which stays collapsed so that a recursive type ends.
 */
export function expandAllPaths(structure: StructureLoaded): string[] {
  return keptRows(structure, 'source', () => true)
    .filter((row) => row.expanded)
    .map((row) => row.path);
}

/** A tree's field count (spec #135): the rows Expand all shows, so a recursive type counts as deep as it opens. */
export function fieldCount(structure: StructureLoaded): number {
  return keptRows(structure, 'source', () => true).length;
}

/** Whether a field's local name holds the search `query`, ignoring case; the namespace and the attribute mark do not count. */
export function nameMatches(field: FieldDto, query: string): boolean {
  return field.localName.toLowerCase().includes(query.toLowerCase());
}

/**
 * The rows of a search (spec #135): every field whose local name matches `query` (`nameMatches`), and each of its
 * ancestors, as far down as Expand all opens. An ancestor is shown expanded; a match with no match below it collapsed.
 */
export function searchRows(structure: StructureLoaded, query: string, side: Side): TreeRow[] {
  return keptRows(structure, side, (field) => nameMatches(field, query));
}

/** A field's effective occurrence, `min..max`, with `unbounded` for an absent max. */
export function occurrenceText(field: FieldDto): string {
  return `${field.min}..${field.max ?? 'unbounded'}`;
}

/**
 * The source fields read by the source-field boxes that a target field mapping's `result` reaches (ADR-0013), each
 * once, in the order `reachedBoxes` meets those boxes. An unreachable box adds none, so a mapping whose `result` is
 * null has none.
 */
export function reachedSourceFields(mapping: TargetFieldMapping | undefined): FieldPath[] {
  return [...reachedSourceBoxes(mapping).keys()];
}

/** The reached source fields of `reachedSourceFields`, each with the reached source-field boxes that read it. */
export function reachedSourceBoxes(mapping: TargetFieldMapping | undefined): Map<FieldPath, SourceFieldBox[]> {
  const fields = new Map<FieldPath, SourceFieldBox[]>();
  for (const id of reachedBoxes(mapping)) {
    const box = mapping!.boxes[id]!;
    if (box.kind === 'source') fields.set(box.field, [...(fields.get(box.field) ?? []), box]);
  }
  return fields;
}
