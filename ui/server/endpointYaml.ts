import { load } from 'js-yaml';
import { sameValue } from '../src/endpoints/same-value.ts';

/** An endpoint file as the settings form reads it: its envelope, its component's options, and what each environment overrides. */
export interface EndpointDocument {
  id: string;
  scheme: string;
  direction: 'in' | 'out';
  /** The file's own description of the endpoint, or null when it has none. */
  description: string | null;
  /** The component's options, the file's `config`, as YAML reads them. */
  config: Record<string, unknown>;
  /** Each environment that overrides options, in the file's order, with the names of the options it overrides. */
  environments: { name: string; options: string[] }[];
}

/** An endpoint file the form cannot read, or a save it refuses; the message says why, never quoting a value. */
export class EndpointFileError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'EndpointFileError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parse(text: string): unknown {
  try {
    return load(text);
  } catch (error) {
    throw new EndpointFileError(`the file is not YAML: ${error instanceof Error ? error.message.split('\n')[0] : String(error)}`);
  }
}

/**
 * Reads `text` as an endpoint file: null when it is a YAML document of another kind, the endpoint when its `kind` is
 * `endpoint`. Throws {@link EndpointFileError} when it is no YAML, or an endpoint file without a string `id` and
 * `scheme`, a `direction` of `in` or `out`, and a mapping as `config`.
 */
export function readEndpoint(text: string): EndpointDocument | null {
  const doc = parse(text);
  if (!isRecord(doc) || doc.kind !== 'endpoint') return null;
  const { id, scheme, direction, description, config, environments } = doc;
  if (typeof id !== 'string' || typeof scheme !== 'string') throw new EndpointFileError('an endpoint file names its id and scheme');
  if (direction !== 'in' && direction !== 'out') throw new EndpointFileError('an endpoint file has the direction in or out');
  if (!isRecord(config)) throw new EndpointFileError('the endpoint file has no mapping under config');
  const overrides: EndpointDocument['environments'] = [];
  if (isRecord(environments)) {
    for (const [name, env] of Object.entries(environments)) {
      if (isRecord(env) && isRecord(env.config)) overrides.push({ name, options: Object.keys(env.config) });
    }
  }
  return { id, scheme, direction, description: typeof description === 'string' ? description : null, config, environments: overrides };
}

/** Words that a YAML reader may take for something other than text: written quoted. */
const WORDS = /^(y|n|yes|no|on|off|true|false|null)$/i;
/** Text written plain: it starts with a letter, `_` or `/`, holds no space, quote, `#`, comma or bracket, and does not end with `:`. */
const PLAIN = /^[A-Za-z_/][A-Za-z0-9_./:@+=-]*$/;

/** `value` as YAML on one line: plain text where no reader can mistake it, else double-quoted; anything else as JSON. */
function written(value: unknown): string {
  if (typeof value === 'string') return PLAIN.test(value) && !value.endsWith(':') && !WORDS.test(value) ? value : JSON.stringify(value);
  if (typeof value === 'number' && !Number.isFinite(value)) throw new EndpointFileError('a number must be finite');
  return JSON.stringify(value);
}

const KEY = /^( *)([A-Za-z0-9_][A-Za-z0-9_.-]*|"(?:[^"\\]|\\.)*"|'(?:[^']|'')*'):(?: (.*))?$/;

function keyName(written: string): string {
  if (written.startsWith('"')) return JSON.parse(written) as string;
  if (written.startsWith("'")) return written.slice(1, -1).replaceAll("''", "'");
  return written;
}

