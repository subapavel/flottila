import type { FlowFile, FlowItem } from '../files/flowFiles';
import type { ServedSteps } from './types';

export type { FlowFile, FlowItem };

/** The working folder's flow files, and whether they may be edited. `configured` is false when no folder is set. */
export interface FlowList {
  configured: boolean;
  editable: boolean;
  items: FlowItem[];
  /** True when the folder holds more YAML files than are looked at. */
  truncated: boolean;
}

/** A flow file and the version it was read at. */
export interface StoredFlow {
  file: FlowFile;
  etag: string;
}

/** What the flow screens ask of the server. Every call rejects with an {@link FlowsError} when it is refused. */
export interface FlowsApi {
  list(): Promise<FlowList>;
  read(path: string): Promise<StoredFlow>;
  /** The step types of the node's modules and plugins, from its admin API, or why they could not be read. */
  steps(): Promise<ServedSteps>;
}

/** A refusal: its HTTP status (0 when the server did not answer) and the error text it gave. */
export class FlowsError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'FlowsError';
    this.status = status;
  }
}

async function refusal(res: Response): Promise<FlowsError> {
  let text = `${res.status} ${res.statusText}`.trim();
  try {
    const body: unknown = await res.json();
    if (typeof body === 'object' && body !== null && 'error' in body && typeof body.error === 'string') text = body.error;
  } catch {
    // not JSON: the status line stands
  }
  return new FlowsError(res.status, text);
}

/**
 * The working folder's flow files at `/files/flows`, and the node's step types at `/admin/steps`, of the server that
 * serves the UI, through `send` (the browser's `fetch` by default).
 */
export function httpFlowsApi(send: typeof fetch = (input, init) => fetch(input, init)): FlowsApi {
  async function call(url: string): Promise<Response> {
    let res: Response;
    try {
      res = await send(url, { headers: { accept: 'application/json' } });
    } catch (failed) {
      throw new FlowsError(0, `the server does not answer: ${String(failed)}`);
    }
    if (!res.ok) throw await refusal(res);
    return res;
  }

  return {
    async list() {
      return (await (await call('/files/flows')).json()) as FlowList;
    },
    async read(path) {
      const res = await call(`/files/flows/${path.split('/').map(encodeURIComponent).join('/')}`);
      const etag = res.headers.get('etag');
      if (etag === null) throw new FlowsError(res.status, 'the server sent the file without its ETag');
      return { file: (await res.json()) as FlowFile, etag };
    },
    async steps() {
      try {
        return ((await (await call('/admin/steps')).json()) as { items: { type: string; configSchema: unknown }[] }).items;
      } catch (failed) {
        return { missing: failed instanceof Error ? failed.message : String(failed) };
      }
    },
  };
}
