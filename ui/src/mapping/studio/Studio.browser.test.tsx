import '@patternfly/react-core/dist/styles/base.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { ApiProblem, type ApiClient } from '../api/client';
import { fakeApiClient } from '../api/fake/fake-api';
import { selectUnsaved } from './store/working-copy';
import type { MappingModel } from '../generated/mapping-model';
import { Studio, studioStore } from './Studio';
import { StudioFrame } from './StudioFrame';

const ORDER = 'Q{urn:rail8:corpus:skeleton}Order';
const ORDER_ID = `/${ORDER}/OrderId`;
const NOTE = `/${ORDER}/Note`;

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

function render(node: ReactNode) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(node);
}

function unmount() {
  root?.unmount();
  host?.remove();
  root = undefined;
}

function renderStudio(api: ApiClient, mappingId: string, options: { startEditing?: boolean } = {}) {
  render(
    <Studio
      mappingId={mappingId}
      api={api}
      startEditing={options.startEditing}
    />,
  );
}

/** The frame over a store the test holds, wired to `api` as the studio wires it, so a test can edit through the store. */
async function renderFrame(api: ApiClient, mappingId: string) {
  const store = studioStore(api, mappingId, await api.getMapping(mappingId));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <StudioFrame mappingId={mappingId} api={api} store={store} />
    </QueryClientProvider>,
  );
  return store;
}

/** Whether the page asks before it unloads: a `beforeunload` listener cancelled the event. */
function asksBeforeUnload(): boolean {
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

const tab = (name: string) => page.getByRole('tab', { name });
const button = (name: string) => page.getByRole('button', { name, exact: true });
const discardDialog = () => page.getByRole('dialog', { name: 'Discard changes?' });

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  unmount();
  // every test fails on console.error
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the studio', () => {
  test('opens a mapping in view mode, on the Overview tab, with its name and Edit', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy');

    await expect.element(page.getByRole('heading', { level: 1, name: 'order-id-copy' })).toBeVisible();
    await expect.element(page.getByRole('tablist', { name: 'Editor views' }).getByRole('tab')).toHaveLength(1);
    await expect.element(tab('Overview')).toHaveAttribute('aria-selected', 'true');
    await expect.element(button('Edit')).toBeVisible();
    await expect.element(button('Save')).not.toBeInTheDocument();
    await expect.element(button('Cancel')).not.toBeInTheDocument();
  });

  test('shows the opening mapping and a mapping the service refuses with its problem and Retry', async () => {
    const fake = fakeApiClient();
    let answer: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const refused = new ApiProblem({ type: 'urn:rail8:mapping:problem:not-found', title: 'Not found', status: 404, detail: 'no mapping mappings/gone' });
    const getMapping = vi.fn(() => answered.then(() => Promise.reject(refused)));
    renderStudio({ ...fake, getMapping }, 'mappings/gone');

    await expect.element(page.getByText('Opening mappings/gone…')).toBeVisible();
    answer();
    await expect.element(page.getByRole('heading', { name: /Not found/ })).toBeVisible();
    await expect.element(page.getByText('no mapping mappings/gone')).toBeVisible();
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect.poll(() => getMapping.mock.calls.length).toBe(2);
  });

  test('a start in edit mode opens with Cancel and a disabled Save', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy', { startEditing: true });

    await expect.element(button('Save')).toBeDisabled();
    await expect.element(button('Cancel')).toBeVisible();
    await expect.element(button('Edit')).not.toBeInTheDocument();
  });

  test('Edit switches to edit mode', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy');

    await button('Edit').click();

    await expect.element(button('Save')).toBeDisabled();
    await expect.element(button('Cancel')).toBeVisible();
  });
});

