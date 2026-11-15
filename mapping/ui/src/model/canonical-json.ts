import type { MappingModel } from '../generated/mapping-model';

/**
 * The text of a model in canonical form (ADR-0010), as the service's canonical writer prints it: keys sorted by UTF-16
 * code unit at every level, indented as `JSON.stringify(value, null, 2)` does, strings quoted as it quotes them, and a
 * final LF. A member held as `undefined` is left out, as `JSON.stringify` leaves it out of a request body. The model's
 * commands already leave out what the writer leaves out (defaults, empty collections), so for a working copy this is
 * the text Save writes. It is for display: the service stays the one writer (ADR-0013).
 */
export function canonicalJson(model: MappingModel): string {
  return `${print(model, '')}\n`;
}

/**
 * One value at the depth `indent`. The keys are printed one by one: an object would hold keys that look like array
 * indices in numeric order, not in code-unit order.
 */
function print(value: unknown, indent: string): string {
  const inner = `${indent}  `;
  let entries: string[];
  let open: string;
  let close: string;
  if (Array.isArray(value)) {
    [open, close] = ['[', ']'];
    entries = value.map((item) => print(item, inner));
  } else if (typeof value === 'object' && value !== null) {
    [open, close] = ['{', '}'];
    const record = value as Record<string, unknown>;
    entries = Object.keys(record)
      .filter((key) => record[key] !== undefined)
      .sort()
      .map((key) => `${JSON.stringify(key)}: ${print(record[key], inner)}`);
  } else {
    // a string, the format version or null: JSON.stringify spells them as the writer does
    return JSON.stringify(value);
  }
  return entries.length === 0 ? `${open}${close}` : `${open}\n${inner}${entries.join(`,\n${inner}`)}\n${indent}${close}`;
}
