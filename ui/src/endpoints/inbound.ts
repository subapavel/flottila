import type { Options } from './settings';

/** What the node tells of where it takes requests, besides each component's prefix. */
export interface NodeIngress {
  /** The node's environment: each endpoint is deployed with that environment's overlay. */
  environment: string;
  /** The origin senders call the node's HTTP port at, such as `https://rail8.example.com`, or null when the node is given none. */
  httpOrigin: string | null;
}

/** An environment's overlay of an endpoint file: the options it sets. */
export interface Overlay {
  name: string;
  config: Options;
}

/**
 * An endpoint's options as the node deploys them in `environment`: `options` with that environment's overlay laid over
 * them, each key the overlay names replaced whole, never merged.
 */
export function optionsIn(options: Options, environments: readonly Overlay[], environment: string): Options {
  const overlay = environments.find((env) => env.name === environment);
  return overlay === undefined ? options : { ...options, ...overlay.config };
}

/**
 * Where senders call an in endpoint of a component with an HTTP prefix: the prefix and the endpoint's `address`, after
 * the node's origin when it is known, else the path alone. Null when the address is no text starting with `/`.
 */
export function inboundAddress(prefix: string, address: unknown, origin: string | null): string | null {
  if (typeof address !== 'string' || !address.startsWith('/')) return null;
  return `${origin ?? ''}${prefix}${address}`;
}
