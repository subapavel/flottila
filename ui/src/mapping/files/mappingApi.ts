import { createHash } from 'node:crypto';
import { readFile, realpath, stat } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { basename, join } from 'node:path';
import { foreign } from '../../../server/adminProxy.ts';
import { replaceFile, writeNew } from '../../../server/workingFolder.ts';
import {
  baseFolder,
  bundleFile,
  bundleFolder,
  listMappings,
  mappingFile,
  mappingFolder,
  mappingProblem,
  notFound,
  pathRefused,
  Problem,
  SUFFIX,
  type BundleFolder,
} from './mappingFolder.ts';

/** The working folder whose mappings the studio edits, who may reach them, and the node that does the engine's work. */
export interface MappingApiOptions {
  /** The working folder; undefined when none is set, and every route but the catalogue says so. */
  workspace?: string;
  /** Whether mappings may be saved and created. */
  editable: boolean;
  /** The development server's own origins; a POST or PUT with any other `Origin` is refused. */
  origins: readonly string[];
  /** The node's admin API, such as `http://127.0.0.1:8081`, and the bearer token it needs, if any. */
  node: { target: string; token?: string };
  /** Makes the signal that ends one call to the node; by default after {@link CALL_SECONDS}. */
  deadline?: () => AbortSignal;
}

/** How long one call to the node may take. */
export const CALL_SECONDS = 30;

/** The most bytes a request and the bundle files it reads may have in all, as they are on disk. */
export const MAX_RAW_BYTES = 12 * 1024 * 1024;

/** The most bytes the node takes in one call. */
const MAX_CALL_BYTES = 16 * 1024 * 1024;

const FILES_NEEDED = mappingProblem('files-needed');
const BUSY = 'urn:rail8:admin:problem:busy';
const ACTIONS = new Set(['compile', 'refresh', 'test']);

/** An answer of the node, or of this server: its status and its JSON, as bytes. */
interface Answer {
  status: number;
  body: Buffer;
  headers?: Record<string, string>;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** `"sha256:<hex>"` of the bytes, quoted: a strong tag. */
const etagOf = (bytes: Buffer) => `"sha256:${createHash('sha256').update(bytes).digest('hex')}"`;

const tooLarge = (detail: string) => new Problem(413, mappingProblem('too-large'), 'Request too large', detail);

/** Whether a `Content-Type` names `application/json`, in any case, with or without parameters. */
const isJson = (contentType: string | undefined) => contentType?.split(';', 1)[0]?.trim().toLowerCase() === 'application/json';

/** The body of `req`, refused with 413 over {@link MAX_RAW_BYTES}; an overlong body is read to its end, not kept. */
async function bodyOf(req: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req as AsyncIterable<Buffer>) {
    size += chunk.length;
    if (size <= MAX_RAW_BYTES) chunks.push(chunk);
  }
  if (size > MAX_RAW_BYTES) throw tooLarge('the request is over 12 MiB');
  return Buffer.concat(chunks);
}

/** The bytes of a stored mapping file, refused with 413 over {@link MAX_RAW_BYTES}. */
async function storedBytes(file: string): Promise<Buffer> {
  if ((await stat(file)).size > MAX_RAW_BYTES) throw tooLarge('the mapping file is over 12 MiB');
  return readFile(file);
}

/** The `member` of a JSON request as text, or the empty string when it is none: the node has checked the request first. */
function memberOf(body: Buffer, member: string): string {
  try {
    const value: unknown = JSON.parse(body.toString('utf8'));
    return isRecord(value) && typeof value[member] === 'string' ? value[member] : '';
  } catch {
    return '';
  }
}

/** What a request asks for: its route, and the id it names. */
type Route = { op: 'list' } | { op: 'create' } | { op: 'structure' } | { op: 'catalogue' } | { op: 'get' | 'save' | 'compile' | 'refresh' | 'test'; id: string };

