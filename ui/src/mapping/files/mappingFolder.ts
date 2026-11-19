import { lstat, realpath, stat } from 'node:fs/promises';
import { dirname, join, sep } from 'node:path';
import { walk } from '../../../server/workingFolder.ts';

/**
 * A refused request of the mapping studio's API: an RFC 9457 problem with the HTTP status it is answered with, its
 * type, title and detail, and any members of its own.
 */
export class Problem extends Error {
  readonly status: number;
  readonly type: string;
  readonly title: string;
  readonly members: Record<string, unknown>;

  constructor(status: number, type: string, title: string, detail: string, members: Record<string, unknown> = {}) {
    super(detail);
    this.status = status;
    this.type = type;
    this.title = title;
    this.members = members;
  }

  /** The problem as JSON. */
  json(): Record<string, unknown> {
    return { type: this.type, title: this.title, status: this.status, detail: this.message, ...this.members };
  }
}

/** The type of a problem of the mapping studio, `urn:rail8:mapping:problem:<slug>`. */
export const mappingProblem = (slug: string) => `urn:rail8:mapping:problem:${slug}`;

/** 400 `invalid-workspace-path`: `path`, as the request spelled it, is no path of the working folder. */
export const pathRefused = (path: string) =>
  new Problem(400, mappingProblem('invalid-workspace-path'), 'Not a workspace path', `not a workspace path: "${path}"`);

/** 404 `not-found`. */
export const notFound = (detail = 'no such mapping or directory in the workspace') => new Problem(404, mappingProblem('not-found'), 'Not found', detail);

const SEGMENT = '[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}';

/** A mapping id, at most 16 segments; its last segment must not be reserved as well. */
const ID = new RegExp(`^${SEGMENT}(/${SEGMENT}){0,15}$`);

