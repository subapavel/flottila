import { describe, expect, test } from 'vitest';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { createTestPreview, HEADER_DEFAULTS, testPreviewKey } from './test-preview';

function memoryBrowserState(seed: Record<string, unknown> = {}): BrowserStateStore & { values: Map<string, unknown> } {
  const values = new Map(Object.entries(seed));
  return { values, read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

const KEY = testPreviewKey('m/a');

describe('the Test tab previews (#171, #167)', () => {
  test('start at Warning with no values, and keep each change in the browser under the mapping id', () => {
    const browserState = memoryBrowserState();
    const preview = createTestPreview(browserState, 'm/a');
    expect(preview.getState()).toMatchObject({ traceLevel: 'Warning', values: {} });

    preview.getState().setTraceLevel('Debug');
    preview.getState().setValue('header:SenderName', 'Acme');

    expect(browserState.values.get(KEY)).toEqual({ traceLevel: 'Debug', values: { 'header:SenderName': 'Acme' } });
    expect(createTestPreview(browserState, 'm/a').getState()).toMatchObject({ traceLevel: 'Debug', values: { 'header:SenderName': 'Acme' } });
  });

  test.each([
    ['nothing', undefined],
    ['an unknown level', { traceLevel: 'Verbose', values: {} }],
    ['no values', { traceLevel: 'Info' }],
    ['null values', { traceLevel: 'Info', values: null }],
    ['a value that is no text', { traceLevel: 'Info', values: { a: 1 } }],
  ])('kept state with %s starts afresh', (_, kept) => {
    expect(createTestPreview(memoryBrowserState({ [KEY]: kept }), 'm/a').getState()).toMatchObject({ traceLevel: 'Warning', values: {} });
  });

  test("the header parameters carry the prototype's defaults", () => {
    expect(HEADER_DEFAULTS).toMatchObject({ Profiling: 'off', SenderParty: 'Test_SenderParty', ReceiverSystem: 'Test_ReceiverSystem', MessageId: '' });
    expect(Object.keys(HEADER_DEFAULTS)).toHaveLength(24);
  });
});
