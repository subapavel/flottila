import type { RootSelector } from '../../generated/mapping-model';
import { rootKind, type MessageChoice, type RootKind } from '../shared/structure-names';

export type Direction = 'input' | 'output' | 'fault';

/** What the author typed for a message (the old New mapping form's side): the bundle, one reference per line, and the root. */
export interface ChoiceDraft {
  bundle: string;
  kind: RootKind;
  element: string;
  wsdlInterface: string;
  operation: string;
  direction: Direction;
  fault: string;
}

/** The kinds of root in the order the Type and Root selects offer them, with their words. */
export const ROOT_KINDS: readonly [RootKind, string][] = [
  ['element', 'XML element'],
  ['json', 'JSON document'],
  ['wsdl', 'WSDL message'],
];

/** The draft of a message already chosen, or an empty one; `kind` replaces the chosen root's kind. */
export function draftOf(choice: MessageChoice | undefined, kind?: RootKind): ChoiceDraft {
  const root = choice?.root;
  const wsdl = root && 'wsdl' in root ? root.wsdl : undefined;
  return {
    bundle: choice ? choice.bundle.join('\n') : '',
    kind: kind ?? (root ? rootKind(root) : 'element'),
    element: root && 'element' in root ? root.element : '',
    wsdlInterface: wsdl?.interface ?? '',
    operation: wsdl?.operation ?? '',
    direction: wsdl?.direction ?? 'input',
    fault: wsdl?.fault ?? '',
  };
}

/** The root selector of a draft (GLOSSARY: Root selector), or none while a name it needs is empty. */
function rootOf(draft: ChoiceDraft): RootSelector | undefined {
  const element = draft.element.trim();
  const wsdlInterface = draft.wsdlInterface.trim();
  const operation = draft.operation.trim();
  const fault = draft.fault.trim();
  switch (draft.kind) {
    case 'element':
      return element === '' ? undefined : { element };
    case 'json':
      return { json: '$' };
    case 'wsdl':
      if (wsdlInterface === '' || operation === '' || (draft.direction === 'fault' && fault === '')) return undefined;
      // a fault is sent exactly when the direction is fault (ADR-0008)
      return { wsdl: { interface: wsdlInterface, operation, direction: draft.direction, ...(draft.direction === 'fault' ? { fault } : {}) } };
  }
}

/**
 * The message a draft names: its non-empty bundle lines, trimmed, and its root; none while the bundle is empty or the
 * root lacks a name. The service checks the rest when it loads the structure.
 */
export function choiceOf(draft: ChoiceDraft): MessageChoice | undefined {
  const bundle = draft.bundle
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '');
  const root = rootOf(draft);
  if (bundle.length === 0 || root === undefined) return undefined;
  return { bundle: bundle as [string, ...string[]], root };
}
