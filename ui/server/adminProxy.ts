import type { IncomingHttpHeaders, IncomingMessage, ServerResponse } from 'node:http';
import { Readable } from 'node:stream';
import type { ReadableStream as WebReadableStream } from 'node:stream/web';

/** Where the development proxy sends the UI's admin requests, and whom it lets through. */
export interface AdminProxyOptions {
  /** The node's admin API, such as `http://127.0.0.1:8081`; a request keeps its path and query. */
  target: string;
  /** The development server's own origins (scheme, host and port); a request with any other `Origin` is refused. */
  origins: readonly string[];
  /** The bearer token the node needs; undefined for a node without one. */
  token?: string;
}

/** The request headers passed on to the node. Every other one, `Origin`, `Referer`, `Cookie`, `Authorization` and `Sec-Fetch-*` among them, stays behind. */
const PASSED = ['accept', 'content-type'] as const;

/** The response headers passed back to the browser. */
const RETURNED = ['content-type', 'content-length', 'www-authenticate'] as const;

/** What to do with a request: refuse it, saying why, or send it on with these headers. */
export type Forward = { refused: string } | { headers: Record<string, string> };

/**
 * Why a request does not come from a page of this server, or undefined when it does: it must carry
 * `Sec-Fetch-Site: same-origin`, and an `Origin`, when there is one, from `origins` (never compared with the request's
 * `Host`, which a page on a re-bound domain controls). `what` names what the request is for in the refusal.
 */
export function foreign(headers: IncomingHttpHeaders, origins: readonly string[], what: string): string | undefined {
  if (headers['sec-fetch-site'] !== 'same-origin') {
    return `only this server's own pages may call ${what} through it (Sec-Fetch-Site must be same-origin)`;
  }
  if (headers.origin !== undefined && !origins.includes(headers.origin)) {
    return `the origin ${headers.origin} may not call ${what} through this server`;
  }
  return undefined;
}

/**
 * Decides whether a browser request may reach the node, and with which headers. It must come from a page of this
 * server: `Sec-Fetch-Site: same-origin`, and an `Origin`, when there is one, from the fixed list (never compared with
 * the request's `Host`, which a page on a re-bound domain controls). Only `Accept` and `Content-Type` go on; the token,
 * when one is configured, is set here, so the browser never holds it.
 */
export function forward(headers: IncomingHttpHeaders, options: Pick<AdminProxyOptions, 'origins' | 'token'>): Forward {
  const refused = foreign(headers, options.origins, 'the admin API');
  if (refused !== undefined) return { refused };
  const passed: Record<string, string> = {};
  for (const name of PASSED) {
    const value = headers[name];
    if (typeof value === 'string') passed[name] = value;
  }
  if (options.token) passed.authorization = `Bearer ${options.token}`;
  return { headers: passed };
}

function isAdmin(url: string): boolean {
  return url === '/admin' || url.startsWith('/admin/') || url.startsWith('/admin?');
}

function refuse(res: ServerResponse, status: number, error: string) {
  res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify({ error }));
}

/**
 * A development-server middleware that sends `/admin` requests from the UI's own pages on to the node, as the node
 * answers no browser. It takes GET and POST without a body, which is all the UI sends (405 otherwise), refuses what
 * {@link forward} refuses (403), and answers 502 when the node cannot be reached. Every other path goes to `next`.
 */
export function adminProxy(options: AdminProxyOptions) {
  return (req: IncomingMessage, res: ServerResponse, next: () => void) => {
    const url = req.url ?? '';
    if (!isAdmin(url)) {
      next();
      return;
    }
    if (req.method !== 'GET' && req.method !== 'POST') {
      refuse(res, 405, 'this server passes only GET and POST on to the admin API');
      return;
    }
    const decided = forward(req.headers, options);
    if ('refused' in decided) {
      refuse(res, 403, decided.refused);
      return;
    }
    const stop = new AbortController();
    res.on('close', () => stop.abort());
    fetch(options.target + url, { method: req.method, headers: decided.headers, signal: stop.signal }).then(
      (answer) => {
        const returned: Record<string, string> = {};
        for (const name of RETURNED) {
          const value = answer.headers.get(name);
          if (value !== null) returned[name] = value;
        }
        res.writeHead(answer.status, returned);
        if (answer.body === null) res.end();
        else Readable.fromWeb(answer.body as WebReadableStream<Uint8Array>).on('error', () => res.destroy()).pipe(res);
      },
      (failed: unknown) => {
        if (!res.headersSent) refuse(res, 502, `the node's admin API at ${options.target} does not answer: ${String(failed)}`);
      },
    );
  };
}
