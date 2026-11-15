import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import type { ApiClient } from '../../api/client';
import { fakeApiClient } from '../../api/fake/fake-api';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { MappingModel } from '../../generated/mapping-model';
import { Studio } from '../shell/Studio';

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

function memoryBrowserState(): BrowserStateStore {
  const values = new Map<string, unknown>();
  return { read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

function renderStudio(api: ApiClient, mappingId: string) {
  history.replaceState(null, '', '#overview');
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Studio mappingId={mappingId} api={api} browserState={memoryBrowserState()} listHref="/?api=fake" />);
}

const details = () => page.getByRole('region', { name: 'Overview' });
const description = () => page.getByRole('textbox', { name: 'Description' });
const button = (name: string) => page.getByRole('button', { name, exact: true });

/** The description list's value for `term`. */
const valueOf = (term: string) => details().getByRole('term').filter({ hasText: new RegExp(`^${term}$`) }).element().nextElementSibling;

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  root?.unmount();
  host?.remove();
  history.replaceState(null, '', window.location.pathname);
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the Overview tab (the prototype MappingOverview)', () => {
  test('shows the mapping details in our terms: Name, Mapping ID, Package, Source and Target', async () => {
    renderStudio(fakeApiClient(), 'mappings/order-id-copy');

    await expect.element(details().getByRole('heading', { name: 'Mapping details' })).toBeVisible();
    await expect.element(details().getByRole('term')).toHaveLength(5);
    expect(details().getByRole('term').elements().map((term) => term.textContent)).toEqual(['Name', 'Mapping ID', 'Package', 'Source', 'Target']);
    expect(valueOf('Name')?.textContent).toBe('order-id-copy');
    expect(valueOf('Mapping ID')?.querySelector('code')?.textContent).toBe('mappings/order-id-copy');
    expect(valueOf('Package')?.textContent).toBe('mappings');
    expect(valueOf('Source')?.textContent).toBe('Order');
    expect(valueOf('Target')?.textContent).toBe('Order');
    await expect.element(details()).not.toMatchTextContent(/Namespace|Software Component|Status/);
  });

  test('a mapping in the top folder without messages shows a dash for each, as the mapping list does', async () => {
    renderStudio(fakeApiClient({ blank: { formatVersion: 1 } }), 'blank');

    await expect.element(details().getByRole('heading', { name: 'Mapping details' })).toBeVisible();
    expect(valueOf('Package')?.textContent).toBe('—');
    expect(valueOf('Source')?.textContent).toBe('—');
    expect(valueOf('Target')?.textContent).toBe('—');
  });

  test('the description is read-only in view mode', async () => {
    const described: MappingModel = { formatVersion: 1, description: 'Copies the order id.' };
    renderStudio(fakeApiClient({ 'mappings/described': described }), 'mappings/described');

    await expect.element(description()).toHaveValue('Copies the order id.');
    await expect.element(description()).toHaveAttribute('readonly');
    await userEvent.type(description(), ' More.');
    await expect.element(description()).toHaveValue('Copies the order id.');
  });

  test('an edited description marks the mapping unsaved, and Save sends it', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.fn(fake.saveMapping);
    renderStudio({ ...fake, saveMapping }, 'mappings/order-id-copy');
    await button('Edit').click();
    await expect.element(description()).not.toHaveAttribute('readonly');
    await expect.element(button('Save')).toBeDisabled();

    await description().fill('Copies the order id.');

    await expect.element(button('Save')).toBeEnabled();
    await button('Save').click();
    await expect.element(page.getByRole('heading', { name: /Mapping order-id-copy saved/ })).toBeVisible();
    expect(saveMapping.mock.calls[0]![1].description).toBe('Copies the order id.');
    expect((await fake.getMapping('mappings/order-id-copy')).model.description).toBe('Copies the order id.');
  });
});
