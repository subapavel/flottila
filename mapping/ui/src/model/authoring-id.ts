import type { AuthoringId } from '../generated/mapping-model';

/** Lowercase Crockford base32: no i, l, o or u. */
const ALPHABET = '0123456789abcdefghjkmnpqrstvwxyz';

/**
 * A fresh authoring id: 10 lowercase Crockford base32 characters, drawn with `crypto.getRandomValues` (ADR-0010).
 * `fill` is the random source, a port for tests.
 */
export function newAuthoringId(
  fill: (bytes: Uint8Array<ArrayBuffer>) => Uint8Array = (bytes) => {
    crypto.getRandomValues(bytes);
    return bytes;
  },
): AuthoringId {
  return Array.from(fill(new Uint8Array(10)), (byte) => ALPHABET[byte & 31]).join('');
}
