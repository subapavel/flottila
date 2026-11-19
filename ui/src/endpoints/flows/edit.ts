import { isMap, isScalar, isSeq, parseAllDocuments, parseDocument, type CST, type Document, type Node, type Pair, type YAMLMap, type YAMLSeq } from 'yaml';
import { yamlFindings, type Lock } from './flowYaml';
import { BUILT_IN, type StepType } from './steps';
import type { Path } from './types';

/**
 * A change to a file: an operation of JSON Patch on a path into the file's value. `add` sets a member of a mapping, or
 * inserts into a list at an index (the length appends); `move` moves an item of a list to an index of a list, counted
 * after the removal, or renames a key of a mapping in place.
 */
export type Op =
  | { op: 'add'; path: Path; value: unknown }
  | { op: 'replace'; path: Path; value: unknown }
  | { op: 'remove'; path: Path }
  | { op: 'move'; from: Path; path: Path };

/** Why an edit was refused; nothing was changed. */
export class EditRefused extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'EditRefused';
  }
}

/** The most operations in one edit, the deepest path, and the longest line of a new step written on one line. */
const MAX_OPS = 256;
const MAX_PATH = 32;
const MAX_LINE = 100;

const refuse = (message: string): never => {
  throw new EditRefused(message);
};

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** `path` as the node writes a place in its refusals: `processes.enrich.steps[1].name`. */
export const placeOf = (path: Path) =>
  path.length === 0 ? 'the file' : path.reduce<string>((out, s) => (typeof s === 'number' ? `${out}[${s}]` : out === '' ? s : `${out}.${s}`), '');

const startsWith = (path: Path, prefix: Path) => prefix.length <= path.length && prefix.every((s, i) => path[i] === s);
const samePath = (a: Path, b: Path) => a.length === b.length && startsWith(a, b);

/** Whether `a` and `b` are the same value, with the keys of every mapping in the same order. */
export function sameOrdered(a: unknown, b: unknown): boolean {
  if (Array.isArray(a)) return Array.isArray(b) && a.length === b.length && a.every((v, i) => sameOrdered(v, b[i]));
  if (isRecord(a)) {
    if (!isRecord(b)) return false;
    const ka = Object.keys(a);
    const kb = Object.keys(b);
    return ka.length === kb.length && ka.every((k, i) => k === kb[i] && sameOrdered(a[k], b[k]));
  }
  return Object.is(a, b);
}

/** `value` as JSON holds it: members whose value is undefined are left out; anything that is no JSON value is refused. */
function clean(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(clean);
  if (isRecord(value)) return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined).map(([k, v]) => [k, clean(v)]));
  if (value === null || typeof value === 'string' || typeof value === 'boolean' || typeof value === 'number') return value;
  return refuse(`a value of the kind ${typeof value} cannot be written`);
}

// ---------------------------------------------------------------------------------------------------------------------
// The value the operations make: what the edited text must read back as.

/**
 * `next` written over `old`: a mapping over a mapping keeps the keys it keeps in their places, each merged, and adds the
 * new ones last; a list over a list is merged item by item.
 */
function merged(old: unknown, next: unknown): unknown {
  if (isRecord(old) && isRecord(next) && Object.keys(old).length > 0 && Object.keys(next).length > 0) {
    const out: Record<string, unknown> = {};
    for (const k of Object.keys(old)) if (Object.hasOwn(next, k)) out[k] = merged(old[k], next[k]);
    for (const k of Object.keys(next)) if (!Object.hasOwn(old, k)) out[k] = next[k];
    return out;
  }
  if (Array.isArray(old) && Array.isArray(next) && old.length > 0 && next.length > 0) return next.map((v, i) => (i < old.length ? merged(old[i], v) : v));
  return next;
}

function at(value: unknown, path: Path): unknown {
  let v = value;
  for (const s of path) {
    if (Array.isArray(v) && typeof s === 'number') v = v[s];
    else if (isRecord(v) && typeof s === 'string' && Object.hasOwn(v, s)) v = v[s];
    else return undefined;
  }
  return v;
}

