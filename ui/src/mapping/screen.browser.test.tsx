import '@patternfly/react-core/dist/styles/base.css';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { Shell } from '../Shell';
import type { ApiClient } from './api/client';
import { fakeApiClient } from './api/fake/fake-api';
import { mappingsScreen } from './screen';
import type { BrowserStateStore } from './studio/browser-state/browser-state';

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

function memoryBrowserState(): BrowserStateStore {
  const values = new Map<string, unknown>();
  return { read: (key) => values.get(key), write: (key, value) => void values.set(key, value) };
}

function render(api: ApiClient) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={[mappingsScreen(api, memoryBrowserState())]} start="mappings" />);
}

// the shell's tabs; the studio's own tabs are named Overview and Signature
const shellTab = (name: string) => page.getByRole('tab', { name, exact: true });
const shellTabs = () => page.getByRole('tab').filter({ hasNotText: /^(Overview|Definition|Test|Signature)$/ });
const row = (name: string) => page.getByRole('row').filter({ has: page.getByRole('cell', { name, exact: true }) });
// a role query finds what is shown only: the tabs not shown are hidden
const description = () => page.getByRole('textbox', { name: 'Description' });
const button = (name: string) => page.getByRole('button', { name, exact: true });
const discardDialog = () => page.getByRole('dialog', { name: 'Discard changes?' });

/** Opens the mapping `name` from the Mappings tab and waits for its studio. */
async function openMapping(name: string) {
  await shellTab('Mappings').click();
  await row(name).getByRole('button', { name: 'Open in studio' }).click();
  await expect.element(shellTab(name)).toHaveAttribute('aria-selected', 'true');
  await expect.element(page.getByRole('heading', { level: 1, name })).toBeVisible();
}

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  root?.unmount();
  host?.remove();
  root = undefined;
  // every test fails on console.error
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the Mappings screen in the shell', () => {
  test('opens each mapping in a tab of its own, several at once, each keeping its own edits', async () => {
    render(fakeApiClient());
    await openMapping('order-id-copy');
    await button('Edit').click();
    await description().fill('Copies the order id.');

    await openMapping('order-unmapped');
    await expect.element(button('Edit')).toBeVisible();
    await expect.element(description()).toHaveValue('');
    expect(shellTabs().elements()).toHaveLength(3);

    await shellTab('order-id-copy').click();
    await expect.element(description()).toHaveValue('Copies the order id.');
    await expect.element(button('Save')).toBeEnabled();

    await openMapping('order-id-copy');
    expect(shellTabs().elements()).toHaveLength(3);
  });

  test('a mapping just created opens in its tab in edit mode, on the Signature tab', async () => {
    render(fakeApiClient());
    await page.getByRole('button', { name: 'New mapping' }).click();
    await page.getByRole('dialog', { name: 'New mapping' }).getByRole('textbox', { name: 'Name' }).fill('fresh');
    await page.getByRole('button', { name: 'Create' }).click();

    await expect.element(shellTab('fresh')).toHaveAttribute('aria-selected', 'true');
    await expect.element(button('Save')).toBeDisabled();
    await expect.element(button('Cancel')).toBeVisible();
    await expect.element(shellTab('Signature')).toHaveAttribute('aria-selected', 'true');
    await expect.element(button('Add source message')).toBeEnabled();
  });

  test('closing the tab of a mapping with unsaved changes asks; Keep editing keeps it, Discard changes closes it', async () => {
    render(fakeApiClient());
    await openMapping('order-id-copy');
    await button('Edit').click();
    await description().fill('Copies the order id.');

    await page.getByRole('button', { name: 'Close order-id-copy' }).click();
    await expect.element(discardDialog()).toMatchTextContent(/Discard your unsaved changes to order-id-copy and close its tab\?/);
    await discardDialog().getByRole('button', { name: 'Keep editing' }).click();
    await expect.element(discardDialog()).not.toBeInTheDocument();
    await expect.element(description()).toHaveValue('Copies the order id.');

    await page.getByRole('button', { name: 'Close order-id-copy' }).click();
    await discardDialog().getByRole('button', { name: 'Discard changes' }).click();

    await expect.element(shellTab('order-id-copy')).not.toBeInTheDocument();
    await expect.element(shellTab('Mappings')).toHaveAttribute('aria-selected', 'true');
  });

  test('the tab of a mapping without unsaved changes closes at once', async () => {
    render(fakeApiClient());
    await openMapping('order-id-copy');

    await page.getByRole('button', { name: 'Close order-id-copy' }).click();

    await expect.element(shellTab('order-id-copy')).not.toBeInTheDocument();
    await expect.element(discardDialog()).not.toBeInTheDocument();
  });

  test('Ctrl+S saves the mapping of the tab shown and no other, and a save brings the table up to date', async () => {
    const fake = fakeApiClient();
    const saveMapping = vi.fn(fake.saveMapping);
    render({ ...fake, saveMapping });
    await openMapping('order-unmapped');
    await button('Edit').click();
    await description().fill('Not shown.');
    await openMapping('order-id-copy');
    await button('Edit').click();
    await description().fill('Copies the order id.');

    await userEvent.keyboard('{Control>}s{/Control}');

    await expect.element(page.getByRole('heading', { name: /Mapping order-id-copy saved/ })).toBeVisible();
    expect(saveMapping.mock.calls.map(([id]) => id)).toEqual(['mappings/order-id-copy']);
    await shellTab('Mappings').click();
    await expect.element(row('order-id-copy').getByRole('cell', { name: 'Copies the order id.' })).toBeVisible();
  });
});