/** The route of `method` and `path` under `/api`; 404 or 405 when there is none, 400 when the id is not well encoded. */
function routeOf(method: string, path: string): Route {
  const allowed = (methods: Record<string, Route>) => {
    const route = methods[method];
    if (route === undefined) throw new Problem(405, 'about:blank', 'Method Not Allowed', `${path} takes ${Object.keys(methods).join(' and ')}`);
    return route;
  };
  if (path === '/api/mappings') return allowed({ GET: { op: 'list' }, POST: { op: 'create' } });
  if (path === '/api/structures') return allowed({ POST: { op: 'structure' } });
  if (path === '/api/catalogue') return allowed({ GET: { op: 'catalogue' } });
  if (!path.startsWith('/api/mappings/')) throw new Problem(404, 'about:blank', 'Not Found', `no route ${path} in the mapping studio's API`);
  const raw = path.slice('/api/mappings/'.length).split('/');
  const action = raw.at(-1) ?? '';
  const decoded = (segments: string[]) => {
    try {
      return decodeURIComponent(segments.join('/'));
    } catch {
      throw pathRefused(segments.join('/'));
    }
  };
  if (method === 'POST' && raw.length > 1 && ACTIONS.has(action)) return { op: action as 'compile', id: decoded(raw.slice(0, -1)) };
  const id = decoded(raw);
  return allowed({ GET: { op: 'get', id }, PUT: { op: 'save', id } });
}

/**
 * A development-server middleware that answers the mapping studio's API, `/api`, as `mapping/contract/openapi.json`
 * describes it, over the mapping files of the working folder. The list reads the folder alone; everything else asks the
 * node's mapping module, whose answers it passes on, and the browser never calls the node itself.
 *
 * - Opening a mapping answers the node's canonical form of the stored file, with `ETag: "sha256:<hex>"` of exactly
 *   those bytes. A save writes the node's canonical form of the body over the file, through a temporary file renamed
 *   over it; with `If-Match`, only when the tag is that of the stored file, compared before the body is parsed, so a
 *   stored file the node refuses matches no tag. A create writes the new file and never one over a file. Saves and
 *   creates are made one at a time.
 * - When the node needs a call's bundle files, they are read in the order it names them, relative to the mapping's
 *   folder (or the structure's base), and sent once in a second call. A file must stay inside the package that holds the
 *   folder, the nearest folder up with `package.yaml`, or inside the working folder when there is none; the first that
 *   does not is sent as escaping, and no file after it is read.
 * - Refused: 403 `foreign-origin` for a POST or PUT from a page that is not this server's, and `read-only` for a save or
 *   a create when the folder is shown read-only; 415 for a body that is not JSON; 413 `too-large` when the body, or it
 *   and the bundle files, are over 12 MiB, before the call that would carry them. A node that does not answer within
 *   {@link CALL_SECONDS}, is down, refuses the token, has no such route or answers what it must not is 502
 *   `node-unavailable`; its 503 `busy` is passed on as it is. A call to the node ends when the browser's request closes.
 *
 * Every path outside `/api` goes to `next`.
 */
