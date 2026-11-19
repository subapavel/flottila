import { createHash, randomUUID } from 'node:crypto';
import { link, lstat, readdir, readFile, realpath, rename, rm, stat, writeFile } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { basename, dirname, join, relative, sep } from 'node:path';

/** At most this many YAML files are looked at, this deep, and none longer than this. */
export const MAX_FILES = 1000;
const MAX_DEPTH = 16;
export const MAX_FILE_BYTES = 1024 * 1024;
const SKIPPED = new Set(['node_modules', 'target']);

/** A request the working folder's routes refuse: the HTTP status and the error text the answer carries. */
export class Refusal extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** One file the walk found: its path relative to the folder, with `/`, and its text. */
export interface FoundFile {
  path: string;
  text: string;
}

/**
 * The files under `root` whose name `takes`, in path order, read as text; the paths of those over
 * {@link MAX_FILE_BYTES}, which are not read, in path order too; and whether the walk stopped at {@link MAX_FILES} of
 * them. Hidden folders and files, `node_modules`, `target`, symbolic links and folders more than 16 deep are passed
 * over.
 */
export async function walk(root: string, takes: (name: string) => boolean): Promise<{ files: FoundFile[]; large: string[]; truncated: boolean }> {
  const files: FoundFile[] = [];
  const large: string[] = [];
  let seen = 0;
  let truncated = false;
  const visit = async (dir: string, depth: number) => {
    const entries = await readdir(dir, { withFileTypes: true });
    entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    for (const entry of entries) {
      if (truncated || entry.name.startsWith('.')) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!SKIPPED.has(entry.name) && depth < MAX_DEPTH) await visit(full, depth + 1);
      } else if (entry.isFile() && takes(entry.name)) {
        if (++seen > MAX_FILES) {
          truncated = true;
          continue;
        }
        const path = relative(root, full).split(sep).join('/');
        if ((await stat(full)).size > MAX_FILE_BYTES) large.push(path);
        else files.push({ path, text: await readFile(full, 'utf8') });
      }
    }
  };
  await visit(root, 0);
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  large.sort();
  return { files, large, truncated };
}

/** The ETag of a file's bytes. */
export function etagOf(bytes: Buffer | string): string {
  return `"sha256-${createHash('sha256').update(bytes).digest('hex')}"`;
}

/**
 * The real path of the file at `path` (relative, with `/`, each segment percent-encoded) under the real folder `root`
 * whose name `takes`. Refused with 400 when the path is malformed or names no such file, 404 when there is none, 403
 * when it leads out of the folder, and 413 when it is over {@link MAX_FILE_BYTES}.
 */
export async function locate(root: string, path: string, takes: (name: string) => boolean, what: string): Promise<string> {
  const segments = segmentsOf(path, takes, what);
  let real: string;
  try {
    real = await realpath(join(root, ...segments));
  } catch {
    throw new Refusal(404, `no file ${segments.join('/')} in the working folder`);
  }
  if (!real.startsWith(root + sep)) throw new Refusal(403, 'the path leaves the working folder');
  const info = await stat(real);
  if (!info.isFile()) throw new Refusal(404, `no file ${segments.join('/')} in the working folder`);
  if (info.size > MAX_FILE_BYTES) throw new Refusal(413, 'the file is over 1 MiB');
  return real;
}

/**
 * Creates the file at `path` (relative, with `/`, each segment percent-encoded) under the real folder `root`, whose name
 * `takes`, holding `text`, and answers its real path. It is written whole to a temporary file beside it and linked to its
 * name, so it never replaces a file. Refused with 400 when the path is malformed, names no such file or a place the walk
 * passes over (a hidden or skipped folder or file, or one too deep), 404 when its folder does not exist, 403 when the
 * folder leads out of the working folder, and 412 when a file, or a link, of that name exists.
 */
export async function createFile(root: string, path: string, takes: (name: string) => boolean, what: string, text: string): Promise<string> {
  const segments = segmentsOf(path, takes, what);
  const folders = segments.slice(0, -1);
  if (folders.length > MAX_DEPTH || segments.some((s) => s.startsWith('.') || SKIPPED.has(s))) throw new Refusal(400, `the path names no ${what} of the working folder`);
  let folder: string;
  try {
    folder = await realpath(join(root, ...folders));
  } catch {
    throw new Refusal(404, `no folder ${folders.join('/')} in the working folder`);
  }
  if (folder !== root && !folder.startsWith(root + sep)) throw new Refusal(403, 'the path leaves the working folder');
  if (!(await stat(folder)).isDirectory()) throw new Refusal(404, `no folder ${folders.join('/')} in the working folder`);
  const file = join(folder, segments.at(-1)!);
  const exists = new Refusal(412, 'the file exists already; nothing was written');
  if (await lstat(file).then(() => true, () => false)) throw exists;
  const temporary = join(folder, `.${segments.at(-1)!}.${randomUUID()}.tmp`);
  try {
    await writeFile(temporary, text, { flag: 'wx' });
    await link(temporary, file).catch((error: unknown) => {
      throw (error as NodeJS.ErrnoException).code === 'EEXIST' ? exists : error;
    });
  } finally {
    await rm(temporary, { force: true });
  }
  return file;
}

/** The decoded segments of `path`, refused with 400 when it is malformed or its last segment is no name `takes`. */
function segmentsOf(path: string, takes: (name: string) => boolean, what: string): string[] {
  const segments = path.split('/').map((s) => {
    try {
      return decodeURIComponent(s);
    } catch {
      throw new Refusal(400, 'the path is not well encoded');
    }
  });
  if (segments.some((s) => s === '' || s === '.' || s === '..' || /[/\\\0]/.test(s)) || !takes(segments.at(-1)!)) {
    throw new Refusal(400, `the path names no ${what} of the working folder`);
  }
  return segments;
}

/**
 * Writes `text` over `file` through a temporary file beside it, renamed over it, keeping the file's mode. `before`, when
 * given, runs just before the rename, and a failure of it leaves the file as it was.
 */
export async function replaceFile(file: string, text: string, before?: () => Promise<void>): Promise<void> {
  const temporary = join(dirname(file), `.${basename(file)}.${randomUUID()}.tmp`);
  try {
    await writeFile(temporary, text, { flag: 'wx', mode: (await stat(file)).mode & 0o777 });
    await before?.();
    await rename(temporary, file);
  } catch (error) {
    await rm(temporary, { force: true });
    throw error;
  }
}

/** The request's body as JSON, refused with 413 over {@link MAX_FILE_BYTES} and 400 when it is no JSON. */
export async function jsonBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req as AsyncIterable<Buffer>) {
    size += chunk.length;
    if (size > MAX_FILE_BYTES) throw new Refusal(413, 'the request is over 1 MiB');
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new Refusal(400, 'the request is not JSON');
  }
}

/** Answers `value` as JSON with `status`, never cached, with its `etag` when one is given. */
export function send(res: ServerResponse, status: number, value: unknown, etag?: string) {
  const headers: Record<string, string> = { 'content-type': 'application/json', 'cache-control': 'no-store' };
  if (etag !== undefined) headers.etag = etag;
  res.writeHead(status, headers).end(JSON.stringify(value));
}

/** Answers what `answer` failed with: a {@link Refusal} with its status, anything else as 500; a started answer is cut. */
export function sendFailure(res: ServerResponse, error: unknown) {
  if (res.headersSent) res.destroy();
  else if (error instanceof Refusal) send(res, error.status, { error: error.message });
  else send(res, 500, { error: `the working folder could not be read or written: ${String(error)}` });
}
