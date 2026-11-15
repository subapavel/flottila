import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { ApiProblem, type ApiClient, type MappingSummary } from '../../api/client';
import { fakeApiClient } from '../../api/fake/fake-api';
import { MappingList } from './MappingList';

const LIST = '?api=fake';
const studioHref = (id: string) => `?mapping=${id}#definition`;

let root: Root | undefined;
let host: HTMLElement | undefined;
let consoleError: ReturnType<typeof vi.spyOn>;

function render(api: ApiClient, onCreated: (id: string) => void = () => undefined) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  root.render(
    <QueryClientProvider client={queryClient}>
      <MappingList api={api} studioHref={studioHref} listHref={LIST} onCreated={onCreated} />
    </QueryClientProvider>,
  );
}

/** The fake with a spy on createMapping, so a test can read the request the modal sent. */
function spied(api: ApiClient = fakeApiClient()) {
  const createMapping = vi.fn(api.createMapping);
  return { api: { ...api, createMapping }, createMapping };
}

const table = () => page.getByRole('table', { name: 'Mappings' });
/** The table's body rows: every row but the header row. */
const rows = () => table().getByRole('row').filter({ hasNot: page.getByRole('columnheader') });
const search = () => page.getByRole('textbox', { name: 'Find mappings' });
const modal = () => page.getByRole('dialog', { name: 'New mapping' });
const field = (name: string) => modal().getByRole('textbox', { name });
/** A field's helper text; PatternFly appends ": error status;" for screen readers to an error. */
const helper = (text: string) => modal().getByText(new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(: error status;)?$`));

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  root?.unmount();
  host?.remove();
  root = undefined;
  // every test fails on console.error (ADR-0013)
  expect(consoleError).not.toHaveBeenCalled();
  consoleError.mockRestore();
});

describe('the start page (ADR-0028)', () => {
  test('is the masthead over a table of the mappings with Name, Package, Source, Target and Description, each with Open in studio', async () => {
    render(fakeApiClient());

    await expect.element(page.getByRole('banner')).toHaveTextContent('FlottilaMapping studio');
    await expect.element(page.getByRole('heading', { level: 1, name: 'Mappings' })).toBeVisible();
    const headers = table().getByRole('columnheader');
    await expect.element(headers).toHaveLength(6);
    for (const [i, name] of ['Name', 'Package', 'Source', 'Target', 'Description', 'Actions'].entries()) {
      await expect.element(headers.nth(i)).toHaveTextContent(name);
    }
    await expect.element(rows()).toHaveLength(3);
    const row = rows().nth(0);
    for (const [i, text] of ['order-id-copy', 'mappings', 'Order', 'Order', '—', 'Open in studio'].entries()) {
      await expect.element(row.getByRole('cell').nth(i)).toHaveTextContent(text);
    }
    await expect.element(row.getByRole('link', { name: 'Open in studio' })).toHaveAttribute('href', '?mapping=mappings/order-id-copy#definition');
  });

  test('a mapping without package, messages or description shows a dash for each, and its description when it has one', async () => {
    const listMappings = async (): Promise<MappingSummary[]> => [
      { id: 'top', name: 'top', package: '' },
      { id: 'a/b', name: 'b', package: 'a', description: 'Orders to invoices', source: 'Order', target: 'Invoice' },
    ];
    render({ ...fakeApiClient(), listMappings });

    await expect.element(rows()).toHaveLength(2);
    for (const [i, text] of ['top', '—', '—', '—', '—'].entries()) await expect.element(rows().nth(0).getByRole('cell').nth(i)).toHaveTextContent(text);
    for (const [i, text] of ['b', 'a', 'Order', 'Invoice', 'Orders to invoices'].entries()) {
      await expect.element(rows().nth(1).getByRole('cell').nth(i)).toHaveTextContent(text);
    }
  });

  test('the search keeps the rows whose name, package, messages or description contain it, ignoring case', async () => {
    const listMappings = async (): Promise<MappingSummary[]> => [
      { id: 'orders/in', name: 'in', package: 'orders' },
      { id: 'x/invoice', name: 'invoice', package: 'x', source: 'Bill' },
      { id: 'y/z', name: 'z', package: 'y', target: 'BILLING' },
      { id: 'w/v', name: 'v', package: 'w', description: 'Pays a bill' },
    ];
    render({ ...fakeApiClient(), listMappings });
    await expect.element(rows()).toHaveLength(4);

    await search().fill('bill');
    await expect.element(rows()).toHaveLength(3);
    await search().fill('ORDERS');
    await expect.element(rows()).toHaveLength(1);
    await expect.element(rows().nth(0)).toMatchTextContent(/^in/);
    // the spaces around the search are no part of it
    await search().fill('  invoice  ');
    await expect.element(rows()).toHaveLength(1);
  });

  test('a search no mapping matches says so, and Clear search shows all rows again', async () => {
    render(fakeApiClient());
    await expect.element(rows()).toHaveLength(3);

    await search().fill('nothing like it');

    await expect.element(page.getByRole('heading', { name: 'No mappings match' })).toBeVisible();
    await page.getByRole('button', { name: 'Clear search' }).click();
    await expect.element(rows()).toHaveLength(3);
    await expect.element(search()).toHaveValue('');
  });

  test("the search input's own clear button shows all rows again", async () => {
    render(fakeApiClient());
    await search().fill('tree');
    await expect.element(rows()).toHaveLength(1);

    await page.getByRole('button', { name: 'Reset' }).click();

    await expect.element(rows()).toHaveLength(3);
  });

  test('an empty workspace says so', async () => {
    render(fakeApiClient({}));

    await expect.element(page.getByRole('heading', { name: 'No mappings yet' })).toBeVisible();
    await expect.element(page.getByRole('button', { name: 'New mapping' })).toBeVisible();
  });

  test('shows that the list is loading, then a list the service refuses with its problem', async () => {
    const problem = new ApiProblem({ type: 'about:blank', title: 'Service Unavailable', status: 503, detail: 'the workspace is offline' });
    let answer: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    render({ ...fakeApiClient(), listMappings: () => answered.then(() => Promise.reject(problem)) });

    await expect.element(page.getByRole('heading', { name: 'Loading the mappings' })).toBeVisible();
    answer();
    await expect.element(page.getByRole('heading', { name: 'Service Unavailable' })).toBeVisible();
    await expect.element(page.getByText('the workspace is offline')).toBeVisible();
  });
});

describe('the New mapping modal', () => {
  test('has Name, Package and Description and no message choice; Create sends package/name with the description and tells the id', async () => {
    const { api, createMapping } = spied();
    const onCreated = vi.fn();
    render(api, onCreated);

    await page.getByRole('button', { name: 'New mapping' }).click();
    await expect.element(modal().getByRole('textbox')).toHaveLength(3);
    await field('Name').fill('order-to-invoice');
    await field('Package').fill('orders/inbound');
    await field('Description').fill('Turns an order into an invoice.');
    await modal().getByRole('button', { name: 'Create' }).click();

    await expect.poll(() => onCreated.mock.calls).toEqual([['orders/inbound/order-to-invoice']]);
    expect(createMapping.mock.calls).toEqual([[{ id: 'orders/inbound/order-to-invoice', description: 'Turns an order into an invoice.' }]]);
  });

  test('without a package the name is the id; an empty description is not sent; spaces around name and package are dropped', async () => {
    const { api, createMapping } = spied();
    const onCreated = vi.fn();
    render(api, onCreated);
    await page.getByRole('button', { name: 'New mapping' }).click();

    await field('Name').fill('  top  ');
    await field('Package').fill('   ');
    await userEvent.keyboard('{Enter}');

    await expect.poll(() => onCreated.mock.calls).toEqual([['top']]);
    expect(createMapping.mock.calls).toEqual([[{ id: 'top' }]]);
  });

  test('Create without a name asks for one and sends nothing', async () => {
    const { api, createMapping } = spied();
    render(api);
    await page.getByRole('button', { name: 'New mapping' }).click();
    await expect.element(helper('Enter a name.')).not.toBeInTheDocument();

    await modal().getByRole('button', { name: 'Create' }).click();

    await expect.element(helper('Enter a name.')).toBeVisible();
    await expect.element(field('Name')).toHaveAttribute('aria-invalid', 'true');
    expect(createMapping).not.toHaveBeenCalled();
  });

  test.each([
    ['Name', 'a/b', 'Use letters, digits, ".", "_" and "-", up to 64, not starting with ".".'],
    ['Name', '.hidden', 'Use letters, digits, ".", "_" and "-", up to 64, not starting with ".".'],
    ['Name', 'x'.repeat(65), 'Use letters, digits, ".", "_" and "-", up to 64, not starting with ".".'],
    ['Package', 'a//b', 'Use folder names of letters, digits, ".", "_" and "-", separated by "/".'],
    ['Package', '../up', 'Use folder names of letters, digits, ".", "_" and "-", separated by "/".'],
    ['Package', Array.from({ length: 16 }, () => 'p').join('/'), 'Use at most 15 folders.'],
  ])('a %s of "%s" says what is allowed and Create sends nothing', async (label, value, message) => {
    const { api, createMapping } = spied();
    render(api);
    await page.getByRole('button', { name: 'New mapping' }).click();
    await field('Name').fill('valid');

    await field(label).fill(value);

    await expect.element(helper(message)).toBeVisible();
    await expect.element(field(label)).toHaveAttribute('aria-invalid', 'true');
    await modal().getByRole('button', { name: 'Create' }).click();
    expect(createMapping).not.toHaveBeenCalled();
  });

  test('a description of more than 4,000 characters, counted by code point, is refused before it is sent', async () => {
    const { api, createMapping } = spied();
    render(api);
    await page.getByRole('button', { name: 'New mapping' }).click();
    await field('Name').fill('valid');

    // 4,000 astral characters are 8,000 UTF-16 units and still allowed
    await field('Description').fill('😀'.repeat(4000));
    await expect.element(helper('Up to 4,000 characters.')).toBeVisible();
    await field('Description').fill('x'.repeat(4001));

    await expect.element(helper('Use at most 4,000 characters; this has 4,001.')).toBeVisible();
    await modal().getByRole('button', { name: 'Create' }).click();
    expect(createMapping).not.toHaveBeenCalled();
  });

  test('an id that exists shows the 409 problem in the modal and opens nothing', async () => {
    const onCreated = vi.fn();
    render(fakeApiClient(), onCreated);
    await page.getByRole('button', { name: 'New mapping' }).click();

    await field('Name').fill('order-id-copy');
    await field('Package').fill('mappings');
    await modal().getByRole('button', { name: 'Create' }).click();

    await expect.element(modal().getByRole('heading', { name: /Mapping exists/ })).toBeVisible();
    await expect.element(modal().getByText('a mapping with this id exists')).toBeVisible();
    expect(onCreated).not.toHaveBeenCalled();
  });

  test('Create is disabled while the service has not answered', async () => {
    const fake = fakeApiClient();
    let answer: () => void = () => undefined;
    const answered = new Promise<void>((resolve) => (answer = resolve));
    const onCreated = vi.fn();
    render({ ...fake, createMapping: async (request) => answered.then(() => fake.createMapping(request)) }, onCreated);
    await page.getByRole('button', { name: 'New mapping' }).click();
    await field('Name').fill('slow');

    await modal().getByRole('button', { name: 'Create' }).click();

    // PatternFly names a loading button after its spinner too: "Loading... Create"
    await expect.element(modal().getByRole('button', { name: /Create$/ })).toBeDisabled();
    answer();
    await expect.poll(() => onCreated.mock.calls).toEqual([['slow']]);
  });

  test('Cancel closes the modal and sends nothing; it opens empty again', async () => {
    const { api, createMapping } = spied();
    render(api);
    await page.getByRole('button', { name: 'New mapping' }).click();
    await field('Name').fill('draft');

    await modal().getByRole('button', { name: 'Cancel' }).click();

    await expect.element(modal()).not.toBeInTheDocument();
    expect(createMapping).not.toHaveBeenCalled();
    await page.getByRole('button', { name: 'New mapping' }).click();
    await expect.element(field('Name')).toHaveValue('');
  });

  test('Escape closes the modal as Cancel does', async () => {
    render(fakeApiClient());
    await page.getByRole('button', { name: 'New mapping' }).click();
    await expect.element(modal()).toBeVisible();

    await userEvent.keyboard('{Escape}');

    await expect.element(modal()).not.toBeInTheDocument();
  });
});