export function mappingApi(options: MappingApiOptions) {
  const { node } = options;
  let writes: Promise<unknown> = Promise.resolve();
  const locked = <T>(write: () => Promise<T>): Promise<T> => {
    const done = writes.then(write);
    writes = done.catch(() => undefined);
    return done;
  };

  const unavailable = (detail: string) => new Problem(502, mappingProblem('node-unavailable'), 'Node unavailable', detail);

  /** The reply of the node to a call of `op`, a POST with `call` or a GET without; 502 unless it is one to pass on. */
  const ask = async (op: string, call: Record<string, unknown> | undefined, signal: AbortSignal): Promise<Answer & { json: unknown }> => {
    const headers: Record<string, string> = {};
    if (node.token) headers.authorization = `Bearer ${node.token}`;
    let body: string | undefined;
    if (call !== undefined) {
      body = JSON.stringify(call);
      if (Buffer.byteLength(body) > MAX_CALL_BYTES) throw tooLarge('the request and the bundle files it reads are over what the node takes in one call');
      headers['content-type'] = 'application/json';
    }
    const deadline = options.deadline?.() ?? AbortSignal.timeout(CALL_SECONDS * 1000);
    let status: number;
    let type: string;
    let bytes: Buffer;
    try {
      const reply = await fetch(`${node.target}/admin/modules/mapping/${op}`, {
        method: call === undefined ? 'GET' : 'POST',
        headers,
        body,
        signal: AbortSignal.any([signal, deadline]),
      });
      status = reply.status;
      type = (reply.headers.get('content-type') ?? '').split(';', 1)[0]?.trim().toLowerCase() ?? '';
      bytes = Buffer.from(await reply.arrayBuffer());
    } catch (error) {
      if (signal.aborted) throw error;
      if (deadline.aborted) throw unavailable(`the node at ${node.target} did not answer within ${CALL_SECONDS} s`);
      throw unavailable(`the node at ${node.target} does not answer (is it running?): ${String(error)}`);
    }
    let json: unknown;
    try {
      json = JSON.parse(bytes.toString('utf8'));
    } catch {
      json = undefined;
    }
    if ((status === 200 || status === 201) && type === 'application/json' && json !== undefined) return { status, body: bytes, json };
    if (type === 'application/problem+json' && isRecord(json) && json.status === status && typeof json.type === 'string') {
      if (status >= 400 && status < 500 && json.type.startsWith(mappingProblem(''))) return { status, body: bytes, json };
      if (status === 503 && json.type === BUSY) return { status, body: bytes, json };
    }
    const said = isRecord(json) && typeof json.type === 'string' ? ` ${json.type}` : isRecord(json) && typeof json.error === 'string' ? `: ${json.error}` : '';
    throw unavailable(`the node at ${node.target} answered ${status}${said}, which is not an answer of its mapping module`);
  };

  /**
   * The node's answer to `op` for `request`, with the bundle files it needs: a first call sends none, and when the node
   * answers `files-needed`, `place` gives the folder they are relative to and they are sent in a second call. `place`
   * is also asked when the first answer is no refusal, so that it can refuse the request. `reads` is canonical's.
   */
  const operate = async (op: string, request: Buffer, place: () => Promise<BundleFolder>, signal: AbortSignal, reads?: 'stored' | 'sent') => {
    const call = { request: request.toString('base64'), ...(reads === undefined ? {} : { reads }) };
    const first = await ask(op, call, signal);
    const needed = isRecord(first.json) && first.json.type === FILES_NEEDED;
    if (first.status >= 400 && !needed) return first;
    const at = await place();
    if (!needed) return first;
    const references = isRecord(first.json) ? first.json.references : undefined;
    if (!Array.isArray(references) || !references.every((r) => typeof r === 'string')) throw unavailable('the node asked for files without naming them');
    const files: Record<string, string> = {};
    const escaping: string[] = [];
    let total = request.length;
    for (const reference of references) {
      const found = await bundleFile(at, reference);
      if (found.kind === 'escaping') {
        escaping.push(reference);
        break;
      }
      if (found.kind === 'unsent') continue;
      total += found.size;
      if (total > MAX_RAW_BYTES) throw tooLarge(`the request and the bundle files it reads are over 12 MiB, at ${reference}`);
      files[reference] = (await readFile(found.path)).toString('base64');
    }
    const second = await ask(op, { ...call, files, escaping }, signal);
    if (isRecord(second.json) && second.json.type === FILES_NEEDED) throw unavailable('the node asked twice for the files of one request');
    return second;
  };

  /** A model answer: the node's canonical bytes and their ETag, or the node's refusal as it is. */
  const model = (reply: Answer, headers: Record<string, string> = {}): Answer =>
    reply.status < 400 ? { ...reply, headers: { etag: etagOf(reply.body), ...headers } } : reply;

  const rootOf = async () => {
    if (options.workspace === undefined) throw notFound('no working folder is set: start the UI with RAIL8_WORKSPACE');
    const workspace = options.workspace;
    return realpath(workspace).catch(() => {
      throw notFound(`the working folder ${workspace} does not exist`);
    });
  };

  const answer = async (req: IncomingMessage, route: Route, signal: AbortSignal): Promise<Answer> => {
    if (route.op === 'catalogue') return ask('catalogue', undefined, signal);
    const root = await rootOf();
    switch (route.op) {
      case 'list': {
        const { mappings, truncated } = await listMappings(root);
        return { status: 200, body: Buffer.from(JSON.stringify(mappings)), headers: truncated ? { 'rail8-truncated': 'true' } : {} };
      }
      case 'get': {
        const file = await mappingFile(root, route.id);
        const at = await bundleFolder(root, await mappingFolder(root, route.id));
        return model(await operate('canonical', await storedBytes(file), async () => at, signal, 'stored'));
      }
      case 'save': {
        const file = await mappingFile(root, route.id);
        const at = await bundleFolder(root, await mappingFolder(root, route.id));
        const sent = await bodyOf(req);
        const ifMatch = req.headers['if-match'];
        return locked(async () => {
          if (ifMatch !== undefined) {
            const stored = await operate('canonical', await storedBytes(file), async () => at, signal, 'stored');
            if (stored.status === 503) return stored;
            if (stored.status !== 200 || etagOf(stored.body) !== ifMatch) {
              throw new Problem(409, mappingProblem('mapping-changed'), 'Mapping changed', 'the mapping changed on disk since the If-Match tag was read; nothing was saved');
            }
          }
          const saved = await operate('canonical', sent, async () => at, signal, 'sent');
          if (saved.status === 200) await replaceFile(file, saved.body);
          return model(saved);
        });
      }
      case 'create': {
        const sent = await bodyOf(req);
        const id = memberOf(sent, 'id');
        // The id and its folder are checked before the node is asked; a body that names no id is the node's to refuse.
        const named = id === '' ? undefined : await mappingFolder(root, id);
        const folder = async () => named ?? mappingFolder(root, id);
        const created = await operate('create', sent, async () => bundleFolder(root, await folder()), signal);
        if (created.status !== 201) return created;
        const into = join(await folder(), basename(id) + SUFFIX);
        return locked(async () => {
          if (!(await writeNew(into, created.body))) {
            throw new Problem(409, mappingProblem('mapping-exists'), 'Mapping exists', 'a mapping with this id exists');
          }
          return model(created, { location: `/api/mappings/${id}` });
        });
      }
      case 'structure': {
        const sent = await bodyOf(req);
        const at = await bundleFolder(root, await baseFolder(root, memberOf(sent, 'base')));
        return operate('structure', sent, async () => at, signal);
      }
      default: {
        const at = await bundleFolder(root, await mappingFolder(root, route.id));
        return operate(route.op, await bodyOf(req), async () => at, signal);
      }
    }
  };

  const reply = (res: ServerResponse, { status, body, headers }: Answer) => {
    const type = status < 400 ? 'application/json' : 'application/problem+json';
    res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store', ...headers }).end(body);
  };

  return (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const path = new URL(req.url ?? '', 'http://localhost').pathname;
    if (path !== '/api' && !path.startsWith('/api/')) {
      next();
      return;
    }
    const stop = new AbortController();
    res.on('close', () => stop.abort());
    const answered = async () => {
      const method = req.method ?? 'GET';
      const route = routeOf(method, path);
      if (method !== 'GET') {
        const refused = foreign(req.headers, options.origins, "the mapping studio's API");
        if (refused !== undefined) throw new Problem(403, mappingProblem('foreign-origin'), 'Foreign origin', refused);
        if ((route.op === 'save' || route.op === 'create') && !options.editable) {
          throw new Problem(403, mappingProblem('read-only'), 'Read only', 'this server shows the working folder read-only (RAIL8_READ_ONLY is true); nothing was saved');
        }
        if (!isJson(req.headers['content-type'])) {
          throw new Problem(415, mappingProblem('unsupported-media-type'), 'Unsupported media type', 'the body of this request is application/json');
        }
      }
      return answer(req, route, stop.signal);
    };
    answered().then(
      (done) => reply(res, done),
      (error: unknown) => {
        if (stop.signal.aborted || res.headersSent) res.destroy();
        else if (error instanceof Problem) reply(res, { status: error.status, body: Buffer.from(JSON.stringify(error.json())) });
        else {
          const failed = new Problem(500, 'about:blank', 'Internal Server Error', `the working folder could not be read or written: ${String(error)}`);
          reply(res, { status: 500, body: Buffer.from(JSON.stringify(failed.json())) });
        }
      },
    );
  };
}