/** A bundle reference: a relative path that may climb at most 16 folders. */
const BUNDLE = /^(\.\.\/){0,16}[A-Za-z0-9_-][A-Za-z0-9._-]*(\/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$/;

/** A package reference, `name@version:/path`, which the engine refuses itself. */
const PACKAGE = /^[A-Za-z0-9_-][A-Za-z0-9._-]*@[A-Za-z0-9][A-Za-z0-9.+-]*:\/[A-Za-z0-9_-][A-Za-z0-9._-]*(\/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$/;

/** The last segments of the action paths, so that no id is one. */
const RESERVED = new Set(['test', 'compile', 'refresh', 'udfs']);

/** The end of a mapping file's name; the id is the file's path without it. */
export const SUFFIX = '.mapping.json';

/** Whether `id` is a mapping id. */
export function isId(id: string): boolean {
  return ID.test(id) && !RESERVED.has(id.slice(id.lastIndexOf('/') + 1));
}

/** Whether `path` is `folder` or lies under it. */
const inside = (path: string, folder: string) => path === folder || path.startsWith(folder + sep);

/**
 * The real path of the mapping file of `id` under the real working folder `root`. Refused with 400 when `id` is no
 * mapping id or the file's real path leads out of `root`, and 404 when there is no such file.
 */
export async function mappingFile(root: string, id: string): Promise<string> {
  if (!isId(id)) throw pathRefused(id);
  let real: string;
  try {
    real = await realpath(join(root, id + SUFFIX));
  } catch {
    throw notFound();
  }
  if (!inside(real, root)) throw pathRefused(id);
  if (!(await stat(real)).isFile()) throw notFound();
  return real;
}

/**
 * The real path of the folder that holds, or would hold, the mapping file of `id`: the folder its bundle references are
 * relative to. Refused as {@link mappingFile} is, with 404 when the folder does not exist.
 */
export async function mappingFolder(root: string, id: string): Promise<string> {
  if (!isId(id)) throw pathRefused(id);
  return realFolder(root, dirname(join(root, id + SUFFIX)), id);
}

/** The real path of the folder `base` names, `''` being the working folder itself; refused as {@link mappingFolder} is. */
export async function baseFolder(root: string, base: string): Promise<string> {
  if (base !== '' && !ID.test(base)) throw pathRefused(base);
  return realFolder(root, join(root, base), base);
}

async function realFolder(root: string, path: string, spelled: string): Promise<string> {
  let real: string;
  try {
    real = await realpath(path);
  } catch {
    throw notFound();
  }
  if (!inside(real, root)) throw pathRefused(spelled);
  if (!(await stat(real)).isDirectory()) throw notFound();
  return real;
}

/** A real folder that bundle references are relative to, and the real folder their files must stay in. */
export interface BundleFolder {
  folder: string;
  bound: string;
}

/**
 * The real folder `folder` of the real working folder `root`, bound by its package: the nearest folder from `folder` up
 * that holds `package.yaml`, or `root` when none does.
 */
export async function bundleFolder(root: string, folder: string): Promise<BundleFolder> {
  for (let at = folder; inside(at, root); at = dirname(at)) {
    if ((await stat(join(at, 'package.yaml')).catch(() => undefined))?.isFile()) return { folder, bound: at };
    if (at === root) break;
  }
  return { folder, bound: root };
}

/**
 * What a bundle reference names: a file to send, with its real path and size; nothing to send, for a package reference
 * and a file that is missing or no file, which the engine reports itself; or a path that escapes, which the engine
 * refuses when it reaches it.
 */
export type BundleFile = { kind: 'file'; path: string; size: number } | { kind: 'unsent' } | { kind: 'escaping' };

/**
 * Decides `reference` from `at` with three checks: its pattern, on its text; its normalised path, under `at.bound`; and
 * the real path of its file, or of the file's nearest existing folder when it is missing, under `at.bound` as well. A
 * link whose real path does not resolve, dangling or a loop, escapes, as its target might appear outside later.
 */
export async function bundleFile(at: BundleFolder, reference: string): Promise<BundleFile> {
  if (PACKAGE.test(reference)) return { kind: 'unsent' };
  if (!BUNDLE.test(reference)) return { kind: 'escaping' };
  const path = join(at.folder, reference);
  if (!inside(path, at.bound)) return { kind: 'escaping' };
  let existing = path;
  while (!(await lstat(existing).then(() => true, () => false))) existing = dirname(existing);
  let real: string;
  try {
    real = await realpath(existing);
  } catch {
    return { kind: 'escaping' };
  }
  if (!inside(real, at.bound)) return { kind: 'escaping' };
  if (existing !== path) return { kind: 'unsent' };
  const info = await stat(real);
  return info.isFile() ? { kind: 'file', path: real, size: info.size } : { kind: 'unsent' };
}

/** One mapping of the list: its id, name and package, and what its file names when it has the shape. */
export interface MappingSummary {
  id: string;
  name: string;
  package: string;
  description?: string;
  source?: string;
  target?: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * The name a side of a mapping gives its root: an element's local name, a WSDL root's operation, or the last segment
 * of a JSON root's first bundle file; undefined when the side is not a structure reference.
 */
function rootName(side: unknown): string | undefined {
  if (!isRecord(side) || !isRecord(side.root) || !Array.isArray(side.bundle) || side.bundle.length === 0) return undefined;
  if (!side.bundle.every((file) => typeof file === 'string')) return undefined;
  const { root } = side;
  if (typeof root.element === 'string') return root.element.slice(root.element.indexOf('}') + 1);
  if (isRecord(root.wsdl) && typeof root.wsdl.operation === 'string') return root.wsdl.operation;
  if (root.json === '$') {
    const first = side.bundle[0] as string;
    return first.slice(first.lastIndexOf('/') + 1);
  }
  return undefined;
}

/**
 * The summary of the mapping `id` whose file holds `text`, or undefined when the file was not read. Its package is the
 * id's folder, `''` at the top. A file that is a JSON object gives its description and the root name of each side,
 * each only when it has its type.
 */
function summaryOf(id: string, text: string | undefined): MappingSummary {
  const slash = id.lastIndexOf('/');
  const summary: MappingSummary = { id, name: id.slice(slash + 1), package: id.slice(0, Math.max(slash, 0)) };
  let model: unknown;
  try {
    model = text === undefined ? undefined : JSON.parse(text);
  } catch {
    return summary;
  }
  if (!isRecord(model)) return summary;
  if (typeof model.description === 'string') summary.description = model.description;
  const source = rootName(model.source);
  if (source !== undefined) summary.source = source;
  const target = rootName(model.target);
  if (target !== undefined) summary.target = target;
  return summary;
}

/**
 * The mappings of the real working folder `root`, sorted by id, as the walk of the working folder finds them: each
 * `*.mapping.json` whose path is a mapping id. A file over 1 MiB is not read and is listed by its id, name and package
 * alone. `truncated` is true when the walk stopped at its limit, so that more mappings may exist.
 */
export async function listMappings(root: string): Promise<{ mappings: MappingSummary[]; truncated: boolean }> {
  const { files, large, truncated } = await walk(root, (name) => name.endsWith(SUFFIX));
  const found: { id: string; text?: string }[] = [
    ...files.map((file) => ({ id: file.path.slice(0, -SUFFIX.length), text: file.text })),
    ...large.map((path) => ({ id: path.slice(0, -SUFFIX.length) })),
  ];
  const mappings = found
    .filter(({ id }) => isId(id))
    .map(({ id, text }) => summaryOf(id, text))
    .sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return { mappings, truncated };
}
