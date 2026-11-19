import { readFile, realpath } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { foreign } from '../../../server/adminProxy.ts';
import { readFlow, saysFlow } from '../flows/flowYaml.ts';
import { EndpointFileError, readEndpoint } from './endpointYaml.ts';
import { etagOf, jsonBody, locate, MAX_FILE_BYTES, Refusal, replaceFile, send, sendFailure, walk } from './workingFolder.ts';

/** The working folder whose flow files the flow screen draws, and who may reach them. */
export interface FlowFilesOptions {
  /** The working folder; undefined when none is set, and the routes say so. */
  workspace?: string;
  /** Whether the files may be edited; the answers say so. */
  editable: boolean;
  /** The development server's own origins; a request with any other `Origin` is refused. */
  origins: readonly string[];
}

/** One flow file of the working folder: its path relative to the folder, with `/`, and its id or why it cannot be read. */
export type FlowItem = { path: string; id: string } | { path: string; problem: string };

/**
 * What a flow refers to in the working folder. `endpoints` are those of the flow's package, the nearest folder above the
 * file that holds `package.yaml`; `address` is a `direct` endpoint's, and `overlaid` says an environment changes it.
 * `flows` are every flow of the working folder, `address` the one their inbound `direct` endpoint serves. `partial` is
 * true when the walk stopped at its limit or a file of the package could not be read, so that what is not found may still
 * exist.
 */
export interface FlowContext {
  endpoints: { id: string; path: string; scheme: string; direction: 'in' | 'out'; address?: string; overlaid?: true }[];
  flows: { path: string; id: string; mode?: 'sync' | 'at-least-once' | 'at-most-once'; address?: string }[];
  partial: boolean;
}

/** What `GET /files/flows/<path>` answers. */
export interface FlowFile {
  path: string;
  text: string;
  editable: boolean;
  context: FlowContext;
}

const PREFIX = '/files/flows';
const MODES = new Set(['sync', 'at-least-once', 'at-most-once']);

/** The files the node reads as a package's YAML: `*.yaml` and `*.yml`. */
const isYaml = (name: string) => name.endsWith('.yaml') || name.endsWith('.yml');

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** The folder of `path`, with `/`; '' for a file at the top of the working folder. */
const folderOf = (path: string) => path.slice(0, Math.max(0, path.lastIndexOf('/')));

interface Endpoint {
  entry: FlowContext['endpoints'][number];
  pkg: string;
}

/** The working folder as one walk reads it: its flows, its endpoints, the folder of each package, and what could not be read. */
async function survey(root: string) {
  const { files, truncated } = await walk(root, isYaml);
  const packages = new Set(files.filter((f) => f.path === 'package.yaml' || f.path.endsWith('/package.yaml')).map((f) => folderOf(f.path)));
  const packageOf = (path: string) => {
    for (let folder = folderOf(path); ; folder = folderOf(folder)) {
      if (packages.has(folder) || folder === '') return folder;
    }
  };
  const flows: { item: FlowItem; from?: string; mode?: string; pkg: string }[] = [];
  const endpoints: Endpoint[] = [];
  const unreadable = new Set<string>();
  for (const { path, text } of files) {
    const pkg = packageOf(path);
    const read = readFlow(text);
    if (read.kind === 'flow') {
      const from = isRecord(read.value.from) && typeof read.value.from.endpoint === 'string' ? read.value.from.endpoint : undefined;
      const mode = isRecord(read.value.delivery) && typeof read.value.delivery.mode === 'string' ? read.value.delivery.mode : undefined;
      flows.push({ item: { path, id: read.id }, from, mode, pkg });
      continue;
    }
    if (read.kind === 'problem') {
      unreadable.add(pkg);
      if (saysFlow(text)) flows.push({ item: { path, problem: read.message }, pkg });
      continue;
    }
    try {
      const doc = readEndpoint(text);
      if (doc === null) continue;
      const address = typeof doc.config.address === 'string' ? doc.config.address : undefined;
      const overlaid = doc.environments.some((env) => env.options.includes('address'));
      endpoints.push({
        pkg,
        entry: { id: doc.id, path, scheme: doc.scheme, direction: doc.direction, ...(address === undefined ? {} : { address }), ...(overlaid ? { overlaid } : {}) },
      });
    } catch (error) {
      if (!(error instanceof EndpointFileError)) throw error;
      unreadable.add(pkg);
    }
  }
  return { flows, endpoints, unreadable, truncated, packageOf };
}

