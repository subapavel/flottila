import type { RootSelector } from '../../generated/mapping-model';

/** The kind of a root selector (GLOSSARY: Root selector): an XML element, a JSON document or a WSDL message. */
export type RootKind = 'element' | 'json' | 'wsdl';

/** A message as the Signature tab names it: its bundle and root selector, as a structure reference has them. */
export interface MessageChoice {
  bundle: readonly [string, ...string[]];
  root: RootSelector;
}

export const rootKind = (root: RootSelector): RootKind => ('element' in root ? 'element' : 'wsdl' in root ? 'wsdl' : 'json');

/** An EQName's namespace and local name: `Q{namespace}local`, or a bare NCName without a namespace. */
function splitEQName(name: string): { namespace: string; local: string } {
  if (!name.startsWith('Q{')) return { namespace: '', local: name };
  const close = name.indexOf('}');
  return { namespace: name.slice(2, close), local: name.slice(close + 1) };
}

/**
 * A message's name, as the mapping list names a root (ADR-0029): an element's local name, a WSDL root's operation, the
 * last segment of a JSON root's first file.
 */
export function messageName({ bundle, root }: MessageChoice): string {
  if ('element' in root) return splitEQName(root.element).local;
  if ('wsdl' in root) return root.wsdl.operation;
  return bundle[0].slice(bundle[0].lastIndexOf('/') + 1);
}

/** A message's namespace: an element's, or a WSDL interface's; none for a JSON root or a name without one. */
export function messageNamespace({ root }: MessageChoice): string | undefined {
  if ('json' in root) return undefined;
  const { namespace } = splitEQName('element' in root ? root.element : root.wsdl.interface);
  return namespace === '' ? undefined : namespace;
}