describe('Check', () => {
  const checkBanner = () => page.getByRole('status').filter({ has: page.getByRole('heading', { name: /Check (passed|found issues)/ }) });
  const warning = (code: string, message: string, severity: 'error' | 'warning' = 'warning') => ({
    severity,
    code,
    message,
    location: { kind: 'pointer' as const, pointer: '/x' },
  });

  test('is shown in edit mode only and enabled only while the working copy has unsaved changes', async () => {
    const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
    await expect.element(button('Check')).not.toBeInTheDocument();
    await button('Edit').click();
    await expect.element(button('Check')).toBeDisabled();

    store.getState().link(ORDER_ID, ORDER_ID);

    await expect.element(button('Check')).toBeEnabled();
  });

  test('a clean working copy passes: the ok banner, until it is dismissed', async () => {
    const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);

    await button('Check').click();

    await expect.element(button('Checking…')).toBeDisabled();
    await expect.element(checkBanner().getByRole('heading', { name: /Check passed/ })).toBeVisible();
    await expect.element(checkBanner().getByText('Structures assigned and graph connections complete.')).toBeVisible();
    await expect.element(checkBanner()).toHaveClass(/pf-m-success/);
    await expect.element(button('Check')).toBeEnabled();
    await page.getByRole('button', { name: 'Dismiss check' }).click();
    await expect.element(checkBanner()).not.toBeInTheDocument();
  });

  test('lists the missing structures in our words and each error and warning of a fresh compile', async () => {
    const compile = { 'mappings/blank': [warning('missing-structure', 'no source'), warning('unknown-function', 'no such function', 'error'), warning('unreachable-box', 'box b1 is unreachable')] };
    const store = await renderFrame(fakeApiClient({ 'mappings/blank': { formatVersion: 1 } }, { compile }), 'mappings/blank');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);

    await button('Check').click();

    await expect.element(checkBanner()).toHaveClass(/pf-m-warning/);
    await expect.element(checkBanner().getByRole('heading', { name: /Check found issues/ })).toBeVisible();
    const items = checkBanner().getByRole('listitem');
    await expect.element(items).toHaveLength(4);
    for (const [i, text] of [
      'Choose a source structure.',
      'Choose a target structure.',
      'Error: no such function',
      'Warning: box b1 is unreachable',
    ].entries()) {
      await expect.element(items.nth(i)).toHaveTextContent(text);
    }
  });

  test('a mapping with a source but no target asks for the target alone', async () => {
    const source = (await fakeApiClient().getMapping('mappings/order-id-copy')).model.source;
    const store = await renderFrame(fakeApiClient({ 'mappings/half': { formatVersion: 1, source } }), 'mappings/half');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);

    await button('Check').click();

    await expect.element(checkBanner().getByRole('listitem')).toHaveLength(1);
    await expect.element(checkBanner().getByRole('listitem')).toHaveTextContent('Choose a target structure.');
  });

  test('a compile that fails is an issue in itself, without the stale diagnostics', async () => {
    const fake = fakeApiClient({ 'mappings/blank': { formatVersion: 1 } }, { compile: { 'mappings/blank': [warning('unreachable-box', 'stale')] } });
    let fail = false;
    const compile: ApiClient['compile'] = (...args) =>
      fail ? Promise.reject(new ApiProblem({ type: 'about:blank', title: 'Bad Gateway', status: 502, detail: '' })) : fake.compile(...args);
    const store = await renderFrame({ ...fake, compile }, 'mappings/blank');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);
    await expect.poll(() => store.getState().diagnostics).toHaveLength(1);
    fail = true;

    await button('Check').click();

    const items = checkBanner().getByRole('listitem');
    await expect.element(items.nth(2)).toHaveTextContent('The mapping could not be compiled: Bad Gateway');
    await expect.element(items).toHaveLength(3);
  });

  test('an edit clears the result, and an edit while it checks ends the check with no result', async () => {
    const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);
    await button('Check').click();
    await expect.element(checkBanner()).toBeVisible();

    store.getState().link(NOTE, NOTE);
    await expect.element(checkBanner()).not.toBeInTheDocument();

    await button('Check').click();
    await expect.element(button('Checking…')).toBeVisible();
    store.getState().undo();
    await expect.element(button('Check')).toBeEnabled();
    const answered = store.getState().compiles;
    await expect.poll(() => store.getState().compiles).toBeGreaterThan(answered);
    await expect.element(checkBanner()).not.toBeInTheDocument();
  });

  test('Cancel clears the result', async () => {
    const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);
    await button('Check').click();
    await expect.element(checkBanner()).toBeVisible();

    await button('Cancel').click();
    await discardDialog().getByRole('button', { name: 'Discard changes' }).click();

    await expect.element(checkBanner()).not.toBeInTheDocument();
  });

  test('F7 checks in edit mode with unsaved changes, and does nothing otherwise', async () => {
    const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
    await expect.element(button('Edit')).toBeVisible();
    await userEvent.keyboard('{F7}');
    await button('Edit').click();
    await userEvent.keyboard('{F7}');
    // a check under way would have renamed the button Checking…
    await expect.element(button('Check')).toBeDisabled();
    await expect.element(checkBanner()).not.toBeInTheDocument();
    store.getState().link(ORDER_ID, ORDER_ID);
    await expect.element(button('Check')).toBeEnabled();

    await userEvent.keyboard('{F7}');

    await expect.element(checkBanner().getByRole('heading', { name: /Check passed/ })).toBeVisible();
  });

  test('F7 and Ctrl+S do nothing in view mode, even with the unsaved changes a refresh leaves; Check clears the notice', async () => {
    const corpus = (await fakeApiClient().getMapping('mappings/order-id-copy')).model;
    const stale: MappingModel = { ...corpus, source: { ...corpus.source!, fingerprint: 'sha256:stale' } };
    const fake = fakeApiClient({ 'mappings/stale': stale });
    const saveMapping = vi.fn(fake.saveMapping);
    const store = await renderFrame({ ...fake, saveMapping }, 'mappings/stale');
    const notice = page.getByRole('status').filter({ hasText: 'The structures changed' });
    await expect.element(notice).toBeVisible();
    expect(selectUnsaved(store.getState())).toBe(true);

    await userEvent.keyboard('{F7}');
    await userEvent.keyboard('{Control>}s{/Control}');
    await button('Edit').click();

    await expect.element(notice).toBeVisible();
    expect(saveMapping).not.toHaveBeenCalled();
    await button('Check').click();
    await expect.element(notice).not.toBeInTheDocument();
    await expect.element(checkBanner()).toBeVisible();
  });

  test('a store change that leaves the working copy alone does not end the check', async () => {
    const fake = fakeApiClient();
    let answer: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const compile = vi.fn((...args: Parameters<ApiClient['compile']>) => answered.then(() => fake.compile(...args)));
    const store = await renderFrame({ ...fake, compile }, 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);
    await button('Check').click();
    await expect.poll(() => compile.mock.calls.length).toBeGreaterThan(0);

    store.getState().startEditing();

    await expect.element(button('Checking…')).toBeDisabled();
    await expect.element(checkBanner()).not.toBeInTheDocument();
    answer();
    await expect.element(checkBanner().getByRole('heading', { name: /Check passed/ })).toBeVisible();
  });

  test('the copy button copies the result as text', async () => {
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    try {
      const store = await renderFrame(fakeApiClient({ 'mappings/blank': { formatVersion: 1 } }), 'mappings/blank');
      await button('Edit').click();
      store.getState().link(ORDER_ID, ORDER_ID);
      await button('Check').click();
      const copy = page.getByRole('button', { name: 'Copy check result to clipboard' });

      await copy.click();

      await expect.poll(() => writeText.mock.calls).toEqual([['Check found issues\nChoose a source structure.\nChoose a target structure.']]);
      await expect.element(checkBanner().getByText('Copied!')).toBeInTheDocument();
    } finally {
      writeText.mockRestore();
    }
  });

  test('a passed check copies its sentence; a copy the browser refuses says how to copy by hand', async () => {
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'));
    try {
      const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
      await button('Edit').click();
      store.getState().link(ORDER_ID, ORDER_ID);
      await button('Check').click();

      await page.getByRole('button', { name: 'Copy check result to clipboard' }).click();

      await expect.element(checkBanner().getByText('Could not copy. Select the text and copy it manually.')).toBeInTheDocument();
      expect(writeText.mock.calls).toEqual([['Check passed · Structures assigned and graph connections complete.']]);
    } finally {
      writeText.mockRestore();
    }
  });

  test('the copy receipt goes after 2.4 s', async () => {
    const original = window.setTimeout;
    const timers = vi.spyOn(window, 'setTimeout').mockImplementation(((fn: TimerHandler, delay?: number, ...rest: unknown[]) =>
      original(fn, delay === 2400 ? 200 : delay, ...rest)) as typeof window.setTimeout);
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    try {
      const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
      await button('Edit').click();
      store.getState().link(ORDER_ID, ORDER_ID);
      await button('Check').click();
      await page.getByRole('button', { name: 'Copy check result to clipboard' }).click();
      await expect.element(checkBanner().getByText('Copied!')).toBeInTheDocument();

      await expect.element(checkBanner().getByText('Copied!')).not.toBeInTheDocument();
    } finally {
      writeText.mockRestore();
      timers.mockRestore();
    }
  });

  test('the copy tooltip shows on hover, on focus and with a receipt; Escape hides it and the receipt', async () => {
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    try {
      const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
      await button('Edit').click();
      store.getState().link(ORDER_ID, ORDER_ID);
      await button('Check').click();
      const copy = page.getByRole('button', { name: 'Copy check result to clipboard' });
      const tooltip = page.getByRole('tooltip');
      const away = page.getByRole('heading', { level: 1 });

      await copy.hover();
      await expect.element(tooltip).toHaveTextContent('Copy to clipboard');
      await away.hover();
      await expect.element(tooltip).not.toBeInTheDocument();
      (copy.element() as HTMLElement).focus();
      await expect.element(tooltip).toHaveTextContent('Copy to clipboard');
      (copy.element() as HTMLElement).blur();
      await expect.element(tooltip).not.toBeInTheDocument();

      await copy.click();
      await away.hover();
      (copy.element() as HTMLElement).blur();
      await expect.element(tooltip).toHaveTextContent('Copied!');
      (copy.element() as HTMLElement).focus();
      await userEvent.keyboard('{Shift}');
      await expect.element(tooltip).toHaveTextContent('Copied!');
      await userEvent.keyboard('{Escape}');
      await expect.element(tooltip).not.toBeInTheDocument();
      await expect.element(checkBanner().getByText('Copied!')).not.toBeInTheDocument();
    } finally {
      writeText.mockRestore();
    }
  });
});

