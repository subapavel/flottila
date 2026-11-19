import type { NodeIngress } from './inbound';
import type { Options, SettingsDescription } from './settings';

/** One endpoint file of the working folder: its path relative to the folder, and its envelope or why it cannot be read. */
export type EndpointItem = { path: string; id: string; scheme: string; direction: 'in' | 'out' } | { path: string; problem: string };

/** The working folder's endpoint files, and whether they may be edited. `configured` is false when no folder is set. */
export interface EndpointList {
  configured: boolean;
  editable: boolean;
  items: EndpointItem[];
  /** True when the folder holds more YAML files than are looked at. */
  truncated: boolean;
}

/** One endpoint file as the form shows it. */
export interface EndpointFile {
  path: string;
  editable: boolean;
  id: string;
  scheme: string;
  direction: 'in' | 'out';
  description: string | null;
  config: Options;
  /** Each environment that overrides options, with the names of the options it overrides and the options it sets. */
  environments: { name: string; options: string[]; config: Options }[];
}

/** An endpoint file and the version it was read at, sent back when it is saved. */
export interface StoredEndpoint {
  file: EndpointFile;
  etag: string;
}

/** A component the node serves, with the path its in endpoints are served under on the node's HTTP port, if any, and its settings description. */
export interface ComponentSettings {
  scheme: string;
  httpPrefix: string | null;
  settings: SettingsDescription;
}

/** What `GET /admin/components` answers: where the node takes requests, and every component it serves. */
export interface NodeComponents extends NodeIngress {
  items: ComponentSettings[];
}

/** What the endpoint screens ask of the server. Every call rejects with an {@link EndpointsError} when it is refused. */
export interface EndpointsApi {
  list(): Promise<EndpointList>;
  read(path: string): Promise<StoredEndpoint>;
  /** Writes `config` into the file, on condition that it is still at `etag`; a 412 refusal means it changed since. */
  save(path: string, config: Options, etag: string): Promise<StoredEndpoint>;
  /** Creates the file at `path` holding `text`; a 412 refusal means a file of that name exists. */
  create(path: string, text: string): Promise<StoredEndpoint>;
  /** The components the node serves, and where it takes requests, from its admin API. */
  components(): Promise<NodeComponents>;
}

/** A refusal: its HTTP status (0 when the server did not answer) and the error text it gave. */
export class EndpointsError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'EndpointsError';
    this.status = status;
  }
}

async function refusal(res: Response): Promise<EndpointsError> {
  let text = `${res.status} ${res.statusText}`.trim();
  try {
    const body: unknown = await res.json();
    if (typeof body === 'object' && body !== null && 'error' in body && typeof body.error === 'string') text = body.error;
  } catch {
    // not JSON: the status line stands
  }
  return new EndpointsError(res.status, text);
}

/**
 * The working folder's endpoint files at `/files/endpoints`, and the node's components at `/admin/components`, of the
 * server that serves the UI, through `send` (the browser's `fetch` by default).
 */
export function httpEndpointsApi(send: typeof fetch = (input, init) => fetch(input, init)): EndpointsApi {
  const fileUrl = (path: string) => `/files/endpoints/${path.split('/').map(encodeURIComponent).join('/')}`;

  async function call(url: string, init: RequestInit = {}): Promise<Response> {
    let res: Response;
    try {
      res = await send(url, { ...init, headers: { accept: 'application/json', ...init.headers } });
    } catch (failed) {
      throw new EndpointsError(0, `the server does not answer: ${String(failed)}`);
    }
    if (!res.ok) throw await refusal(res);
    return res;
  }

  async function stored(res: Response): Promise<StoredEndpoint> {
    const etag = res.headers.get('etag');
    if (etag === null) throw new EndpointsError(res.status, 'the server sent the file without its ETag');
    return { file: (await res.json()) as EndpointFile, etag };
  }

  return {
    async list() {
      return (await (await call('/files/endpoints')).json()) as EndpointList;
    },
    async read(path) {
      return stored(await call(fileUrl(path)));
    },
    async save(path, config, etag) {
      const init = { method: 'PUT', headers: { 'content-type': 'application/json', 'if-match': etag }, body: JSON.stringify({ config }) };
      return stored(await call(fileUrl(path), init));
    },
    async create(path, text) {
      const init = { method: 'PUT', headers: { 'content-type': 'application/json', 'if-none-match': '*' }, body: JSON.stringify({ text }) };
      return stored(await call(fileUrl(path), init));
    },
    async components() {
      return (await (await call('/admin/components')).json()) as NodeComponents;
    },
  };
}
