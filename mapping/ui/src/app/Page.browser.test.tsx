import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page } from 'vitest/browser';
import type { ApiClient } from '../api/client';
import { fakeApiClient } from '../api/fake/fake-api';
import type { BrowserStateStore } from '../studio/browser-state/browser-state';
import type { MappingModel } from '../generated/mapping-model';
import { Page } from './Page';

function memoryBrowserState(): BrowserStateStore {
  const values = new Map<string, unknown>();
  return { read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;
const path = window.location.pathname;

function render(api: ApiClient = fakeApiClient()) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Page api={api} browserState={memoryBrowserState()} />);
}

const table = () => page.getByRole('table', { name: 'Mappings' });
const modal = () => page.getByRole('dialog', { name: 'New mapping' });
const discardDialog = () => page.getByRole('dialog', { name: 'Discard changes?' });
const studioTitle = () => page.getByRole('heading', { level: 1, name: 'order-to-invoice' });
const NEW_SEARCH = '?api=fake&mapping=orders%2Forder-to-invoice';

/** A model whose source names a structure that changed since: the refresh on open leaves it with unsaved changes. */
async function staleModel(): Promise<MappingModel> {
  const corpus = (await fakeApiClient().getMapping('mappings/order-id-copy')).model;
  return { ...corpus, source: { ...corpus.source!, fingerprint: 'sha256:stale' } };
}

/** The fake, but the mapping Create makes reads back from disk as `staleModel`, so the new studio has unsaved changes. */
async function staleOnCreate(): Promise<ApiClient> {
  const fake = fakeApiClient();
  const stale = await staleModel();
  return { ...fake, getMapping: async (id) => (id === 'orders/order-to-invoice' ? { model: stale, etag: '"fake:stale"' } : fake.getMapping(id)) };
}

/** From the list, New mapping and Create `orders/order-to-invoice`: the studio opens in the same document, unsaved. */
async function createUnsaved() {
  history.replaceState(null, '', `${path}?api=fake`);
  render(await staleOnCreate());
  await page.getByRole('button', { name: 'New mapping' }).click();
  await modal().getByRole('textbox', { name: 'Name' }).fill('order-to-invoice');
  await modal().getByRole('textbox', { name: 'Package' }).fill('orders');
  await modal().getByRole('button', { name: 'Create' }).click();
  await expect.element(studioTitle()).toBeVisible();
  await expect.element(page.getByRole('button', { name: 'Save' })).toBeEnabled();
}

/** Whether the page asks before it unloads: a `beforeunload` listener cancelled the event. */
function asksBeforeUnload(): boolean {
  const event = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(event);
  return event.defaultPrevented;
}

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  root?.unmount();
  host?.remove();
  root = undefined;
  history.replaceState(null, '', path);
  // every test fails on console.error (ADR-0013)
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the page (ADR-0028)', () => {
  test('starts at the list; Open in studio leads to the mapping on Definition, keeping the page parameters', async () => {
    history.replaceState(null, '', `${path}?api=fake`);
    render();

    const row = table().getByRole('row').filter({ hasText: 'order-id-copy' });
    await expect.element(row.getByRole('link', { name: 'Open in studio' })).toHaveAttribute('href', '?api=fake&mapping=mappings%2Forder-id-copy#definition');
    await expect.element(page.getByRole('link', { name: 'Flottila' })).toHaveAttribute('href', '?api=fake');
  });

  test('list, New mapping, Create: the new mapping opens in the studio in edit mode on Signature; Back returns to the list, which has it', async () => {
    history.replaceState(null, '', `${path}?api=fake`);
    render();
    await page.getByRole('button', { name: 'New mapping' }).click();
    await modal().getByRole('textbox', { name: 'Name' }).fill('order-to-invoice');
    await modal().getByRole('textbox', { name: 'Package' }).fill('orders');

    await modal().getByRole('button', { name: 'Create' }).click();

    await expect.element(page.getByRole('heading', { level: 1, name: 'order-to-invoice' })).toBeVisible();
    await expect.element(page.getByRole('tab', { name: 'Signature' })).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('button', { name: 'Save' })).toBeDisabled();
    await expect.element(page.getByRole('link', { name: 'Flottila' })).toHaveAttribute('href', '?api=fake');
    expect(window.location.search).toBe('?api=fake&mapping=orders%2Forder-to-invoice');
    expect(window.location.hash).toBe('#signature');

    history.back();

    await expect.element(table().getByRole('row').filter({ hasText: 'order-to-invoice' })).toBeVisible();
    expect(window.location.search).toBe('?api=fake');
  });

  test('a mapping named at start opens in the studio in view mode on the hash tab', async () => {
    history.replaceState(null, '', `${path}?mapping=mappings/order-id-copy&api=fake#overview`);
    render();

    await expect.element(page.getByRole('heading', { level: 1, name: 'order-id-copy' })).toBeVisible();
    await expect.element(page.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('button', { name: 'Edit' })).toBeVisible();
    await expect.element(table()).not.toBeInTheDocument();
  });
});