/** The comment at the end of a one-line value, with the spaces before it, or '' when it has none. */
function trailingComment(value: string): string {
  let i = 0;
  const quote = value[0];
  if (quote === '"' || quote === "'") {
    for (i = 1; i < value.length; i++) {
      if (quote === '"' && value[i] === '\\') i++;
      else if (value[i] === quote) {
        if (quote === "'" && value[i + 1] === "'") i++;
        else break;
      }
    }
  }
  const at = value.slice(i).search(/\s#/);
  if (at < 0) return '';
  const start = i + at;
  let from = start;
  while (from > 0 && /\s/.test(value[from - 1]!)) from--;
  return value.slice(from);
}

interface Entry {
  key: string;
  /** The entry's first line, and the line after its value. */
  start: number;
  end: number;
  /** The entry's first line split: indentation and key as written, and the comment ending a one-line value. */
  head: string;
  comment: string;
}

const blankOrComment = (line: string) => /^\s*(#.*)?$/.test(line);
const indentOf = (line: string) => line.length - line.trimStart().length;

/**
 * `text`, an endpoint file, with its `config` set to `config`, changing only what differs: an option whose value
 * changed is rewritten on its own line, keeping the comment after a one-line value; a new option is added after the
 * last one; an option left out is removed with its lines. Every other line, comment and the order of the options stay
 * as they are. A value is written on one line, text plain or double-quoted and anything else as JSON.
 *
 * Throws {@link EndpointFileError}, and changes nothing, when `config` is not a top-level block mapping the form can
 * edit line by line, or when the result would not read back as the same file with `config` as given.
 */
export function writeConfig(text: string, config: Record<string, unknown>): string {
  const before = parse(text);
  if (!isRecord(before) || !isRecord(before.config)) throw new EndpointFileError('the endpoint file has no mapping under config');
  const old = before.config;
  const crlf = text.includes('\r\n');
  const lines = text.split('\n').map((l) => (l.endsWith('\r') ? l.slice(0, -1) : l));

  const at = lines.findIndex((l) => /^config:/.test(l));
  const head = at < 0 ? null : /^config:(?:\s*(\{\s*\}))?(\s+#.*)?\s*$/.exec(lines[at]!);
  if (head === null) throw new EndpointFileError('config is not written as a block the form can edit; edit the file in a text editor');
  let blockEnd = at + 1;
  while (blockEnd < lines.length && (blankOrComment(lines[blockEnd]!) || indentOf(lines[blockEnd]!) > 0)) blockEnd++;

  const entries: Entry[] = [];
  let indent = -1;
  for (let i = at + 1; i < blockEnd; i++) {
    const line = lines[i]!;
    if (blankOrComment(line)) continue;
    if (indent < 0) indent = indentOf(line);
    if (indentOf(line) > indent) continue;
    const match = indentOf(line) === indent ? KEY.exec(line) : null;
    if (match === null) throw new EndpointFileError(`line ${i + 1} of config is not an option the form can edit; edit the file in a text editor`);
    const value = match[3] ?? '';
    entries.push({ key: keyName(match[2]!), start: i, end: i + 1, head: `${match[1]}${match[2]}:`, comment: trailingComment(value) });
  }
  for (const [n, entry] of entries.entries()) {
    let end = n + 1 < entries.length ? entries[n + 1]!.start : blockEnd;
    while (end > entry.start + 1 && blankOrComment(lines[end - 1]!)) end--;
    entry.end = end;
  }
  const keys = entries.map((e) => e.key);
  if (head[1] !== undefined ? keys.length > 0 : !sameValue([...keys].sort(), Object.keys(old).sort())) {
    throw new EndpointFileError('config is not written as a block the form can edit; edit the file in a text editor');
  }

  const pad = ' '.repeat(indent < 0 ? 2 : indent);
  const option = (head: string, value: unknown, comment = '') => `${head} ${written(value)}${comment}`;
  const out = lines.slice(0, at + 1);
  const empty = Object.values(config).length === 0;
  if (head[1] !== undefined || empty) out[at] = `config:${empty ? ' {}' : ''}${head[2] ?? ''}`;
  if (head[1] !== undefined || entries.length === 0) {
    for (const [key, value] of Object.entries(config)) out.push(option(`${pad}${written(key)}:`, value));
  }
  for (let i = at + 1, n = 0; i < blockEnd; ) {
    const entry = entries[n];
    if (entry === undefined || i < entry.start) {
      out.push(lines[i++]!);
      continue;
    }
    if (Object.hasOwn(config, entry.key)) {
      const value = config[entry.key];
      if (sameValue(value, old[entry.key])) out.push(...lines.slice(entry.start, entry.end));
      else out.push(option(entry.head, value, entry.end === entry.start + 1 ? entry.comment : ''));
    }
    if (n === entries.length - 1) {
      for (const [key, value] of Object.entries(config)) if (!Object.hasOwn(old, key)) out.push(option(`${pad}${written(key)}:`, value));
    }
    i = entry.end;
    n++;
  }
  out.push(...lines.slice(blockEnd));
  const result = out.join(crlf ? '\r\n' : '\n');

  const after = parse(result);
  if (!sameValue(after, { ...before, config })) {
    throw new EndpointFileError('the file would not read back with the settings given; nothing was saved');
  }
  return result;
}
