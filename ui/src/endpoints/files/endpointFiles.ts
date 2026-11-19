import { readFile, realpath } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { foreign } from '../../../server/adminProxy.ts';
import { EndpointFileError, readEndpoint, writeConfig, type EndpointDocument } from './endpointYaml.ts';
import { createFile, etagOf, jsonBody, locate, MAX_FILE_BYTES, Refusal, replaceFile, send, sendFailure, walk } from './workingFolder.ts';

export { etagOf, MAX_FILE_BYTES, MAX_FILES } from './workingFolder.ts';

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

const PREFIX = '/files/endpoints';

const isYaml = (name: string) => name.endsWith('.yaml') && !name.endsWith('.test.yaml');

/**
 * The endpoint files under `root`: every `*.yaml` but a `*.test.yaml` whose `kind` is `endpoint`, sorted by path, and
 * whether the walk stopped at its limit of files. The folders and files the walk passes over are not looked at, and a
 * file that is no YAML is passed over unless it says `kind: endpoint`.
 */
export async function listEndpoints(root: string): Promise<{ items: EndpointItem[]; truncated: boolean }> {
  const { files, truncated } = await walk(root, isYaml);
  const items: EndpointItem[] = [];
  for (const { path, text } of files) {
    try {
      const doc = readEndpoint(text);
      if (doc !== null) items.push({ path, id: doc.id, scheme: doc.scheme, direction: doc.direction });
    } catch (error) {
      if (error instanceof EndpointFileError && /^kind:\s*endpoint\s*$/m.test(text)) items.push({ path, problem: error.message });
    }
  }
  return { items, truncated };
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
 * - `PUT /files/endpoints/<path>` with `If-None-Match: *` and `{"text": "..."}`: creates the endpoint file, in a folder
 *   of the working folder that exists, with the text, which must read as an endpoint file, and answers 201 as GET
 *   does; 403 when not editable, 412 when a file of that name exists, 404 when its folder does not, 422 when the text is
 *   no endpoint file, 400 when both preconditions are sent.
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
    const file = await locate(await rootOf(), path, isYaml, 'endpoint file');
    const bytes = await readFile(file);
    return { file: endpointOf(bytes.toString('utf8'), path, options.editable), etag: etagOf(bytes) };
  };

  const create = async (req: IncomingMessage, path: string) => {
    const sent = await jsonBody(req);
    if (typeof sent !== 'object' || sent === null || !('text' in sent) || typeof sent.text !== 'string') throw new Refusal(400, 'a create sends {"text": "..."}');
    const text = sent.text;
    if (Buffer.byteLength(text, 'utf8') > MAX_FILE_BYTES) throw new Refusal(413, 'the text is over 1 MiB');
    let file: EndpointFile;
    try {
      file = endpointOf(text, path, options.editable);
    } catch (error) {
      throw error instanceof Refusal && error.status === 404 ? new Refusal(422, 'the text is not an endpoint file') : error;
    }
    await createFile(await rootOf(), path, isYaml, 'endpoint file', text);
    return { file, etag: etagOf(text), created: true };
  };

  const save = async (req: IncomingMessage, path: string) => {
    if (!options.editable) throw new Refusal(403, 'this server shows endpoint files read-only');
    const ifMatch = req.headers['if-match'];
    const ifNoneMatch = req.headers['if-none-match'];
    if (ifNoneMatch !== undefined && ifMatch !== undefined) throw new Refusal(400, 'a save sends If-Match or If-None-Match, not both');
    if (ifNoneMatch === '*') return create(req, path);
    if (ifMatch === undefined) throw new Refusal(428, 'a save names the version it changes in If-Match');
    const sent = await jsonBody(req);
    if (typeof sent !== 'object' || sent === null || !('config' in sent)) throw new Refusal(400, 'a save sends {"config": {...}}');
    const config = sent.config;
    if (typeof config !== 'object' || config === null || Array.isArray(config)) throw new Refusal(400, 'config is a JSON object');
    const file = await locate(await rootOf(), path, isYaml, 'endpoint file');
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
    await replaceFile(file, written);
    return { file: endpointOf(written, path, options.editable), etag: etagOf(written), created: false };
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
        const { file, etag, created } = await done;
        send(res, created ? 201 : 200, file, etag);
      } else send(res, 405, { error: 'the working folder takes GET, and PUT of one file' });
    };
    answer().catch((error: unknown) => sendFailure(res, error));
  };
}
