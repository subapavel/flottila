import { describe, expect, test } from 'vitest';
import { choiceOf, draftOf, type ChoiceDraft } from './message-choice';
import type { MessageChoice } from '../shared/structure-names';

const ORDER = 'Q{urn:flottila:corpus:skeleton}Order';
const empty = draftOf(undefined);
const draft = (change: Partial<ChoiceDraft>): ChoiceDraft => ({ ...empty, ...change });

describe('the draft of a message choice', () => {
  test('an empty draft is an XML element without a bundle', () => {
    expect(empty).toEqual({ bundle: '', kind: 'element', element: '', wsdlInterface: '', operation: '', direction: 'input', fault: '' });
  });

  test('a chosen element, JSON or WSDL message comes back as its draft, one bundle reference per line', () => {
    const element: MessageChoice = { bundle: ['a.xsd', 'b.xsd'], root: { element: ORDER } };
    const fault: MessageChoice = { bundle: ['s.wsdl'], root: { wsdl: { interface: 'Port', operation: 'Op', direction: 'fault', fault: 'Oops' } } };
    const output: MessageChoice = { bundle: ['s.wsdl'], root: { wsdl: { interface: 'Port', operation: 'Op', direction: 'output' } } };

    expect(draftOf(element)).toEqual(draft({ bundle: 'a.xsd\nb.xsd', element: ORDER }));
    expect(draftOf({ bundle: ['i.json'], root: { json: '$' } })).toEqual(draft({ bundle: 'i.json', kind: 'json' }));
    expect(draftOf(fault)).toEqual(draft({ bundle: 's.wsdl', kind: 'wsdl', wsdlInterface: 'Port', operation: 'Op', direction: 'fault', fault: 'Oops' }));
    expect(draftOf(output).fault).toBe('');
  });

  test('a kind given replaces the chosen root kind and keeps the bundle', () => {
    expect(draftOf({ bundle: ['a.xsd'], root: { element: ORDER } }, 'json')).toMatchObject({ bundle: 'a.xsd', kind: 'json', element: ORDER });
    expect(draftOf(undefined, 'wsdl').kind).toBe('wsdl');
  });
});

describe('the message a draft names', () => {
  test('trims the bundle lines and drops the empty ones', () => {
    expect(choiceOf(draft({ bundle: ' a.xsd \n\n b.xsd', element: ` ${ORDER} ` }))).toEqual({ bundle: ['a.xsd', 'b.xsd'], root: { element: ORDER } });
  });

  test('a JSON root needs only the bundle', () => {
    expect(choiceOf(draft({ bundle: 'i.json', kind: 'json' }))).toEqual({ bundle: ['i.json'], root: { json: '$' } });
  });

  test('a WSDL root has a fault exactly when the direction is fault', () => {
    const wsdl = draft({ bundle: 's.wsdl', kind: 'wsdl', wsdlInterface: 'Port', operation: 'Op', fault: 'Oops' });

    expect(choiceOf(wsdl)?.root).toEqual({ wsdl: { interface: 'Port', operation: 'Op', direction: 'input' } });
    expect(choiceOf({ ...wsdl, direction: 'fault' })?.root).toEqual({ wsdl: { interface: 'Port', operation: 'Op', direction: 'fault', fault: 'Oops' } });
  });

  test('names none while the bundle is empty or the root lacks a name', () => {
    const wsdl = draft({ bundle: 's.wsdl', kind: 'wsdl', wsdlInterface: 'Port', operation: 'Op' });
    for (const incomplete of [
      draft({ bundle: ' \n ', kind: 'json' }),
      draft({ bundle: 'a.xsd', element: ' ' }),
      { ...wsdl, wsdlInterface: '' },
      { ...wsdl, operation: '' },
      { ...wsdl, direction: 'fault' as const },
    ]) {
      expect(choiceOf(incomplete)).toBeUndefined();
    }
  });
});