describe('Ctrl+S and Cmd+S', () => {
  test('save as Save does, in edit mode with unsaved changes', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.fn(fake.saveMapping);
    const store = await renderFrame({ ...fake, saveMapping }, 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);
    await expect.element(button('Save')).toBeEnabled();

    await userEvent.keyboard('{Control>}s{/Control}');

    await expect.element(page.getByRole('heading', { name: /Mapping order-unmapped saved/ })).toBeVisible();
    store.getState().link(NOTE, NOTE);
    await expect.element(button('Save')).toBeEnabled();
    await userEvent.keyboard('{Meta>}S{/Meta}');
    await expect.element(button('Save')).toBeDisabled();
    expect(saveMapping).toHaveBeenCalledTimes(2);
  });

  test('do nothing in view mode, without unsaved changes, or while a dialog is open', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.fn(fake.saveMapping);
    const store = await renderFrame({ ...fake, saveMapping }, 'mappings/order-unmapped');
    await expect.element(button('Edit')).toBeVisible();
    await userEvent.keyboard('{Control>}s{/Control}');
    await button('Edit').click();
    await userEvent.keyboard('{Control>}s{/Control}');
    store.getState().link(ORDER_ID, ORDER_ID);
    await button('Cancel').click();
    await expect.element(discardDialog()).toBeVisible();

    await userEvent.keyboard('{Control>}s{/Control}');

    await discardDialog().getByRole('button', { name: 'Keep editing' }).click();
    await expect.element(button('Save')).toBeEnabled();
    expect(saveMapping).not.toHaveBeenCalled();
  });

  test('a second Ctrl+S while the service has not answered sends nothing more', async () => {
    const fake = fakeApiClient();
    let answer: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const saveMapping = vi.fn((...args: Parameters<ApiClient['saveMapping']>) => answered.then(() => fake.saveMapping(...args)));
    const store = await renderFrame({ ...fake, saveMapping }, 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);
    await userEvent.keyboard('{Control>}s{/Control}');
    await expect.element(button('Save')).toBeDisabled();

    await userEvent.keyboard('{Control>}s{/Control}');

    answer();
    await expect.element(page.getByRole('heading', { name: /Mapping order-unmapped saved/ })).toBeVisible();
    expect(saveMapping).toHaveBeenCalledTimes(1);
  });
});

