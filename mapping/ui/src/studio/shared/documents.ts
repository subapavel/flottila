import type { StructureRef } from '../../generated/mapping-model';

/** A test document's format, by the root of the message's structure. */
export type DocumentFormat = 'XML' | 'JSON';

export const formatOf = (ref: StructureRef): DocumentFormat => ('json' in ref.root ? 'JSON' : 'XML');

const PARSER_ERROR = ['http://www.mozilla.org/newlayout/xml/parsererror.xml', 'http://www.w3.org/1999/xhtml'];

/** Parses a test document (the prototype's parsePayload); throws when the text is not well-formed XML or valid JSON. */
export function parseDocument(text: string, format: 'XML'): XMLDocument;
export function parseDocument(text: string, format: 'JSON'): unknown;
export function parseDocument(text: string, format: DocumentFormat): unknown;
export function parseDocument(text: string, format: DocumentFormat): unknown {
  if (format === 'JSON') return JSON.parse(text);
  const doc = new DOMParser().parseFromString(text, 'application/xml');
  if (doc.documentElement.localName === 'parsererror' || PARSER_ERROR.some((ns) => doc.getElementsByTagNameNS(ns, 'parsererror').length > 0)) {
    throw new Error('The text is not well-formed XML.');
  }
  return doc;
}

/** A document serialised back, with the declaration the text had. */
function xmlString(doc: XMLDocument, text: string): string {
  const declaration = /^\s*<\?xml[^?]*\?>/.exec(text)?.[0].trim() ?? '';
  const body = new XMLSerializer().serializeToString(doc).replace(/^<\?xml\s[^?]*\?>\s*/, '');
  return (declaration ? `${declaration}\n` : '') + body;
}

/**
 * The text indented two spaces a level (the prototype's Pretty Print): JSON in full, XML between the children of an
 * element with element-only content, unless it keeps its space (`xml:space="preserve"`). Throws as `parseDocument` does.
 */
export function prettyText(text: string, format: DocumentFormat): string {
  if (format === 'JSON') return JSON.stringify(parseDocument(text, 'JSON'), null, 2);
  const doc = parseDocument(text, 'XML');
  const indent = (element: Element, depth: number) => {
    if (element.getAttributeNS('http://www.w3.org/XML/1998/namespace', 'space') === 'preserve') return;
    const nodes = [...element.childNodes];
    if (element.children.length === 0 || nodes.some((n) => n.nodeType === Node.CDATA_SECTION_NODE || (n.nodeType === Node.TEXT_NODE && n.nodeValue!.trim()))) return;
    for (const n of nodes) {
      if (n.nodeType === Node.TEXT_NODE) {
        n.remove();
        continue;
      }
      element.insertBefore(doc.createTextNode(`\n${'  '.repeat(depth + 1)}`), n);
      if (n.nodeType === Node.ELEMENT_NODE) indent(n as Element, depth + 1);
    }
    element.append(doc.createTextNode(`\n${'  '.repeat(depth)}`));
  };
  indent(doc.documentElement, 0);
  return xmlString(doc, text);
}

/** A row of a document's tree (the prototype's payloadRows, read only): a field or attribute with its value. */
export interface DocumentRow {
  id: string;
  name: string;
  /** Its path in the document: `/Order/Line[1]/@id`, a JSON pointer for JSON. */
  path: string;
  level: number;
  /** Whether it has rows below it. */
  branch: boolean;
  /** Its value type: `string` for XML; the JSON type for JSON. */
  kind: string;
  value: string;
}

/** The rows of a test document in document order; `title` names a JSON document's root. Throws as `parseDocument` does. */
export function documentRows(text: string, format: DocumentFormat, title: string): DocumentRow[] {
  const rows: DocumentRow[] = [];
  if (format === 'JSON') {
    const visit = (value: unknown, name: string, location: string[]) => {
      const entries = value !== null && typeof value === 'object' ? Object.entries(value) : [];
      const path = location.map((k) => `/${k.replaceAll('~', '~0').replaceAll('/', '~1')}`).join('') || '/';
      const kind = Array.isArray(value) ? 'array' : value === null ? 'null' : typeof value;
      const shown = Array.isArray(value) ? `[${entries.length}]` : kind === 'object' ? '{…}' : String(value);
      rows.push({ id: path, name, path, level: location.length, branch: entries.length > 0, kind, value: shown });
      for (const [k, v] of entries) visit(v, Array.isArray(value) ? `[${k}]` : k, [...location, k]);
    };
    visit(parseDocument(text, 'JSON'), title, []);
    return rows;
  }
  const visit = (element: Element, prior: string, level: number, index: number) => {
    const name = element.tagName + (index ? `[${index}]` : '');
    const path = `${prior}/${name}`;
    const children = [...element.children];
    const attributes = [...element.attributes];
    const text = [...element.childNodes]
      .filter((n) => n.nodeType === Node.TEXT_NODE || n.nodeType === Node.CDATA_SECTION_NODE)
      .map((n) => n.nodeValue)
      .join('');
    rows.push({ id: path, name, path, level, branch: children.length + attributes.length > 0, kind: 'string', value: children.length ? text.trim() : text });
    for (const a of attributes) rows.push({ id: `${path}/@${a.name}`, name: `@${a.name}`, path: `${path}/@${a.name}`, level: level + 1, branch: false, kind: 'string', value: a.value });
    const counts = new Map<string, number>();
    for (const child of children) {
      const seen = counts.get(child.tagName) ?? 0;
      counts.set(child.tagName, seen + 1);
      visit(child, path, level + 1, seen);
    }
  };
  visit(parseDocument(text, 'XML').documentElement, '', 0, 0);
  return rows;
}