describe('leaving the studio with unsaved changes (ADR-0028)', () => {
  test('Back asks first: Keep editing stays in the studio with the changes, Discard changes leaves for the list', async () => {
    await createUnsaved();

    history.back();

    await expect.element(discardDialog()).toMatchTextContent(/Discard your unsaved changes to order-to-invoice and leave the studio\?/);
    await expect.poll(() => window.location.search).toBe(NEW_SEARCH);
    await discardDialog().getByRole('button', { name: 'Keep editing' }).click();
    await expect.element(discardDialog()).not.toBeInTheDocument();
    await expect.element(page.getByRole('button', { name: 'Save' })).toBeEnabled();
    expect(window.location.search).toBe(NEW_SEARCH);

    history.back();
    await discardDialog().getByRole('button', { name: 'Discard changes' }).click();

    await expect.element(table().getByRole('row').filter({ hasText: 'order-to-invoice' })).toBeVisible();
    expect(window.location.search).toBe('?api=fake');
    await expect.element(discardDialog()).not.toBeInTheDocument();
  });

  test('a hash typed in the studio is a tab change, not leaving; leaving past such entries asks and returns to the entry it left', async () => {
    const selected = (name: string) => expect.element(page.getByRole('tab', { name })).toHaveAttribute('aria-selected', 'true');
    await createUnsaved();
    await page.getByRole('tab', { name: 'Definition' }).click();
    window.location.hash = '#test';
    await selected('Test');
    window.location.hash = '#overview';
    await selected('Overview');
    history.back();
    await selected('Test');
    await expect.element(discardDialog()).not.toBeInTheDocument();

    // two entries back at once, from a typed hash's entry
    history.go(-2);

    await expect.element(discardDialog()).toBeVisible();
    await expect.poll(() => window.location.search).toBe(NEW_SEARCH);
    expect(window.location.hash).toBe('#test');
    await discardDialog().getByRole('button', { name: 'Keep editing' }).click();

    // back through the entry whose tab was chosen, then out
    history.back();
    await selected('Definition');
    history.back();

    await expect.element(discardDialog()).toBeVisible();
    await expect.poll(() => window.location.search).toBe(NEW_SEARCH);
    expect(window.location.hash).toBe('#definition');
    await discardDialog().getByRole('button', { name: 'Discard changes' }).click();
    await expect.element(table()).toBeVisible();
    expect(window.location.search).toBe('?api=fake');
  });

  test('the brand and Open in studio load another document, so the page asks before it unloads', async () => {
    history.replaceState(null, '', `${path}?mapping=mappings/stale&api=fake`);
    render(fakeApiClient({ 'mappings/stale': await staleModel() }));
    await expect.element(page.getByRole('status').filter({ hasText: 'The structures changed' })).toBeVisible();

    // another query is another document: following the link unloads this one, and beforeunload asks
    await expect.element(page.getByRole('link', { name: 'Flottila' })).toHaveAttribute('href', '?api=fake');
    expect(asksBeforeUnload()).toBe(true);
  });
});
