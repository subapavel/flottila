import '@patternfly/react-core/dist/styles/base.css';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { page } from 'vitest/browser';
import { Shell } from '../Shell';
import { AdminError, type AdminApi, type StepRecord, type Timeline } from './admin';
import { messagesScreen } from './screen';

function step(attempt: number, n: number, name: string, extra: Partial<StepRecord> = {}): StepRecord {
  return {
    step: n,
    attempt,
    name,
    outcome: 'OK',
    error: null,
    startedAt: `2026-11-17T10:0${attempt}:0${n}Z`,
    wallNanos: 2_500_000,
    cpuNanos: null,
    body: { hash: `sha256:${attempt}${n}`, size: 11, kept: true },
    ...extra,
  };
}

const failedTwice: Timeline = {
  id: 'm-1',
  flow: 'demo/orders',
  steps: [
    step(1, 0, 'arrival'),
    step(1, 1, 'send', { outcome: 'FAILED', error: 'partner refused: 503', cpuNanos: 1_500 }),
    step(2, 0, 'arrival'),
    step(2, 1, 'send', { outcome: 'FAILED', error: 'partner refused: 502', body: { hash: 'sha256:21', size: 4, kept: false } }),
  ],
};

const delivered: Timeline = { id: 'm-2', flow: 'demo/orders', steps: [step(1, 0, 'arrival'), step(1, 1, 'send')] };

/** An admin API over the two messages above; every call can be replaced in a test. */
function fakeApi(overrides: Partial<AdminApi> = {}): AdminApi {
  const byId = new Map([failedTwice, delivered].map((t) => [t.id, t]));
  return {
    findByKey: vi.fn(async (_name: string, _value: string, after?: string) =>
      after === undefined ? { items: ['m-1'], next: 'page-2' } : { items: ['m-2'], next: null },
    ),
    timeline: vi.fn(async (id: string) => byId.get(id) ?? null),
    payload: vi.fn(async () => ({ contentType: 'application/json', text: '{"order":7}', complete: true })),
    restart: vi.fn(async (id: string) => ({ id, attempt: 3 })),
    ...overrides,
  };
}

let root: Root | undefined;
let host: HTMLElement | undefined;

function render(api: AdminApi) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={[messagesScreen(api)]} start="messages" />);
}

afterEach(() => {
  root?.unmount();
  host?.remove();
});

const tab = (name: string) => page.getByRole('tab', { name });

async function searchByKey(name: string, value: string) {
  await page.getByRole('textbox', { name: 'Key name' }).fill(name);
  await page.getByRole('textbox', { name: 'Key value' }).fill(value);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
}