describe('the help', () => {
  test('opens About Mapping studio with the interaction help for our product', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy');

    await page.getByRole('button', { name: 'About Mapping studio' }).click();

    const about = page.getByRole('dialog', { name: 'About Mapping studio' });
    await expect.element(about).toMatchTextContent(/^About Mapping studioOpen a mapping from its Rail8 package\. Choose Edit to make changes\./);
    await expect.element(about).toMatchTextContent(/Drag a source field onto a target for a direct connection\./);
    await expect.element(about).toMatchTextContent(/Lay Out Mapping arranges the expression from left to right at its fixed scale\./);
    await expect.element(about).toMatchTextContent(/Save writes the mapping file to the workspace\. Test runs the mapping on the service\.$/);
    await expect.element(about).not.toMatchTextContent(/browser|SAP PO|prototype/);
    await about.getByRole('button', { name: 'Close' }).click();
    await expect.element(about).not.toBeInTheDocument();
  });
});

describe('a mapping with neither source nor target', () => {
  const blank = (): Record<string, MappingModel> => ({ 'mappings/blank': { formatVersion: 1 } });

  test('opens and refreshes without an error', async () => {
    const fake = fakeApiClient(blank());
    const refresh = vi.fn(fake.refresh);
    renderStudio({ ...fake, refresh }, 'mappings/blank');

    await expect.element(page.getByRole('heading', { level: 1, name: 'blank' })).toBeVisible();
    await expect.poll(() => refresh.mock.calls.length).toBe(1);
  });

  test('saves after an edit, and Save shows that it saved', async () => {
    const fake = fakeApiClient(blank());
    const store = await renderFrame(fake, 'mappings/blank');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);

    await button('Save').click();

    await expect.element(page.getByRole('heading', { name: /Mapping blank saved/ })).toBeVisible();
    expect((await fake.getMapping('mappings/blank')).model.targetFieldMappings).toHaveProperty([ORDER_ID]);
  });
});

