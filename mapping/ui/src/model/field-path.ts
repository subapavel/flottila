/**
 * A step of a field path's text (ADR-0007): an EQName's `Q{namespace}` may hold a `/`; a JSON property's name has no
 * raw `/` or brace, as it percent-encodes `% / @ { }`.
 */
const STEP = /(?:\{[^}]*\}|[^/{])+/g;

/** The escapes of a JSON property's name: `%` and the upper-case hex code of one of `% / @ { }` (ADR-0007). */
const ESCAPE = /%(25|2F|40|7B|7D)/g;

/** The steps of a field path, root first, each as the structure spells it, so a JSON step stays escaped. */
export function fieldPathSteps(path: string): string[] {
  return path.match(STEP) ?? [];
}

/**
 * The name a field path's last step shows (a single step reads as a path of that step): its local name, `@` before
 * an attribute's, and a JSON property's name as the schema spells it, its escapes decoded once; a JSON root is `$`.
 * A path with no step shows itself.
 */
export function stepName(path: string): string {
  const last = fieldPathSteps(path).at(-1) ?? path;
  return decodeName(last);
}

/**
 * A field path as its steps' local names joined by `/`, for display: each as `stepName` shows it.
 * A path with no step shows itself.
 */
export function localPath(path: string): string {
  const steps = fieldPathSteps(path);
  return steps.length === 0 ? path : steps.map(decodeName).join('/');
}

/**
 * A field path in prose: it starts the text or follows whitespace, and is a run of `/`-prefixed steps (`Q{ns}local`,
 * `@Q{ns}local`, `@local`, a JSON name or `$`); a step ends at `/`, whitespace or `(`, and its braces may hold a `/`.
 */
const PROSE_PATH = /(?<=^|\s)(?:\/@?(?:Q\{[^}]*\})?[^\s(/{}]+)+/g;

/** A piece of a message: its text as shown, and for a field path the full path it stands for. */
export interface ProseSegment {
  text: string;
  path?: string;
}

/** A message split into plain text and the field paths inside it; a path's text is its `localPath`. */
export function proseSegments(message: string): ProseSegment[] {
  const segments: ProseSegment[] = [];
  let from = 0;
  for (const match of message.matchAll(PROSE_PATH)) {
    if (match.index > from) segments.push({ text: message.slice(from, match.index) });
    segments.push({ text: localPath(match[0]), path: match[0] });
    from = match.index + match[0].length;
  }
  if (from < message.length) segments.push({ text: message.slice(from) });
  return segments;
}

function decodeName(step: string): string {
  return step.replace(/^(@?)Q\{[^}]*\}/, '$1').replace(ESCAPE, (_, hex: string) => String.fromCharCode(parseInt(hex, 16)));
}