describe('Messages', () => {
  test('finds messages by business key a page at a time, and opens each one in a tab of its own', async () => {
    const api = fakeApi();
    render(api);
    await searchByKey('order', 'A-7');
    await expect.element(page.getByRole('button', { name: 'm-1' })).toBeVisible();
    expect(api.findByKey).toHaveBeenCalledWith('order', 'A-7');

    await page.getByRole('button', { name: 'Load more' }).click();
    await expect.element(page.getByRole('button', { name: 'm-2' })).toBeVisible();
    expect(api.findByKey).toHaveBeenLastCalledWith('order', 'A-7', 'page-2');
    await expect.element(page.getByRole('button', { name: 'Load more' })).not.toBeInTheDocument();

    await page.getByRole('button', { name: 'm-1' }).click();
    await expect.element(tab('m-1')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('heading', { name: 'Message m-1' })).toBeVisible();

    await tab('Messages').click();
    await page.getByRole('button', { name: 'm-2' }).click();
    await expect.element(tab('m-2')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('heading', { name: 'Message m-2' })).toBeVisible();
    expect(page.getByRole('tab').elements()).toHaveLength(3);

    await tab('Messages').click();
    await page.getByRole('button', { name: 'm-1' }).click();
    await expect.element(tab('m-1')).toHaveAttribute('aria-selected', 'true');
    expect(page.getByRole('tab').elements()).toHaveLength(3);
  });

  test('finds a message by its id, and says so when there is none', async () => {
    render(fakeApi());
    await page.getByRole('button', { name: 'Message id' }).click();
    await page.getByRole('textbox', { name: 'Message id' }).fill('m-2');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect.element(page.getByRole('button', { name: 'm-2' })).toBeVisible();

    await page.getByRole('textbox', { name: 'Message id' }).fill('nothing');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect.element(page.getByRole('heading', { name: 'No messages found' })).toBeVisible();
  });

  test("shows the node's error when a search is refused", async () => {
    render(fakeApi({ findByKey: async () => Promise.reject(new AdminError(401, 'this route needs Authorization: Bearer <token>')) }));
    await searchByKey('order', 'A-7');
    await expect.element(page.getByText('The search failed', { exact: false })).toBeVisible();
    await expect.element(page.getByText('this route needs Authorization: Bearer <token>')).toBeVisible();
  });

  test('shows every attempt step by step, with times, outcomes, errors and the state of the latest attempt', async () => {
    render(fakeApi());
    await searchByKey('order', 'A-7');
    await page.getByRole('button', { name: 'm-1' }).click();

    await expect.element(page.getByRole('heading', { name: 'Attempt 1' })).toBeVisible();
    await expect.element(page.getByRole('heading', { name: 'Attempt 2' })).toBeVisible();
    const first = page.getByRole('grid', { name: 'Steps of attempt 1' });
    await expect.element(first.getByText('2026-11-17T10:01:01Z')).toBeVisible();
    await expect.element(first.getByText('partner refused: 503')).toBeVisible();
    await expect.element(first.getByText('1.5 µs')).toBeVisible();
    expect(first.getByText('2.5 ms').elements()).toHaveLength(2);
    const second = page.getByRole('grid', { name: 'Steps of attempt 2' });
    await expect.element(second.getByText('4 B, not kept')).toBeVisible();

    await expect.element(page.getByText('demo/orders')).toBeVisible();
    await expect.element(page.getByText('Step 1 (send) of attempt 2 failed', { exact: false })).toBeVisible();
  });

  test('restarts a message whose latest attempt failed, and shows a refusal with its reason', async () => {
    const api = fakeApi();
    render(api);
    await searchByKey('order', 'A-7');
    await page.getByRole('button', { name: 'm-1' }).click();
    await page.getByRole('button', { name: 'Restart' }).click();
    await expect.element(page.getByText('Restarted as attempt 3', { exact: false })).toBeVisible();
    expect(api.restart).toHaveBeenCalledWith('m-1');

    await page.getByRole('button', { name: 'Refresh' }).click();
    await expect.element(page.getByText('Restarted as attempt 3', { exact: false })).not.toBeInTheDocument();
    expect(api.timeline).toHaveBeenCalledTimes(2);

    api.restart = vi.fn(async () => Promise.reject(new AdminError(422, 'flow demo/orders is not at-least-once')));
    await page.getByRole('button', { name: 'Restart' }).click();
    await expect.element(page.getByText('The restart was refused', { exact: false })).toBeVisible();
    await expect.element(page.getByText('flow demo/orders is not at-least-once')).toBeVisible();
  });

  test('offers no restart when the latest attempt went through', async () => {
    render(fakeApi());
    await searchByKey('order', 'A-7');
    await page.getByRole('button', { name: 'Load more' }).click();
    await page.getByRole('button', { name: 'm-2' }).click();
    await expect.element(page.getByRole('heading', { name: 'Attempt 1' })).toBeVisible();
    await expect.element(page.getByRole('button', { name: 'Restart' })).not.toBeInTheDocument();
  });

  test('shows the payload after a step in a dialog', async () => {
    const api = fakeApi();
    render(api);
    await searchByKey('order', 'A-7');
    await page.getByRole('button', { name: 'm-1' }).click();
    await page.getByRole('button', { name: 'Show the payload after step 1 of attempt 1' }).click();

    const dialog = page.getByRole('dialog', { name: 'Payload after step 1 (send), attempt 1' });
    await expect.element(dialog.getByText('{"order":7}')).toBeVisible();
    await expect.element(dialog.getByText('application/json, 11 B, sha256:11')).toBeVisible();
    expect(api.payload).toHaveBeenCalledWith('m-1', 1, 1);

    await dialog.getByRole('button', { name: 'Close' }).click();
    await expect.element(dialog).not.toBeInTheDocument();
  });

  test('says when the node recorded no message with the id of a tab', async () => {
    render(fakeApi({ findByKey: async () => ({ items: ['gone'], next: null }) }));
    await searchByKey('order', 'A-7');
    await page.getByRole('button', { name: 'gone' }).click();
    await expect.element(page.getByRole('heading', { name: 'No such message' })).toBeVisible();
  });
});
