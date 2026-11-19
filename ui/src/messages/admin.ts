/** How a recorded step ended: it handed a message on, it handed nothing on, or it threw. */
export type Outcome = 'OK' | 'FILTERED' | 'FAILED';

/** One recorded step of one attempt of a message, as the node's admin API returns it. */
export interface StepRecord {
  step: number;
  attempt: number;
  name: string;
  outcome: Outcome;
  /** The error message of a step that failed, else null. */
  error: string | null;
  /** When the step started, as an ISO-8601 instant. */
  startedAt: string;
  wallNanos: number;
  /** Null when the CPU time is not known. */
  cpuNanos: number | null;
  /** The payload after the step: its hash, its size in bytes, and whether its bytes were kept. */
  body: { hash: string; size: number; kept: boolean };
}

/** A message's recorded steps: every attempt in order, and the steps of each in order. */
export interface Timeline {
  id: string;
  flow: string;
  steps: StepRecord[];
}

/** Where a message stands: done, parked or failed, still going, or ended by an operator. */
export type Status = 'completed' | 'failed' | 'running' | 'cancelled';

/** One message of the list of recent messages. */
export interface Listed {
  id: string;
  flow: string;
  status: Status;
  /** When its arrival started, as an ISO-8601 instant. */
  startedAt: string;
  /** From its arrival to the end of its latest recorded step. */
  durationNanos: number;
  keys: Record<string, string>;
}

/**
 * What the list of recent messages shows: the messages whose arrival started at `from` or later and before `to` (ISO-8601
 * instants, at most 31 days apart), narrowed by each filter that is given.
 */
export interface ListQuery {
  from: string;
  to: string;
  flow?: string;
  status?: Status;
  /** A business key the message carries. */
  key?: { name: string; value: string };
  /** The name of a step the message passed. */
  step?: string;
}

/** One page of a list, and the cursor of the next page, or null on the last one. */
export interface Page<T> {
  items: T[];
  next: string | null;
}

/** The start of a recorded payload as text, and whether that is all of it. */
export interface Payload {
  contentType: string;
  text: string;
  complete: boolean;
}

/** The new attempt a restart started. */
export interface Restarted {
  id: string;
  attempt: number;
}

/** What the message screens ask of a node. Every call rejects with an {@link AdminError} when the node refuses it. */
export interface AdminApi {
  /**
   * The messages `query` names, newest first, a page at a time from `after`. A step's name is `ingress` for the arrival,
   * and `<call>/<step>` for a step of a local process.
   */
  list(query: ListQuery, after?: string): Promise<Page<Listed>>;
  /** The ids of the flows deployed on the node, sorted by id. */
  flows(): Promise<string[]>;
  /** The message's recorded steps, or null when the node recorded no message with that id. */
  timeline(id: string): Promise<Timeline | null>;
  /** The payload after step `step` of attempt `attempt`, its first {@link PAYLOAD_SHOWN} bytes at most. */
  payload(id: string, step: number, attempt: number): Promise<Payload>;
  /** Runs a failed message again from its arrival, as its next attempt. */
  restart(id: string): Promise<Restarted>;
}

/** A refusal by the node: its HTTP status and the error text it gave. */
export class AdminError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'AdminError';
    this.status = status;
  }
}

/** How much of a payload is read and shown, in bytes. */
export const PAYLOAD_SHOWN = 1024 * 1024;

async function refusal(res: Response): Promise<AdminError> {
  let text = `${res.status} ${res.statusText}`.trim();
  try {
    const body: unknown = await res.json();
    if (typeof body === 'object' && body !== null && 'error' in body && typeof body.error === 'string') text = body.error;
  } catch {
    // not JSON: the status line stands
  }
  return new AdminError(res.status, text);
}

/** Reads at most `limit` bytes of `res`'s body, then stops reading. */
async function readStart(res: Response, limit: number): Promise<{ bytes: Uint8Array; complete: boolean }> {
  if (res.body === null) return { bytes: new Uint8Array(), complete: true };
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let read = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    read += value.length;
    if (read > limit) {
      await reader.cancel();
      break;
    }
  }
  const bytes = new Uint8Array(await new Blob(chunks as BlobPart[]).arrayBuffer());
  return { bytes: bytes.subarray(0, limit), complete: read <= limit };
}

/**
 * The admin API at `/admin` of the server that serves the UI, through `send` (the browser's `fetch` by default). In
 * development, that server passes the requests on to the node.
 */
export function httpAdminApi(send: typeof fetch = (input, init) => fetch(input, init)): AdminApi {
  const message = (id: string) => `/admin/messages/${encodeURIComponent(id)}`;

  async function json<T>(url: string, init?: RequestInit): Promise<T> {
    const res = await send(url, { ...init, headers: { accept: 'application/json' } });
    if (!res.ok) throw await refusal(res);
    return (await res.json()) as T;
  }

  return {
    list({ from, to, flow, status, key, step }, after) {
      const query = new URLSearchParams({ from, to });
      if (flow !== undefined) query.set('flow', flow);
      if (status !== undefined) query.set('status', status);
      if (key !== undefined) query.set('key', `${key.name}:${key.value}`);
      if (step !== undefined) query.set('step', step);
      if (after !== undefined) query.set('after', after);
      return json<Page<Listed>>(`/admin/messages?${query}`);
    },

    async flows() {
      const listed = await json<{ items: { id: string }[] }>('/admin/flows');
      return listed.items.map((flow) => flow.id);
    },

    async timeline(id) {
      try {
        return await json<Timeline>(message(id));
      } catch (refused) {
        if (refused instanceof AdminError && refused.status === 404) return null;
        throw refused;
      }
    },

    async payload(id, step, attempt) {
      const res = await send(`${message(id)}/steps/${step}/body?attempt=${attempt}`);
      if (!res.ok) throw await refusal(res);
      const { bytes, complete } = await readStart(res, PAYLOAD_SHOWN);
      return { contentType: res.headers.get('content-type') ?? 'application/octet-stream', text: new TextDecoder().decode(bytes), complete };
    },

    restart(id) {
      return json<Restarted>(`/admin/dlq/${encodeURIComponent(id)}/retry`, { method: 'POST' });
    },
  };
}
