import { createHash, randomUUID } from 'node:crypto';
import { readdir, readFile, realpath, rename, rm, stat, writeFile } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { basename, dirname, join, relative, sep } from 'node:path';
import { foreign } from './adminProxy.ts';
import { EndpointFileError, readEndpoint, writeConfig, type EndpointDocument } from './endpointYaml.ts';

/** The working folder whose endpoint files the settings form reads and writes, and who may reach them. */
export interface EndpointFilesOptions {
  /** The working folder; undefined when none is set, and the routes say so. */
  workspace?: string;
  /** Whether a save is allowed; when false every save is refused (403) and the form offers no Edit. */
  editable: boolean;
  /** The development server's own origins; a request with any other `Origin` is refused. */
  origins: readonly string[];
}

/** One endpoint file of the working folder: its path relative to the folder, with `/`, and its envelope or why it cannot be read. */
export type EndpointItem = { path: string; id: string; scheme: string; direction: 'in' | 'out' } | { path: string; problem: string };

/** What the routes answer for one endpoint file. */
export interface EndpointFile extends EndpointDocument {
  path: string;
  editable: boolean;
}

/** At most this many YAML files are looked at, this deep, and none longer than this. */
export const MAX_FILES = 1000;
const MAX_DEPTH = 16;
export const MAX_FILE_BYTES = 1024 * 1024;
const SKIPPED = new Set(['node_modules', 'target']);
const PREFIX = '/files/endpoints';

class Refusal extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const isYaml = (name: string) => name.endsWith('.yaml') && !name.endsWith('.test.yaml');

/**
 * The endpoint files under `root`: every `*.yaml` but a `*.test.yaml` whose `kind` is `endpoint`, sorted by path, and
 * whether the walk stopped at {@link MAX_FILES}. Hidden folders, `node_modules`, `target`, symbolic links and files over
 * {@link MAX_FILE_BYTES} are passed over, and so is a file that is no YAML unless it says `kind: endpoint`.
 */
export async function listEndpoints(root: string): Promise<{ items: EndpointItem[]; truncated: boolean }> {
  const items: EndpointItem[] = [];
  let seen = 0;
  let truncated = false;
  const walk = async (dir: string, depth: number) => {
    const entries = await readdir(dir, { withFileTypes: true });
    entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    for (const entry of entries) {
      if (truncated || entry.name.startsWith('.')) continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!SKIPPED.has(entry.name) && depth < MAX_DEPTH) await walk(full, depth + 1);
      } else if (entry.isFile() && isYaml(entry.name)) {
        if (++seen > MAX_FILES) {
          truncated = true;
          continue;
        }
        if ((await stat(full)).size > MAX_FILE_BYTES) continue;
        const path = relative(root, full).split(sep).join('/');
        const text = await readFile(full, 'utf8');
        try {
          const doc = readEndpoint(text);
          if (doc !== null) items.push({ path, id: doc.id, scheme: doc.scheme, direction: doc.direction });
        } catch (error) {
          if (error instanceof EndpointFileError && /^kind:\s*endpoint\s*$/m.test(text)) items.push({ path, problem: error.message });
        }
      }
    }
  };
  await walk(root, 0);
  items.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return { items, truncated };
}

/** The ETag of a file's bytes. */
export function etagOf(bytes: Buffer | string): string {
  return `"sha256-${createHash('sha256').update(bytes).digest('hex')}"`;
}