describe('refresh on open', () => {
  test('a changed fingerprint shows how many references it orphaned, until the notice is dismissed', async () => {
    const corpus = (await fakeApiClient().getMapping('mappings/order-id-copy')).model;
    const stale: MappingModel = { ...corpus, source: { ...corpus.source!, fingerprint: 'sha256:stale' } };
    const orphan = { severity: 'error' as const, code: 'orphaned-reference', message: 'gone', location: { kind: 'authoring-id' as const, authoringId: 'x' } };
    renderStudio(fakeApiClient({ 'mappings/stale': stale }, { refresh: { 'mappings/stale': [orphan] } }), 'mappings/stale');

    const notice = page.getByRole('status').filter({ hasText: 'The structures changed' });
    await expect.element(notice.getByRole('heading', { name: /The structures changed\. Orphaned references: 1$/ })).toBeVisible();

    await page.getByRole('button', { name: 'Dismiss message' }).click();

    await expect.element(notice).not.toBeInTheDocument();
  });

  test('an answer that comes after the studio closed changes nothing', async () => {
    const corpus = (await fakeApiClient().getMapping('mappings/order-id-copy')).model;
    const stale: MappingModel = { ...corpus, source: { ...corpus.source!, fingerprint: 'sha256:stale' } };
    const fake = fakeApiClient({ 'mappings/stale': stale });
    let answer: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const refresh = vi.fn((id: string, model: MappingModel) => answered.then(() => fake.refresh(id, model)));
    const store = await renderFrame({ ...fake, refresh }, 'mappings/stale');
    await expect.poll(() => refresh.mock.calls.length).toBe(1);
    unmount();

    answer();
    await refresh.mock.results[0]!.value;

    expect(store.getState().past).toEqual([]);
    expect(store.getState().workingCopy.source?.fingerprint).toBe('sha256:stale');
  });

  test('a refresh that fails says so', async () => {
    const problem = new ApiProblem({ type: 'about:blank', title: 'Service Unavailable', status: 503, detail: 'offline' });
    renderStudio({ ...fakeApiClient(), refresh: () => Promise.reject(problem) }, 'mappings/order-id-copy');

    await expect.element(page.getByRole('status').getByRole('heading', { name: /The structures could not be refreshed: Service Unavailable$/ })).toBeVisible();
  });
});