/** The value of the file `value` once `op` is applied, as {@link merged} writes a value over another. */
function patched(value: unknown, op: Op): unknown {
  const root = structuredClone(value);
  const holder = (path: Path) => at(root, path.slice(0, -1));
  switch (op.op) {
    case 'add':
    case 'replace': {
      if (op.path.length === 0) return merged(root, op.value);
      const parent = holder(op.path);
      const last = op.path.at(-1)!;
      if (Array.isArray(parent)) {
        if (op.op === 'add') parent.splice(last as number, 0, op.value);
        else parent[last as number] = merged(parent[last as number], op.value);
      } else (parent as Record<string, unknown>)[last as string] = merged((parent as Record<string, unknown>)[last as string], op.value);
      return root;
    }
    case 'remove': {
      const parent = holder(op.path);
      const last = op.path.at(-1)!;
      if (Array.isArray(parent)) parent.splice(last as number, 1);
      else delete (parent as Record<string, unknown>)[last as string];
      return root;
    }
    case 'move': {
      const from = holder(op.from);
      const last = op.from.at(-1)!;
      if (isRecord(from)) {
        const renamed = Object.entries(from).map(([k, v]) => [k === last ? (op.path.at(-1) as string) : k, v] as const);
        for (const k of Object.keys(from)) delete from[k];
        for (const [k, v] of renamed) from[k] = v;
        return root;
      }
      const [moved] = (from as unknown[]).splice(last as number, 1);
      (holder(op.path) as unknown[]).splice(op.path.at(-1) as number, 0, moved);
      return root;
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// The text as the edit sees it.

/** A text read for an edit: its first document, its value, its locks, and the indentation it uses. */
interface Parsed {
  /** Ends with a line break, unless it is empty. */
  text: string;
  eol: string;
  doc: Document.Parsed;
  value: unknown;
  locks: Lock[];
  /** How far a mapping's keys stand past the key that holds them. */
  unit: number;
  /** How far a list's dashes stand past the key that holds it. */
  seqOffset: number;
}

function read(text: string, eol: string, types: Readonly<Record<string, StepType>>): Parsed {
  const docs = parseAllDocuments(text, { uniqueKeys: false, merge: false, keepSourceTokens: true, prettyErrors: false, logLevel: 'error' });
  const doc = Array.isArray(docs) ? docs[0] : undefined;
  if (doc === undefined) return { text, eol, doc: parseDocument('', { keepSourceTokens: true }) as Document.Parsed, value: null, locks: [], unit: 2, seqOffset: 2 };
  const error = docs.flatMap((d) => d.errors)[0];
  if (error !== undefined) refuse(`the file is not YAML: ${error.message.split('\n')[0]}`);
  const value = doc.toJS() as unknown;
  const locks = yamlFindings(doc, text, []);
  // a step that is no mapping with a text type is locked too
  for (const list of stepLists(value, types)) {
    (at(value, list) as unknown[]).forEach((item, i) => {
      if (!isRecord(item) || typeof item.type !== 'string' || item.type === '') locks.push({ path: [...list, i], reason: 'a step that is no mapping with a text type', kind: 'step' });
    });
  }
  let unit: number | undefined;
  let seqOffset: number | undefined;
  const visit = (node: unknown, keyCol: number | null) => {
    if (isMap(node)) {
      if (!node.flow && keyCol !== null && unit === undefined && node.items[0] !== undefined && isScalar(node.items[0].key)) {
        unit = column(text, node.items[0].key.range![0]) - keyCol;
      }
      for (const pair of node.items) if (isScalar(pair.key)) visit(pair.value, node.flow ? null : column(text, pair.key.range![0]));
    } else if (isSeq(node)) {
      if (!node.flow && keyCol !== null && seqOffset === undefined && node.items.length > 0) seqOffset = column(text, dashOf(node, 0)) - keyCol;
      for (const item of node.items) visit(item, null);
    }
  };
  visit(doc.contents, null);
  return { text, eol, doc, value, locks, unit: unit !== undefined && unit > 0 ? unit : 2, seqOffset: seqOffset !== undefined && seqOffset >= 0 ? seqOffset : 2 };
}

/** The paths of the lists of steps in `value`, a flow: its own, each process's, and those inside steps whose type holds some. */
export function stepLists(value: unknown, types: Readonly<Record<string, StepType>> = BUILT_IN): Path[] {
  if (!isRecord(value) || value.kind !== 'flow') return [];
  const found: Path[] = [];
  const walk = (path: Path, items: unknown) => {
    if (!Array.isArray(items) || path.length > MAX_PATH) return;
    found.push(path);
    items.forEach((item, i) => {
      const type = isRecord(item) && typeof item.type === 'string' ? types[item.type] : undefined;
      for (const pattern of type?.stepLists ?? []) for (const inner of matching(item, pattern, [])) walk([...path, i, ...inner], at(item, inner));
    });
  };
  if (isRecord(value.processes)) {
    for (const [name, process] of Object.entries(value.processes)) if (isRecord(process)) walk(['processes', name, 'steps'], process.steps);
  }
  walk(['steps'], value.steps);
  return found;
}

/** The paths inside `value` that `pattern` matches, below `here`, where `*` is any key or index. */
function matching(value: unknown, pattern: readonly string[], here: Path): Path[] {
  if (pattern.length === 0) return [here];
  const [head, ...rest] = pattern;
  if (head === '*') {
    if (Array.isArray(value)) return value.flatMap((v, i) => matching(v, rest, [...here, i]));
    if (isRecord(value)) return Object.entries(value).flatMap(([k, v]) => matching(v, rest, [...here, k]));
    return [];
  }
  return isRecord(value) && head !== undefined && Object.hasOwn(value, head) ? matching(value[head], rest, [...here, head]) : [];
}

const lineStart = (text: string, pos: number) => text.lastIndexOf('\n', pos - 1) + 1;
const lineEnd = (text: string, pos: number) => {
  const i = text.indexOf('\n', pos);
  return i < 0 ? text.length : i + 1;
};
const column = (text: string, pos: number) => pos - lineStart(text, pos);
const blank = (line: string) => /^[ \t]*\r?\n?$/.test(line);
const commentLine = (line: string) => /^[ \t]*#/.test(line);

/** The offset of the dash of item `i` of the block list `seq`. */
function dashOf(seq: YAMLSeq, i: number): number {
  const token = seq.srcToken as CST.BlockSequence | undefined;
  const dash = token?.type === 'block-seq' ? token.items[i]?.start.find((t) => t.type === 'seq-item-ind') : undefined;
  return dash === undefined ? refuse('the list is not written as the editor reads it') : dash.offset;
}

/** The offset just after the `:` of `pair`. */
function colonOf(pair: Pair): number {
  const sep = (pair.srcToken as CST.CollectionItem | undefined)?.sep?.find((t) => t.type === 'map-value-ind');
  return sep === undefined ? refuse('a key without its colon') : sep.offset + 1;
}

const keyStart = (pair: Pair) => (pair.key as Node).range![0];

/** Where the value of `node` ends: the end of its last item's value for a block collection, else its own. */
function valueEnd(node: unknown, fallback: number): number {
  if (isMap(node) && !node.flow && node.items.length > 0) {
    const last = node.items.at(-1)!;
    return valueEnd(last.value, last.srcToken === undefined ? (last.key as Node).range![1] : colonOf(last));
  }
  if (isSeq(node) && !node.flow && node.items.length > 0) return valueEnd(node.items.at(-1), fallback);
  return isScalar(node) || isMap(node) || isSeq(node) ? (node.range?.[1] ?? fallback) : fallback;
}

/** The end of the line where a value ending at `end` ends, with its line break; a block scalar already ends with one. */
const contentLineEnd = (text: string, end: number) => (end > 0 && text[end - 1] === '\n' ? end : lineEnd(text, end));

/** What stands at a path: the node, and the collection that holds it (none for the root). */
type Found = { node: unknown; holder: { map: YAMLMap; index: number } | { seq: YAMLSeq; index: number } | null };

function locate(p: Parsed, path: Path): Found | null {
  let node: unknown = p.doc.contents;
  let holder: Found['holder'] = null;
  for (const s of path) {
    if (isMap(node) && typeof s === 'string') {
      const index = node.items.findIndex((pair) => isScalar(pair.key) && String(pair.key.value) === s);
      if (index < 0) return null;
      holder = { map: node, index };
      node = node.items[index]!.value;
    } else if (isSeq(node) && typeof s === 'number' && Number.isInteger(s) && s >= 0 && s < node.items.length) {
      holder = { seq: node, index: s };
      node = node.items[s];
    } else return null;
  }
  return { node, holder };
}

function must(p: Parsed, path: Path): Found {
  return locate(p, path) ?? refuse(`there is nothing at ${placeOf(path)}`);
}

/**
 * Refuses when a lock stands where the edit changes the file: on or inside a node changed with all it holds (`deep`), on
 * a collection whose items are added or removed (`shallow`), or on a node holding either that locks what it holds: an
 * anchor, a tag, a list on one line, a mapping in braces over several lines, and for a key used twice or a merge key,
 * the value of that key.
 */
function guard(p: Parsed, deep: Path[], shallow: Path[]) {
  for (const lock of p.locks) {
    const covering = (path: Path) =>
      lock.path.length < path.length &&
      startsWith(path, lock.path) &&
      (lock.kind === 'anchor' || lock.kind === 'tag' || lock.kind === 'flow' || ((lock.kind === 'twice' || lock.kind === 'merge') && path[lock.path.length] === lock.key));
    if (deep.some((path) => startsWith(lock.path, path) || covering(path)) || shallow.some((path) => samePath(lock.path, path) || covering(path))) {
      refuse(`${placeOf(lock.path)} holds ${lock.reason}, which the editor does not change`);
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Writing values.

type Place = 'value' | 'item' | 'flow' | 'key' | 'flowKey';

/** Whether `source` written at `place` reads as `expected` in YAML 1.2, as the browser reads it, and in YAML 1.1. */
function readsAs(source: string, expected: unknown, place: Place): boolean {
  const snippet = { value: `k: ${source}\n`, item: `- ${source}\n`, flow: `{ k: ${source} }\n`, key: `${source}: x\n`, flowKey: `{ ${source}: x }\n` }[place];
  return (['1.2', '1.1'] as const).every((version) => {
    const doc = parseDocument(snippet, { version, uniqueKeys: false, merge: false, prettyErrors: false, logLevel: 'silent' });
    if (doc.errors.length > 0 || doc.warnings.length > 0) return false;
    const root = doc.contents;
    const pair = isMap(root) ? root.items[0] : undefined;
    const node = place === 'item' ? (isSeq(root) ? root.items[0] : undefined) : place === 'key' || place === 'flowKey' ? pair?.key : pair?.value;
    return isScalar(node) && node.type === 'PLAIN' && node.anchor === undefined && node.tag === undefined && Object.is(node.value, expected);
  });
}

/** `value`, a scalar, as one line of YAML at `place`: plain where both readers read it as the value, else double-quoted. */
function scalarText(value: unknown, place: Place): string {
  if (value === null) return 'null';
  if (typeof value === 'boolean') return String(value);
  if (typeof value === 'number') {
    const source = Number.isNaN(value) ? '.nan' : value === Infinity ? '.inf' : value === -Infinity ? '-.inf' : String(value);
    return readsAs(source, value, place) || Number.isNaN(value) ? source : refuse(`the number ${source} cannot be written so that the node reads it as the editor does`);
  }
  if (typeof value === 'string') return value !== '' && !value.includes('\n') && readsAs(value, value, place) ? value : JSON.stringify(value);
  return refuse('a list or a mapping cannot be written here');
}

/** `text`, of several lines, as a literal block: its header and its lines at `col`, or null when it would not read back. */
function literal(text: string, col: number, parentCol: number, eol: string): { header: string; lines: string[] } | null {
  // eslint-disable-next-line no-control-regex
  if (/[\r\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\ufeff]/.test(text)) return null;
  const trailing = /\n*$/.exec(text)![0].length;
  const body = trailing === 0 ? text : text.slice(0, -1);
  const lines = body.split('\n');
  const first = lines.find((l) => l !== '');
  if (first === undefined) return null;
  const header = `|${/^[ \t]/.test(first) ? String(col - parentCol) : ''}${trailing === 0 ? '-' : trailing === 1 ? '' : '+'}`;
  const written = lines.map((l) => (l === '' ? '' : `${' '.repeat(col)}${l}`));
  const check = parseDocument(`${' '.repeat(parentCol)}k: ${header}${eol}${written.join(eol)}${eol}`, { prettyErrors: false, logLevel: 'silent' });
  const read = isMap(check.contents) ? check.contents.items[0]?.value : undefined;
  return check.errors.length === 0 && isScalar(read) && read.value === text ? { header, lines: written } : null;
}

/** `value` inside a mapping on one line, with `spaced` braces (`{ a: 1 }`) or not; a list cannot be. */
function flowText(value: unknown, spaced: boolean): string {
  if (Array.isArray(value)) return refuse('a list cannot be written inside a mapping on one line; write the step over several lines first');
  if (isRecord(value)) {
    const entries = Object.entries(value).map(([k, v]) => `${scalarText(k, 'flowKey')}: ${flowText(v, spaced)}`);
    return entries.length === 0 ? '{}' : spaced ? `{ ${entries.join(', ')} }` : `{${entries.join(', ')}}`;
  }
  return typeof value === 'string' && value.includes('\n') ? JSON.stringify(value) : scalarText(value, 'flow');
}

/** How a writer indents: a mapping's keys `unit` past their key, a list's dashes `seqOffset` past theirs, an item's keys `offset` past its dash. */
interface Style {
  unit: number;
  seqOffset: number;
  eol: string;
}

/** What follows `key:` for `value` with the key at `col`: text on the key's line, and the lines below it. */
function blockValue(value: unknown, col: number, style: Style): { inline: string; lines: string[] } {
  if (isRecord(value)) {
    const entries = Object.entries(value);
    return entries.length === 0 ? { inline: ' {}', lines: [] } : { inline: '', lines: entries.flatMap(([k, v]) => pairLines(k, v, col + style.unit, style)) };
  }
  if (Array.isArray(value)) {
    return value.length === 0 ? { inline: ' []', lines: [] } : { inline: '', lines: value.flatMap((v) => itemLines(v, col + style.seqOffset, 2, style)) };
  }
  if (typeof value === 'string' && value.includes('\n')) {
    const block = literal(value, col + style.unit, col, style.eol);
    if (block !== null) return { inline: ` ${block.header}`, lines: block.lines };
  }
  return { inline: ` ${scalarText(value, 'value')}`, lines: [] };
}

/** The lines of `key: value` with the key at `col`. */
function pairLines(key: string, value: unknown, col: number, style: Style): string[] {
  const { inline, lines } = blockValue(value, col, style);
  return [`${' '.repeat(col)}${scalarText(key, 'key')}:${inline}`, ...lines];
}

/** The lines of `value` as an item of a list, its dash at `dash` and the keys of a mapping `offset` past it. */
function itemLines(value: unknown, dash: number, offset: number, style: Style): string[] {
  const nested = isRecord(value) && Object.keys(value).length > 0 ? Object.entries(value).flatMap(([k, v]) => pairLines(k, v, dash + offset, style)) : null;
  const inner = nested ?? (Array.isArray(value) && value.length > 0 ? value.flatMap((v) => itemLines(v, dash + offset, 2, style)) : null);
  if (inner !== null) {
    inner[0] = `${' '.repeat(dash)}-${' '.repeat(offset - 1)}${inner[0]!.slice(dash + offset)}`;
    return inner;
  }
  if (typeof value === 'string' && value.includes('\n')) {
    const block = literal(value, dash + offset, dash, style.eol);
    if (block !== null) return [`${' '.repeat(dash)}- ${block.header}`, ...block.lines];
  }
  return [`${' '.repeat(dash)}- ${isRecord(value) ? '{}' : Array.isArray(value) ? '[]' : scalarText(value, 'item')}`];
}

const joined = (lines: string[], eol: string) => lines.map((l) => `${l}${eol}`).join('');

const splice = (text: string, from: number, to: number, insert: string) => `${text.slice(0, from)}${insert}${text.slice(to)}`;

// ---------------------------------------------------------------------------------------------------------------------
// The spans of items, and the primitive edits.

/** The span of item `i` of the block list `seq`: the comment lines directly above it, its lines, and a blank line below it when one stands above too. */
function itemSpan(p: Parsed, seq: YAMLSeq, i: number): { start: number; end: number; dash: number } {
  const { text } = p;
  const dash = dashOf(seq, i);
  let start = lineStart(text, dash);
  if (text.slice(start, dash).trim() !== '') refuse('a list item that shares its line with another one');
  const floor = i > 0 ? contentLineEnd(text, valueEnd(seq.items[i - 1], dashOf(seq, i - 1) + 1)) : 0;
  while (start > floor) {
    const above = lineStart(text, start - 1);
    if (!commentLine(text.slice(above, start))) break;
    start = above;
  }
  let end = contentLineEnd(text, valueEnd(seq.items[i], dash + 1));
  if (start > 0 && end < text.length && blank(text.slice(lineStart(text, start - 1), start)) && blank(text.slice(end, lineEnd(text, end)))) end = lineEnd(text, end);
  return { start, end, dash };
}

/** The style of a new item of `seq` at `index`: that of the item before it, else the one after it. */
function newItem(p: Parsed, seq: YAMLSeq, index: number, value: unknown, dash: number): string {
  const neighbour = index > 0 ? index - 1 : index;
  const near = seq.items[neighbour];
  const style: Style = { unit: p.unit, seqOffset: p.seqOffset, eol: p.eol };
  if (isMap(near) && near.flow === true && isRecord(value)) {
    try {
      const line = `${' '.repeat(dash)}- ${flowText(value, p.text[near.range![0] + 1] === ' ')}`;
      if (line.length <= MAX_LINE) return `${line}${p.eol}`;
    } catch (failed) {
      if (!(failed instanceof EditRefused)) throw failed;
    }
  }
  const first = isMap(near) && !near.flow && near.items[0] !== undefined ? keyStart(near.items[0]) : undefined;
  const offset = first === undefined ? 2 : Math.max(2, column(p.text, first) - column(p.text, dashOf(seq, neighbour)));
  return joined(itemLines(value, dash, offset, style), p.eol);
}

/** Inserts into the block list at `listPath`, at `index`, a new item `value`, or the text of an item moved there with its dash at `movedDash`. */
function insertItem(p: Parsed, listPath: Path, index: number, item: { value: unknown } | { moved: string; movedDash: number }): string {
  const { node } = must(p, listPath);
  if (!isSeq(node)) return refuse(`${placeOf(listPath)} is not a list`);
  guard(p, [], [listPath]);
  if (node.flow === true || node.items.length === 0) return refuse(`${placeOf(listPath)} is written on one line`);
  const n = node.items.length;
  if (!Number.isInteger(index) || index < 0 || index > n) return refuse(`${placeOf(listPath)} has no place ${index}`);
  const dash = column(p.text, dashOf(node, 0));
  const pos = index < n ? itemSpan(p, node, index).start : contentLineEnd(p.text, valueEnd(node.items[n - 1], dashOf(node, n - 1) + 1));
  if ('value' in item) return splice(p.text, pos, pos, newItem(p, node, index, item.value, dash));
  let moved = reindent(item.moved, dash - item.movedDash);
  // a blank line the item took below it goes above it at the end of a list
  if (index === n && /\n[ \t]*\r?\n$/.test(moved)) moved = `${p.eol}${moved.slice(0, moved.lastIndexOf('\n', moved.length - 2) + 1)}`;
  return splice(p.text, pos, pos, moved);
}

/** `text`, whole lines, shifted right by `delta` spaces, or left by as many as each line has. */
function reindent(text: string, delta: number): string {
  if (delta === 0) return text;
  return (text.match(/[^\n]*\n|[^\n]+$/g) ?? [])
    .map((line) => (blank(line) ? line : delta > 0 ? `${' '.repeat(delta)}${line}` : line.slice(Math.min(-delta, /^ */.exec(line)![0].length))))
    .join('');
}

function removeItem(p: Parsed, path: Path): string {
  const listPath = path.slice(0, -1);
  const { node } = must(p, listPath);
  if (!isSeq(node)) return refuse(`${placeOf(listPath)} is not a list`);
  guard(p, [path], [listPath]);
  if (node.items.length === 1) return rewrite(p, listPath, []);
  const { start, end } = itemSpan(p, node, path.at(-1) as number);
  return splice(p.text, start, end, '');
}

function addMember(p: Parsed, mapPath: Path, key: string, value: unknown): string {
  const { node } = must(p, mapPath);
  if (!isMap(node)) return refuse(`${placeOf(mapPath)} is not a mapping`);
  guard(p, [], [mapPath]);
  if (node.flow === true) {
    const entry = `${scalarText(key, 'flowKey')}: ${flowText(value, p.text[node.range![0] + 1] === ' ')}`;
    const last = node.items.at(-1);
    if (last === undefined) return splice(p.text, node.range![0], node.range![1], `{ ${entry} }`);
    const end = valueEnd(last.value, colonOf(last));
    return splice(p.text, end, end, `, ${entry}`);
  }
  if (node.items.length === 0) return rewrite(p, mapPath, { [key]: value });
  const col = column(p.text, keyStart(node.items[0]!));
  const pos = contentLineEnd(p.text, valueEnd(node, 0));
  return splice(p.text, pos, pos, joined(pairLines(key, value, col, { unit: p.unit, seqOffset: p.seqOffset, eol: p.eol }), p.eol));
}

function removeMember(p: Parsed, path: Path): string {
  const mapPath = path.slice(0, -1);
  const found = must(p, path);
  const holder = found.holder as { map: YAMLMap; index: number };
  const map = holder.map;
  guard(p, [path], [mapPath]);
  if (map.items.length === 1) return mapPath.length === 0 ? refuse('the file keeps at least one key') : rewrite(p, mapPath, {});
  const i = holder.index;
  const pair = map.items[i]!;
  const end = valueEnd(pair.value, colonOf(pair));
  if (map.flow === true) {
    return i > 0 ? splice(p.text, valueEnd(map.items[i - 1]!.value, colonOf(map.items[i - 1]!)), end, '') : splice(p.text, keyStart(pair), keyStart(map.items[1]!), '');
  }
  const start = keyStart(pair);
  const lineEnds = contentLineEnd(p.text, end);
  if (p.text.slice(lineStart(p.text, start), start).trim() !== '') {
    // the first key of an item, on the line of its dash: the next key takes its place
    const next = keyStart(map.items[i + 1]!);
    if (p.text.slice(lineEnds, next).trim() !== '') refuse(`a comment stands between ${placeOf(path)} and the key after it`);
    return splice(p.text, start, next, '');
  }
  return splice(p.text, lineStart(p.text, start), lineEnds, '');
}

function rename(p: Parsed, from: Path, to: Path): string {
  const found = must(p, from);
  const holder = found.holder as { map: YAMLMap; index: number };
  guard(p, [from], [from.slice(0, -1)]);
  const key = to.at(-1);
  if (typeof key !== 'string') return refuse('a key is renamed to a key');
  if (locate(p, to) !== null) return refuse(`${placeOf(to)} is there already`);
  const node = holder.map.items[holder.index]!.key as Node;
  return splice(p.text, node.range![0], node.range![1], scalarText(key, holder.map.flow === true ? 'flowKey' : 'key'));
}

/** Writes `value` in place of what stands at `path`: a scalar in place, a block collection as lines below its key or dash. */
function rewrite(p: Parsed, path: Path, value: unknown): string {
  const { node, holder } = must(p, path);
  guard(p, [path], []);
  const { text, eol } = p;
  const style: Style = { unit: p.unit, seqOffset: p.seqOffset, eol };
  if (holder === null) return refuse('the whole file is written only when it is new');
  const flow = 'map' in holder ? holder.map.flow === true : holder.seq.flow === true;
  const old = node as Node | null;
  const range = old?.range ?? null;
  const oldBlock = (isMap(old) || isSeq(old)) && old.flow !== true;
  const oldBlockScalar = isScalar(old) && (old.type === 'BLOCK_LITERAL' || old.type === 'BLOCK_FOLDED');
  if (range === null) return refuse(`${placeOf(path)} cannot be written`);
  if (flow) return splice(text, range[0], range[1], flowText(value, text[(holder as { map: YAMLMap }).map.range![0] + 1] === ' '));
  if ('map' in holder) {
    const pair = holder.map.items[holder.index]!;
    const colon = colonOf(pair);
    const { inline, lines } = blockValue(value, column(text, keyStart(pair)), style);
    if (oldBlock || oldBlockScalar) {
      // a block value ends at a line end: the new one is written from the colon over all its lines
      return splice(text, colon, contentLineEnd(text, valueEnd(old, colon)), `${inline}${eol}${joined(lines, eol)}`);
    }
    // a scalar keeps the comment after it; lines below it go after that comment
    const after = contentLineEnd(text, range[1]);
    return splice(splice(text, after, after, joined(lines, eol)), colon, range[1], inline);
  }
  const seq = holder.seq;
  const dashAt = dashOf(seq, holder.index);
  const dash = column(text, dashAt);
  const scalar = !isRecord(value) && !Array.isArray(value) && !(typeof value === 'string' && value.includes('\n'));
  // a scalar over a scalar or a mapping on one line keeps the comment after it
  if (scalar && !oldBlock && !oldBlockScalar) return splice(text, range[0], range[1], scalarText(value, 'item'));
  const end = oldBlock ? contentLineEnd(text, valueEnd(old, dashAt + 1)) : contentLineEnd(text, range[1]);
  const first = isMap(old) && !old.flow && old.items[0] !== undefined ? keyStart(old.items[0]) : undefined;
  const offset = first === undefined ? 2 : Math.max(2, column(text, first) - dash);
  return splice(text, lineStart(text, dashAt), end, joined(itemLines(value, dash, offset, style), eol));
}

/** The operations that write `value` over the mapping or block list at `path` key by key or item by item, or null to write it whole. */
function changes(p: Parsed, path: Path, value: unknown): Op[] | null {
  const { node } = must(p, path);
  const old = at(p.value, path);
  if (isMap(node) && isRecord(old) && isRecord(value) && Object.keys(old).length > 0 && Object.keys(value).length > 0) {
    const ops: Op[] = [];
    for (const k of Object.keys(old)) if (!Object.hasOwn(value, k)) ops.push({ op: 'remove', path: [...path, k] });
    for (const k of Object.keys(old)) if (Object.hasOwn(value, k) && !sameOrdered(old[k], value[k])) ops.push({ op: 'replace', path: [...path, k], value: value[k] });
    for (const k of Object.keys(value)) if (!Object.hasOwn(old, k)) ops.push({ op: 'add', path: [...path, k], value: value[k] });
    return ops;
  }
  if (isSeq(node) && node.flow !== true && Array.isArray(old) && Array.isArray(value) && old.length > 0 && value.length > 0) {
    const ops: Op[] = [];
    value.forEach((v, i) => {
      if (i < old.length && !sameOrdered(old[i], v)) ops.push({ op: 'replace', path: [...path, i], value: v });
    });
    for (let i = old.length - 1; i >= value.length; i--) ops.push({ op: 'remove', path: [...path, i] });
    for (let i = old.length; i < value.length; i++) ops.push({ op: 'add', path: [...path, i], value: value[i] });
    return ops;
  }
  return null;
}

/** The text of a new file holding `value`, a mapping, in block style with two spaces of indent. */
function wholeFile(value: unknown, eol: string): string {
  if (!isRecord(value) || Object.keys(value).length === 0) return refuse('a new file holds a mapping with at least one key');
  const style: Style = { unit: 2, seqOffset: 2, eol };
  return joined(Object.entries(value).flatMap(([k, v]) => pairLines(k, v, 0, style)), eol);
}

// ---------------------------------------------------------------------------------------------------------------------
// One operation, checked.

function checkPath(path: Path) {
  if (path.length > MAX_PATH) refuse(`a path is at most ${MAX_PATH} steps deep`);
}

/** The text with `op` applied, read; refused when it would not read back as the value with `op` applied. */
function apply(p: Parsed, raw: Op, types: Readonly<Record<string, StepType>>): Parsed {
  const op = (raw.op === 'remove' || raw.op === 'move' ? raw : { ...raw, value: clean(raw.value) }) as Op;
  checkPath(op.path);
  const target = op.op === 'move' ? op.from : op.path;
  checkPath(target);
  if (target.length === 0) {
    if (op.op !== 'add' && op.op !== 'replace') return refuse('the file itself is neither removed nor moved');
    if (p.doc.contents !== null) return refuse('the whole file is written only when it is new');
    return checked(p, `${p.text}${wholeFile(op.value, p.eol)}`, op.value, types);
  }
  const parentPath = target.slice(0, -1);
  const last = target.at(-1)!;
  const parent = must(p, parentPath).node;
  if (!isMap(parent) && !isSeq(parent)) return refuse(`${placeOf(parentPath)} is no list or mapping`);
  const exists = locate(p, target) !== null;
  if (op.op === 'add') {
    if (isSeq(parent)) {
      if (typeof last !== 'number') return refuse(`${placeOf(parentPath)} is a list: a value is added at an index`);
      return checked(p, insertItem(p, parentPath, last, { value: op.value }), patched(p.value, op), types);
    }
    if (typeof last !== 'string') return refuse(`${placeOf(parentPath)} is a mapping: a value is added under a key`);
    if (exists) return apply(p, { op: 'replace', path: op.path, value: op.value }, types);
    return checked(p, addMember(p, parentPath, last, op.value), patched(p.value, op), types);
  }
  if (!exists) return refuse(`there is nothing at ${placeOf(target)}`);
  switch (op.op) {
    case 'replace': {
      const ops = changes(p, op.path, op.value);
      if (ops === null) return checked(p, rewrite(p, op.path, op.value), patched(p.value, op), types);
      let q = p;
      for (const sub of ops) q = apply(q, sub, types);
      if (!sameOrdered(q.value, patched(p.value, op))) refuse(`${placeOf(op.path)} would not read back as written`);
      return q;
    }
    case 'remove':
      return checked(p, isSeq(parent) ? removeItem(p, op.path) : removeMember(p, op.path), patched(p.value, op), types);
    case 'move': {
      if (samePath(op.from, op.path)) return p;
      if (startsWith(op.path, op.from)) return refuse('a step cannot be moved into itself');
      if (isMap(parent)) {
        if (!samePath(op.path.slice(0, -1), parentPath)) return refuse('a key is moved only to another key of its own mapping');
        return checked(p, rename(p, op.from, op.path), patched(p.value, op), types);
      }
      const index = op.path.at(-1);
      if (typeof index !== 'number') return refuse('an item is moved to an index of a list');
      guard(p, [op.from], [parentPath]);
      if (parent.items.length === 1) {
        const steps = stepLists(p.value, types).some((l) => samePath(l, parentPath));
        return refuse(`${placeOf(parentPath)} keeps at least one ${steps ? 'step' : 'item'}`);
      }
      const span = itemSpan(p, parent, last as number);
      const removed = read(splice(p.text, span.start, span.end, ''), p.eol, types);
      const moved = { moved: p.text.slice(span.start, span.end), movedDash: column(p.text, span.dash) };
      return checked(p, insertItem(removed, op.path.slice(0, -1), index, moved), patched(p.value, op), types);
    }
  }
}

/** `text` read, refused when it does not read as `expected`, or when it leaves a list of steps empty that was not before. */
function checked(p: Parsed, text: string, expected: unknown, types: Readonly<Record<string, StepType>>): Parsed {
  const next = read(text, p.eol, types);
  if (!sameOrdered(next.value, expected)) refuse('the edit would not read back as written');
  const before = new Set(stepLists(p.value, types).filter((l) => (at(p.value, l) as unknown[]).length === 0).map((l) => JSON.stringify(l)));
  for (const list of stepLists(next.value, types)) {
    if ((at(next.value, list) as unknown[]).length === 0 && !before.has(JSON.stringify(list))) refuse(`${placeOf(list)} keeps at least one step`);
  }
  return next;
}

/** The processes `value` calls, by the place of each call. */
function calls(value: unknown, types: Readonly<Record<string, StepType>>): { process: string; path: Path }[] {
  return stepLists(value, types).flatMap((list) =>
    (at(value, list) as unknown[]).flatMap((step, i) => (isRecord(step) && step.type === 'process' && typeof step.process === 'string' ? [{ process: step.process, path: [...list, i] }] : [])),
  );
}

/**
 * The edit function over `types`, the built-in step types (a test may give more). It applies `ops` in order, each to the
 * text the one before made, and answers the new text, or throws {@link EditRefused} and changes nothing. The text it
 * answers reads as the file's value with the ops applied, keys in order, and only the bytes of the spans the ops touch
 * differ: the comments, blank lines, quoting, line ends and indentation of the rest stay. In a flow it refuses an op that
 * leaves a list of steps empty, and removing a process that a step still calls.
 */
export function editor(types: Readonly<Record<string, StepType>> = BUILT_IN) {
  return (text: string, ...ops: Op[]): string => {
    if (ops.length > MAX_OPS) refuse(`an edit holds at most ${MAX_OPS} operations`);
    const eol = text.includes('\r\n') ? '\r\n' : '\n';
    const open = text !== '' && !text.endsWith('\n');
    const first = read(open ? `${text}${eol}` : text, eol, types);
    let p = first;
    for (const op of ops) p = apply(p, op, types);
    const processes = (v: unknown) => (isRecord(v) && isRecord(v.processes) ? Object.keys(v.processes) : []);
    const gone = processes(first.value).filter((name) => !processes(p.value).includes(name));
    for (const call of calls(p.value, types)) {
      if (gone.includes(call.process)) refuse(`process ${call.process} is still called by ${placeOf(call.path)}`);
    }
    return open && p.text.endsWith(eol) ? p.text.slice(0, -eol.length) : p.text;
  };
}

/** Every change to a flow file, as {@link editor} describes it, over the built-in step types. */
export const edit = editor();

/**
 * Where the value at `path` stands once `ops` are applied, or null when they remove it. A replace keeps a path inside it
 * while its new value still holds that path.
 */
export function follow(path: Path, ops: readonly Op[]): Path | null {
  let current: Path | null = path;
  const shiftOut = (p: Path, removed: Path): Path => {
    const i = removed.length - 1;
    const index = removed[i];
    return typeof index === 'number' && p.length > i && startsWith(p, removed.slice(0, -1)) && typeof p[i] === 'number' && (p[i] as number) > index
      ? [...p.slice(0, i), (p[i] as number) - 1, ...p.slice(i + 1)]
      : p;
  };
  const shiftIn = (p: Path, added: Path): Path => {
    const i = added.length - 1;
    const index = added[i];
    return typeof index === 'number' && p.length > i && startsWith(p, added.slice(0, -1)) && typeof p[i] === 'number' && (p[i] as number) >= index
      ? [...p.slice(0, i), (p[i] as number) + 1, ...p.slice(i + 1)]
      : p;
  };
  for (const op of ops) {
    if (current === null) return null;
    const p: Path = current;
    switch (op.op) {
      case 'add':
        if (typeof op.path.at(-1) === 'number') current = shiftIn(p, op.path);
        else if (startsWith(p, op.path) && p.length > op.path.length) current = at(op.value, p.slice(op.path.length)) === undefined ? null : p;
        break;
      case 'replace':
        if (startsWith(p, op.path) && p.length > op.path.length) current = at(op.value, p.slice(op.path.length)) === undefined ? null : p;
        break;
      case 'remove':
        current = startsWith(p, op.path) ? null : shiftOut(p, op.path);
        break;
      case 'move':
        current = startsWith(p, op.from) ? [...op.path, ...p.slice(op.from.length)] : shiftIn(shiftOut(p, op.from), op.path);
        break;
    }
  }
  return current;
}

/** The span of the list item at `path` in `text`, as an edit takes it: the comment lines directly above it and its lines. */
export function spanOf(text: string, path: Path, types: Readonly<Record<string, StepType>> = BUILT_IN): { start: number; end: number } {
  const p = read(text, text.includes('\r\n') ? '\r\n' : '\n', types);
  const { node } = must(p, path.slice(0, -1));
  if (!isSeq(node)) return refuse(`${placeOf(path.slice(0, -1))} is not a list`);
  const { start, end } = itemSpan(p, node, path.at(-1) as number);
  return { start, end };
}