/** The real path of the endpoint file at `path` (relative, with `/`) under the real folder `root`, refused when it leaves it. */
async function locate(root: string, path: string): Promise<string> {
  const segments = path.split('/').map((s) => {
    try {
      return decodeURIComponent(s);
    } catch {
      throw new Refusal(400, 'the path is not well encoded');
    }
  });
  if (segments.some((s) => s === '' || s === '.' || s === '..' || /[/\\\0]/.test(s)) || !isYaml(segments.at(-1)!)) {
    throw new Refusal(400, 'the path names no endpoint file of the working folder');
  }
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

function endpointOf(text: string, path: string, editable: boolean): EndpointFile {
  try {
    const doc = readEndpoint(text);
    if (doc === null) throw new Refusal(404, `${path} is not an endpoint file`);
    return { path, editable, ...doc };
  } catch (error) {
    if (error instanceof EndpointFileError) throw new Refusal(422, error.message);
    throw error;
  }
}

async function body(req: IncomingMessage): Promise<unknown> {
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

function send(res: ServerResponse, status: number, value: unknown, etag?: string) {
  const headers: Record<string, string> = { 'content-type': 'application/json', 'cache-control': 'no-store' };
  if (etag !== undefined) headers.etag = etag;
  res.writeHead(status, headers).end(JSON.stringify(value));
}

/**
 * A development-server middleware over the endpoint files of the working folder, for the UI's own pages only (403 as
 * the `/admin` proxy refuses):
 *
 * - `GET /files/endpoints`: 200 `{configured, editable, items, truncated}`, the files as {@link listEndpoints} finds them;
 *   `configured` is false, with no items, when no working folder is set.
 * - `GET /files/endpoints/<path>`: 200 the {@link EndpointFile} with its `ETag`; 404 when there is no such endpoint file,
 *   422 when it cannot be read.
 * - `PUT /files/endpoints/<path>` with `If-Match` and `{"config": {...}}`: writes the file's `config` as
 *   {@link writeConfig} does, to a temporary file renamed over it, and answers 200 as GET does; 403 when not editable,
 *   428 without `If-Match`, 412 when the file changed since that tag, 422 when the edit is refused.
 *
 * Saves are made one at a time. Every other path goes to `next`.
 */
export function endpointFiles(options: EndpointFilesOptions) {
  let saves: Promise<unknown> = Promise.resolve();
  const rootOf = async () => {
    if (options.workspace === undefined) throw new Refusal(404, 'no working folder is set: start the UI with RAIL8_WORKSPACE');
    return realpath(options.workspace);
  };

  const list = async () => {
    if (options.workspace === undefined) return { configured: false, editable: false, items: [], truncated: false };
    return { configured: true, editable: options.editable, ...(await listEndpoints(await rootOf())) };
  };

  const read = async (path: string) => {
    const file = await locate(await rootOf(), path);
    const bytes = await readFile(file);
    return { file: endpointOf(bytes.toString('utf8'), path, options.editable), etag: etagOf(bytes) };
  };

  const save = async (req: IncomingMessage, path: string) => {
    if (!options.editable) throw new Refusal(403, 'this server shows endpoint files read-only');
    const ifMatch = req.headers['if-match'];
    if (ifMatch === undefined) throw new Refusal(428, 'a save names the version it changes in If-Match');
    const sent = await body(req);
    if (typeof sent !== 'object' || sent === null || !('config' in sent)) throw new Refusal(400, 'a save sends {"config": {...}}');
    const config = sent.config;
    if (typeof config !== 'object' || config === null || Array.isArray(config)) throw new Refusal(400, 'config is a JSON object');
    const file = await locate(await rootOf(), path);
    const bytes = await readFile(file);
    if (etagOf(bytes) !== ifMatch) throw new Refusal(412, 'the file changed since it was read; nothing was saved');
    const text = bytes.toString('utf8');
    endpointOf(text, path, options.editable);
    let written: string;
    try {
      written = writeConfig(text, config as Record<string, unknown>);
    } catch (error) {
      if (error instanceof EndpointFileError) throw new Refusal(422, error.message);
      throw error;
    }
    const temporary = join(dirname(file), `.${basename(file)}.${randomUUID()}.tmp`);
    try {
      await writeFile(temporary, written, { flag: 'wx', mode: (await stat(file)).mode & 0o777 });
      await rename(temporary, file);
    } catch (error) {
      await rm(temporary, { force: true });
      throw error;
    }
    return { file: endpointOf(written, path, options.editable), etag: etagOf(written) };
  };

  return (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const url = new URL(req.url ?? '', 'http://localhost');
    if (url.pathname !== PREFIX && !url.pathname.startsWith(`${PREFIX}/`)) {
      next();
      return;
    }
    const refused = foreign(req.headers, options.origins, 'the working folder');
    if (refused !== undefined) {
      send(res, 403, { error: refused });
      return;
    }
    const path = url.pathname.slice(PREFIX.length + 1);
    const answer = async () => {
      if (path === '' && req.method === 'GET') send(res, 200, await list());
      else if (path !== '' && req.method === 'GET') {
        const { file, etag } = await read(path);
        send(res, 200, file, etag);
      } else if (path !== '' && req.method === 'PUT') {
        const done = saves.then(() => save(req, path));
        saves = done.catch(() => undefined);
        const { file, etag } = await done;
        send(res, 200, file, etag);
      } else send(res, 405, { error: 'the working folder takes GET, and PUT of one file' });
    };
    answer().catch((error: unknown) => {
      if (res.headersSent) res.destroy();
      else if (error instanceof Refusal) send(res, error.status, { error: error.message });
      else send(res, 500, { error: `the working folder could not be read or written: ${String(error)}` });
    });
  };
}