describe('Edit, Save and Discard', () => {
  test('Cancel after a change asks; Keep editing keeps the change, Discard changes restores the baseline in view mode', async () => {
    const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);
    await expect.element(button('Save')).toBeEnabled();

    await button('Cancel').click();
    await expect.element(discardDialog()).toMatchTextContent(/Discard your unsaved changes to order-unmapped and return to view mode\?/);
    await discardDialog().getByRole('button', { name: 'Keep editing' }).click();
    await expect.element(discardDialog()).not.toBeInTheDocument();
    expect(store.getState().workingCopy.targetFieldMappings).toHaveProperty([ORDER_ID]);

    await button('Cancel').click();
    await discardDialog().getByRole('button', { name: 'Discard changes' }).click();

    await expect.element(discardDialog()).not.toBeInTheDocument();
    await expect.element(button('Edit')).toHaveFocus();
    expect(store.getState().workingCopy).toBe(store.getState().baseline);
    expect(store.getState().past).toEqual([]);
  });

  test('Cancel without a change returns to view mode without asking', async () => {
    await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
    await button('Edit').click();

    await button('Cancel').click();

    await expect.element(button('Edit')).toBeVisible();
    await expect.element(discardDialog()).not.toBeInTheDocument();
  });

  test('Save sends the working copy with If-Match, takes the canonical model as the baseline and stays in edit mode', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.fn(fake.saveMapping);
    const { etag } = await fake.getMapping('mappings/order-unmapped');
    const store = await renderFrame({ ...fake, saveMapping }, 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);

    await button('Save').click();

    await expect.element(page.getByRole('heading', { name: /Mapping order-unmapped saved/ })).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
    await expect.element(button('Cancel')).toBeVisible();
    expect(saveMapping.mock.calls[0]![2]).toBe(etag);
    expect(store.getState().baseline.targetFieldMappings).toHaveProperty([ORDER_ID]);

    await page.getByRole('button', { name: 'Dismiss save notification' }).click();
    await expect.element(page.getByRole('heading', { name: /saved/ })).not.toBeInTheDocument();
  });

  test('the saved notice goes by itself after 8 s, and the next save shows it again', async () => {
    const original = window.setTimeout;
    // PatternFly's alert timeout: the test runs it after 300 ms
    const timers = vi.spyOn(window, 'setTimeout').mockImplementation(((fn: TimerHandler, delay?: number, ...rest: unknown[]) =>
      original(fn, delay === 8000 ? 300 : delay, ...rest)) as typeof window.setTimeout);
    try {
      const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
      await button('Edit').click();
      store.getState().link(ORDER_ID, ORDER_ID);
      await button('Save').click();
      await expect.element(page.getByRole('heading', { name: /Mapping order-unmapped saved/ })).toBeVisible();
      await expect.element(page.getByRole('heading', { name: /saved/ })).not.toBeInTheDocument();

      store.getState().link(NOTE, NOTE);
      await button('Save').click();

      await expect.element(page.getByRole('heading', { name: /Mapping order-unmapped saved/ })).toBeVisible();
      expect(timers.mock.calls.filter(([, delay]) => delay === 8000)).toHaveLength(2);
    } finally {
      timers.mockRestore();
    }
  });

  test('Save is disabled while the service has not answered', async () => {
    const fake = fakeApiClient();
    let answer: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const saveMapping = vi.fn((...args: Parameters<ApiClient['saveMapping']>) => answered.then(() => fake.saveMapping(...args)));
    const store = await renderFrame({ ...fake, saveMapping }, 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);

    await button('Save').click();

    await expect.element(button('Save')).toBeDisabled();
    answer();
    await expect.element(page.getByRole('heading', { name: /Mapping order-unmapped saved/ })).toBeVisible();
    expect(saveMapping).toHaveBeenCalledTimes(1);
  });

  test('Save of a file changed on disk shows the save conflict and no saved notice', async () => {
    const fake = fakeApiClient();
    const store = await renderFrame(fake, 'mappings/order-unmapped');
    // another writer changes the file after it was opened
    const stored = await fake.getMapping('mappings/order-unmapped');
    await fake.saveMapping('mappings/order-unmapped', stored.model, stored.etag);
    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);

    await button('Save').click();

    await expect.element(page.getByRole('dialog', { name: 'Save conflict' })).toBeVisible();
    await expect.element(page.getByRole('heading', { name: /saved/ })).not.toBeInTheDocument();
    await page.getByRole('button', { name: 'Keep editing' }).click();
    await expect.element(button('Save')).toBeEnabled();
  });

  test('a save the service refuses shows the problem and its diagnostics', async () => {
    const refused = new ApiProblem({
      type: 'urn:rail8:mapping:problem:model-refused',
      title: 'Model refused',
      status: 422,
      detail: 'the mapping model is refused by validation stage 1 or 2',
      diagnostics: [{ severity: 'error', code: 'unknown-function', message: 'no such function', location: { kind: 'pointer', pointer: '/x' } }],
    });
    const store = await renderFrame({ ...fakeApiClient(), saveMapping: () => Promise.reject(refused) }, 'mappings/order-unmapped');
    await button('Edit').click();
    store.getState().link(NOTE, NOTE);

    await button('Save').click();

    await expect.element(page.getByRole('heading', { name: /Model refused/ })).toBeVisible();
    await expect.element(page.getByRole('list', { name: 'Save diagnostics' })).toHaveTextContent('error unknown-function: no such function');
    await expect.element(page.getByRole('heading', { name: /saved/ })).not.toBeInTheDocument();
  });

  test('the page asks before unloading only while the working copy has unsaved changes', async () => {
    const store = await renderFrame(fakeApiClient(), 'mappings/order-unmapped');
    await expect.element(button('Edit')).toBeVisible();
    expect(asksBeforeUnload()).toBe(false);

    await button('Edit').click();
    store.getState().link(ORDER_ID, ORDER_ID);
    await expect.element(button('Save')).toBeEnabled();
    expect(asksBeforeUnload()).toBe(true);

    await button('Cancel').click();
    await discardDialog().getByRole('button', { name: 'Discard changes' }).click();
    await expect.element(button('Edit')).toBeVisible();
    expect(asksBeforeUnload()).toBe(false);
  });
});
