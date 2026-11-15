import { describe, expect, test } from 'vitest';
import { fieldPathSteps, localPath, proseSegments, stepName } from './field-path';

describe('the local path of a field path', () => {
  test('is the local names of a namespaced element path, joined by /', () => {
    expect(localPath('/Q{urn:po}PurchaseOrder/Q{urn:po}Buyer/Q{urn:common}Name')).toBe('PurchaseOrder/Buyer/Name');
  });

  test('keeps the @ of an attribute step', () => {
    expect(localPath('/Q{urn:a}Order/Item/@Q{urn:x}id')).toBe('Order/Item/@id');
  });

  test('keeps every character of a step, a literal ~1 of a JSON property included', () => {
    expect(localPath('/$/order/a~1/b~0')).toBe('$/order/a~1/b~0');
  });

  test("a slash inside an EQName's namespace does not split a step (ADR-0007)", () => {
    expect(localPath('/Q{http://a/b}Order/@Q{urn:x/y}code')).toBe('Order/@code');
  });

  test('a JSON path shows its escaped names decoded, and a path with no step shows itself', () => {
    expect(localPath('/$/order/a%2Fb')).toBe('$/order/a/b');
    expect(localPath('')).toBe('');
  });
});

describe('the steps of a field path (ADR-0007)', () => {
  test('are the texts between its slashes, an EQName with its namespace and an attribute with its @', () => {
    expect(fieldPathSteps('/Q{urn:a}Order/Item/@id')).toEqual(['Q{urn:a}Order', 'Item', '@id']);
  });

  test("a slash inside an EQName's namespace does not end a step", () => {
    expect(fieldPathSteps('/Q{http://a/b}Order/@Q{urn:x/y}code')).toEqual(['Q{http://a/b}Order', '@Q{urn:x/y}code']);
  });

  test('an escaped JSON step stays escaped, as the structure spells it', () => {
    expect(fieldPathSteps('/$/order/a%2Fb%25%40%7B%7D')).toEqual(['$', 'order', 'a%2Fb%25%40%7B%7D']);
  });

  test('a path with no step has none', () => {
    expect(fieldPathSteps('')).toEqual([]);
  });
});

describe('the name of a field path’s last step', () => {
  test.each([
    ['/Q{urn:a}Order', 'Order'],
    ['/Q{urn:a}Order/OrderId', 'OrderId'],
    ['/Q{urn:a}Order/@channel', '@channel'],
    ['/Q{http://a/b}Order/Q{http://a/b}Item', 'Item'],
    ['/Q{http://a/b}Order/@Q{http://a/c}code', '@code'],
    ['/$', '$'],
    ['/$/order/items', 'items'],
    ['/$/order/a%2Fb%25%40%7B%7D', 'a/b%@{}'],
    ['', ''],
  ])('%s is %s', (path, name) => {
    expect(stepName(path)).toBe(name);
  });

  test("is its last step's local name, @ before an attribute's, and the path itself when it has no step", () => {
    expect(stepName('/Q{urn:flottila:corpus:skeleton}Order/OrderId')).toBe('OrderId');
    expect(stepName('/Q{http://a/b}Order/@Q{urn:x}code')).toBe('@code');
    expect(stepName('')).toBe('');
  });

  test('a single step reads as a path of that step', () => {
    expect(stepName('a%2Fb')).toBe('a/b');
    expect(stepName('@Q{urn:x}code')).toBe('@code');
  });

  test('a JSON property spelled like an attribute or an EQName is shown as its name, not read as one', () => {
    expect(stepName('/$/%40id')).toBe('@id');
    expect(stepName('/$/Q%7Burn:a%7Dx')).toBe('Q{urn:a}x');
  });

  test('only the five escapes the service writes are decoded, once, and a stray % in a hand-written model is shown as it is', () => {
    expect(stepName('/$/a%2541')).toBe('a%41');
    expect(stepName('/$/a%41')).toBe('a%41');
    expect(stepName('/$/50%')).toBe('50%');
    expect(stepName('/$/a%2f')).toBe('a%2f');
  });
});

describe('the field paths found in a message', () => {
  const PO = '/Q{urn:flottila:corpus:gate:po}PurchaseOrder/Q{urn:flottila:corpus:gate:po}Buyer/Q{urn:flottila:corpus:gate:common}Name';

  test('a path ends at whitespace or a parenthesis', () => {
    expect(proseSegments(`the target leaf ${PO} (1..1) has no target field mapping`)).toEqual([
      { text: 'the target leaf ' },
      { text: 'PurchaseOrder/Buyer/Name', path: PO },
      { text: ' (1..1) has no target field mapping' },
    ]);
    expect(proseSegments(`leaf ${PO}(1..1)`)).toEqual([{ text: 'leaf ' }, { text: 'PurchaseOrder/Buyer/Name', path: PO }, { text: '(1..1)' }]);
  });

  test('an attribute step keeps its @', () => {
    const path = '/Q{urn:a}Order/Q{urn:a}Item/@Q{urn:x}id';
    expect(proseSegments(`bad ${path} here`)).toEqual([{ text: 'bad ' }, { text: 'Order/Item/@id', path }, { text: ' here' }]);
    expect(proseSegments('bad /Q{urn:a}Order/@no')).toEqual([{ text: 'bad ' }, { text: 'Order/@no', path: '/Q{urn:a}Order/@no' }]);
  });

  test("a slash inside an EQName's namespace does not end the path (ADR-0007)", () => {
    const path = '/Q{http://a/b}Order/@Q{urn:x/y}code';
    expect(proseSegments(`at ${path} now`)).toEqual([{ text: 'at ' }, { text: 'Order/@code', path }, { text: ' now' }]);
  });

  test('two paths in one message', () => {
    expect(proseSegments('/Q{urn:a}A/Q{urn:a}B is not /Q{urn:a}C')).toEqual([
      { text: 'A/B', path: '/Q{urn:a}A/Q{urn:a}B' },
      { text: ' is not ' },
      { text: 'C', path: '/Q{urn:a}C' },
    ]);
  });

  test('a $-rooted path shows its root', () => {
    expect(proseSegments('field /$/order/items missing')).toEqual([
      { text: 'field ' },
      { text: '$/order/items', path: '/$/order/items' },
      { text: ' missing' },
    ]);
  });

  test('a message with no path stays as it is, a slash inside a word included', () => {
    expect(proseSegments('the input and/or output is missing')).toEqual([{ text: 'the input and/or output is missing' }]);
    expect(proseSegments('')).toEqual([]);
  });
});