/**
 * A development-server middleware over the flow files of the working folder, for the UI's own pages only (403 as the
 * `/admin` proxy refuses). A flow file is a `*.yaml` or `*.yml` whose `kind` is `flow`; the walk and its limits are the
 * endpoint files'.
 *
 * - `GET /files/flows`: 200 `{configured, editable, truncated, items}`, each flow file as a {@link FlowItem}, by path;
 *   `configured` is false, with no items, when no working folder is set.
 * - `GET /files/flows/<path>`: 200 the {@link FlowFile} with its `ETag`, the text as it is, also when it cannot be
 *   drawn; 404 when there is no such flow file, 413 when it is over 1 MiB.
 * - `PUT /files/flows/<path>` with `If-Match` and `{"text": "..."}`: writes the text over the flow file, through a
 *   temporary file renamed over it, and answers 200 as GET does. A text equal to the file's is not written, so its
 *   `ETag` stays. Refused with 403 when not editable, 428 without `If-Match`, 400 when the body is not `{"text": "..."}`,
 *   413 when the text is over 1 MiB, 404 when there is no such flow file, 412 when the file changed since that tag
 *   (compared again just before the rename), and 422 when the text is no YAML or no flow, as the browser reads it.
 *
 * Saves are made one at a time. Every other path goes to `next`.
 */
export function flowFiles(options: FlowFilesOptions) {
  let saves: Promise<unknown> = Promise.resolve();
  const rootOf = async () => {
    if (options.workspace === undefined) throw new Refusal(404, 'no working folder is set: start the UI with RAIL8_WORKSPACE');
    return realpath(options.workspace);
  };

  const list = async () => {
    if (options.workspace === undefined) return { configured: false, editable: false, items: [], truncated: false };
    const { flows, truncated } = await survey(await rootOf());
    return { configured: true, editable: options.editable, truncated, items: flows.map((f) => f.item) };
  };

  const read = async (path: string) => {
    const root = await rootOf();
    const file = await locate(root, path, isYaml, 'flow file');
    const bytes = await readFile(file);
    const text = bytes.toString('utf8');
    const flow = readFlow(text);
    if (flow.kind === 'other' || (flow.kind === 'problem' && !saysFlow(text))) throw new Refusal(404, `${path} is not a flow file`);
    const { flows, endpoints, unreadable, truncated, packageOf } = await survey(root);
    const pkg = packageOf(path);
    const served = (f: (typeof flows)[number]) =>
      endpoints.find((e) => e.pkg === f.pkg && e.entry.id === f.from && e.entry.scheme === 'direct' && e.entry.direction === 'in')?.entry;
    const context: FlowContext = {
      endpoints: endpoints.filter((e) => e.pkg === pkg).map((e) => e.entry),
      flows: flows.flatMap((f) => {
        if (!('id' in f.item)) return [];
        const door = served(f);
        const address = door !== undefined && door.overlaid === undefined ? door.address : undefined;
        return [
          {
            path: f.item.path,
            id: f.item.id,
            ...(f.mode !== undefined && MODES.has(f.mode) ? { mode: f.mode as 'sync' } : {}),
            ...(address === undefined ? {} : { address }),
          },
        ];
      }),
      partial: truncated || unreadable.has(pkg),
    };
    return { file: { path, text, editable: options.editable, context } satisfies FlowFile, etag: etagOf(bytes) };
  };

  const save = async (req: IncomingMessage, path: string) => {
    if (!options.editable) throw new Refusal(403, 'this server shows flow files read-only');
    const ifMatch = req.headers['if-match'];
    if (ifMatch === undefined) throw new Refusal(428, 'a save names the version it changes in If-Match');
    const sent = await jsonBody(req);
    if (!isRecord(sent) || typeof sent.text !== 'string') throw new Refusal(400, 'a save sends {"text": "..."}');
    const text = sent.text;
    if (Buffer.byteLength(text, 'utf8') > MAX_FILE_BYTES) throw new Refusal(413, 'the text is over 1 MiB');
    const file = await locate(await rootOf(), path, isYaml, 'flow file');
    const bytes = await readFile(file);
    const current = bytes.toString('utf8');
    const old = readFlow(current);
    if (old.kind === 'other' || (old.kind === 'problem' && !saysFlow(current))) throw new Refusal(404, `${path} is not a flow file`);
    const unchanged = async () => {
      if (etagOf(await readFile(file)) !== ifMatch) throw new Refusal(412, 'the file changed since it was read; nothing was saved');
    };
    if (etagOf(bytes) !== ifMatch) throw new Refusal(412, 'the file changed since it was read; nothing was saved');
    const flow = readFlow(text);
    if (flow.kind === 'problem') throw new Refusal(422, flow.message);
    if (flow.kind === 'other') throw new Refusal(422, 'the text is not a flow');
    if (!bytes.equals(Buffer.from(text, 'utf8'))) await replaceFile(file, text, unchanged);
    return read(path);
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
      } else send(res, 405, { error: 'the flow files take GET, and PUT of one file' });
    };
    answer().catch((error: unknown) => sendFailure(res, error));
  };
}
