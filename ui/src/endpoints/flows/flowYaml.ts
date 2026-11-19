import { parseAllDocuments, type Document } from 'yaml';

/**
 * A text read as a flow file:
 *
 * - `flow`: a YAML mapping whose `kind` is `flow`, with a text `id` and a list as `steps`: its first document as the
 *   `yaml` library parsed it (keys used twice are kept, for the view to find, and merge keys are not merged, as the node does not merge them), its value, and how many documents the text
 *   holds.
 * - `problem`: a text that is no YAML, or a flow without a text `id` or with a `steps` that is no list. Whether such a
 *   text is a flow file at all is for the caller to judge, as {@link saysFlow} does.
 * - `other`: YAML of another kind, or not a mapping.
 */
export type FlowRead =
  | { kind: 'flow'; doc: Document.Parsed; value: Record<string, unknown>; id: string; documents: number }
  | { kind: 'problem'; message: string }
  | { kind: 'other' };

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/** Whether `text` says, on a line of its own, that it is a flow file: what makes a file that cannot be read a flow file. */
export const saysFlow = (text: string) => /^kind:\s*["']?flow["']?\s*(#.*)?$/m.test(text);

/** Reads `text` as a flow file, as {@link FlowRead} describes. The node reads only the first document of a file too. */
export function readFlow(text: string): FlowRead {
  const docs = parseAllDocuments(text, { uniqueKeys: false, prettyErrors: false, merge: false, logLevel: 'error' });
  if (!Array.isArray(docs) || docs.length === 0) return { kind: 'other' };
  const doc = docs[0]!;
  const error = doc.errors[0] ?? docs.find((d) => d.errors.length > 0)?.errors[0];
  if (error !== undefined) return { kind: 'problem', message: `the file is not YAML: ${error.message.split('\n')[0]}` };
  let value: unknown;
  try {
    value = doc.toJS();
  } catch (failed) {
    return { kind: 'problem', message: `the file cannot be read: ${failed instanceof Error ? failed.message : String(failed)}` };
  }
  if (!isRecord(value) || value.kind !== 'flow') return { kind: 'other' };
  if (typeof value.id !== 'string') return { kind: 'problem', message: 'a flow file names its id' };
  if (!Array.isArray(value.steps)) return { kind: 'problem', message: 'the flow has no list of steps under steps' };
  return { kind: 'flow', doc, value, id: value.id, documents: docs.length };
}
