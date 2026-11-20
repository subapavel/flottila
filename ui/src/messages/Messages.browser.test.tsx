import '@patternfly/react-core/dist/styles/base.css';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { page } from 'vitest/browser';
import { Shell } from '../Shell';
import { AdminError, type AdminApi, type Listed, type ListQuery, type StepRecord, type Timeline } from './admin';
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

/** Saved at its arrival, then 19.6 ms waiting for a worker; 100 µs between its two steps is too short for a row of its own. */
const waited: Timeline = {
  id: 'm-3',
  flow: 'demo/invoice-async',
  steps: [
    step(1, 0, 'ingress', { startedAt: '2026-11-20T09:50:08.535Z', wallNanos: 0 }),
    step(1, 1, 'validate', { startedAt: '2026-11-20T09:50:08.554600Z', wallNanos: 2_200_000, cpuNanos: 1_000_000 }),
    step(1, 2, 'send', { startedAt: '2026-11-20T09:50:08.556900Z', wallNanos: 4_300_000, cpuNanos: 3_000_000 }),
  ],
};

function row(id: string, extra: Partial<Listed> = {}): Listed {
  return { id, flow: 'demo/orders', status: 'completed', startedAt: '2026-11-19T07:30:00Z', durationNanos: 2_500_000, keys: {}, ...extra };
}

/** When the list's range ends in these tests. */
const NOW = new Date('2026-11-19T08:00:00Z');

/** An admin API over the two messages above, listed newest first a page at a time; every call can be replaced in a test. */
function fakeApi(overrides: Partial<AdminApi> = {}): AdminApi {
  const byId = new Map([failedTwice, delivered, waited].map((t) => [t.id, t]));
  return {
    list: vi.fn(async (_query: ListQuery, after?: string) =>
      after === undefined
        ? { items: [row('m-1', { status: 'failed', startedAt: '2026-11-19T07:50:00Z', keys: { order: 'A-7' } })], next: 'page-2' }
        : { items: [row('m-2')], next: null },
    ),
    counts: vi.fn(async () => ({ completed: 12, failed: 2, running: 1, cancelled: 0 })),
    flows: vi.fn(async () => ['demo/invoices', 'demo/orders']),
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
  root.render(<Shell screens={[messagesScreen(api, () => NOW)]} start="messages" />);
}

afterEach(() => {
  root?.unmount();
  host?.remove();
});

const tab = (name: string) => page.getByRole('tab', { name });
const lastHour = { from: '2026-11-19T07:00:00.000Z', to: '2026-11-19T08:00:00.000Z' };

describe('Messages', () => {
  test('opens on the messages of the last hour, newest first a page at a time, and opens each one in a tab of its own', async () => {
    const api = fakeApi();
    render(api);
    const list = page.getByRole('grid', { name: 'Messages' });
    await expect.element(list.getByRole('button', { name: 'm-1' })).toBeVisible();
    expect(api.list).toHaveBeenCalledWith(lastHour);
    await expect.element(list.getByText('2026-11-19T07:50:00Z')).toBeVisible();
    await expect.element(list.getByText('Failed')).toBeVisible();
    await expect.element(list.getByText('order=A-7')).toBeVisible();

    await page.getByRole('button', { name: 'Load more' }).click();
    await expect.element(list.getByRole('button', { name: 'm-2' })).toBeVisible();
    expect(api.list).toHaveBeenLastCalledWith(lastHour, 'page-2');
    await expect.element(list.getByText('Completed')).toBeVisible();
    await expect.element(page.getByRole('button', { name: 'Load more' })).not.toBeInTheDocument();

    await list.getByRole('button', { name: 'm-1' }).click();
    await expect.element(tab('m-1')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('heading', { name: 'Message m-1' })).toBeVisible();

    await tab('Messages').click();
    await list.getByRole('button', { name: 'm-2' }).click();
    await expect.element(tab('m-2')).toHaveAttribute('aria-selected', 'true');
    await expect.element(page.getByRole('heading', { name: 'Message m-2' })).toBeVisible();
    expect(page.getByRole('tab').elements()).toHaveLength(3);

    await tab('Messages').click();
    await list.getByRole('button', { name: 'm-1' }).click();
    await expect.element(tab('m-1')).toHaveAttribute('aria-selected', 'true');
    expect(page.getByRole('tab').elements()).toHaveLength(3);
  });

  test('narrows the list with each filter: time range, flow and status when chosen, business key and step with Search', async () => {
    const api = fakeApi();
    render(api);
    await expect.element(page.getByRole('button', { name: 'm-1' })).toBeVisible();
    expect(api.flows).toHaveBeenCalledOnce();

    await page.getByRole('combobox', { name: 'Time range' }).selectOptions('Last 24 hours');
    const day = { from: '2026-11-18T08:00:00.000Z', to: lastHour.to };
    await vi.waitFor(() => expect(api.list).toHaveBeenLastCalledWith(day));

    await page.getByRole('combobox', { name: 'Flow' }).selectOptions('demo/orders');
    await vi.waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ ...day, flow: 'demo/orders' }));

    await page.getByRole('combobox', { name: 'Status' }).selectOptions('Failed');
    await vi.waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ ...day, flow: 'demo/orders', status: 'failed' }));

    await page.getByRole('textbox', { name: 'Key name' }).fill('order');
    await page.getByRole('textbox', { name: 'Key value' }).fill('A-7');
    expect(api.list).toHaveBeenCalledTimes(4);
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    const keyed = { ...day, flow: 'demo/orders', status: 'failed', key: { name: 'order', value: 'A-7' } };
    await vi.waitFor(() => expect(api.list).toHaveBeenLastCalledWith(keyed));

    await page.getByRole('textbox', { name: 'Step name' }).fill('send');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await vi.waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ ...keyed, step: 'send' }));
    await expect.element(page.getByRole('button', { name: 'm-1' })).toBeVisible();
  });

  test('counts the messages of the time range and flow by status above the list; a count narrows the list to its status', async () => {
    const api = fakeApi();
    render(api);
    const strip = page.getByRole('group', { name: 'Messages by status' });
    await expect.element(strip.getByRole('button', { name: 'Completed 12' })).toBeVisible();
    await expect.element(strip.getByRole('button', { name: 'Running 1' })).toBeVisible();
    await expect.element(strip.getByRole('button', { name: 'Cancelled 0' })).toBeVisible();
    expect(api.counts).toHaveBeenCalledWith(lastHour);

    await strip.getByRole('button', { name: 'Failed 2' }).click();
    await vi.waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ ...lastHour, status: 'failed' }));
    await expect.element(strip.getByRole('button', { name: 'Failed 2' })).toHaveAttribute('aria-pressed', 'true');
    await expect.element(page.getByRole('combobox', { name: 'Status' })).toHaveValue('failed');
    await expect.element(page.getByRole('grid', { name: 'Messages' }).getByRole('button', { name: 'm-1' })).toBeVisible();

    await page.getByRole('combobox', { name: 'Flow' }).selectOptions('demo/orders');
    await vi.waitFor(() => expect(api.counts).toHaveBeenLastCalledWith({ ...lastHour, flow: 'demo/orders' }));
    await vi.waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ ...lastHour, flow: 'demo/orders', status: 'failed' }));

    await strip.getByRole('button', { name: 'Failed 2' }).click();
    await vi.waitFor(() => expect(api.list).toHaveBeenLastCalledWith({ ...lastHour, flow: 'demo/orders' }));
    await expect.element(strip.getByRole('button', { name: 'Failed 2' })).toHaveAttribute('aria-pressed', 'false');
    await expect.element(page.getByRole('combobox', { name: 'Status' })).toHaveValue('');
  });

  test("shows the node's error when the messages cannot be counted, and the list still", async () => {
    render(fakeApi({ counts: async () => Promise.reject(new AdminError(400, 'the time range is longer than 31 days')) }));
    await expect.element(page.getByText('The messages could not be counted by status', { exact: false })).toBeVisible();
    await expect.element(page.getByText('the time range is longer than 31 days')).toBeVisible();
    await expect.element(page.getByRole('button', { name: 'm-1' })).toBeVisible();
  });

  test('opens a message by its id, and says so when the node recorded none', async () => {
    render(fakeApi());
    await page.getByRole('textbox', { name: 'Message id' }).fill('m-2');
    await page.getByRole('button', { name: 'Open', exact: true }).click();
    await expect.element(page.getByRole('heading', { name: 'Message m-2' })).toBeVisible();

    await tab('Messages').click();
    await page.getByRole('textbox', { name: 'Message id' }).fill('nothing');
    await page.getByRole('button', { name: 'Open', exact: true }).click();
    await expect.element(page.getByRole('heading', { name: 'No such message' })).toBeVisible();
  });

  test('says that no message was found, and why a message may not be listed', async () => {
    render(fakeApi({ list: async () => ({ items: [], next: null }) }));
    await expect.element(page.getByRole('heading', { name: 'No messages found' })).toBeVisible();
    await expect.element(page.getByText('(recording level errors or off)', { exact: false })).toBeVisible();
  });

  test("shows the node's error when the flows cannot be listed", async () => {
    render(fakeApi({ flows: async () => Promise.reject(new AdminError(503, 'the node is stopping')) }));
    await expect.element(page.getByText('The deployed flows could not be listed', { exact: false })).toBeVisible();
    await expect.element(page.getByText('the node is stopping')).toBeVisible();
    await expect.element(page.getByRole('combobox', { name: 'Flow' })).toBeDisabled();
  });

  test("shows the node's error when the list is refused", async () => {
    render(fakeApi({ list: async () => Promise.reject(new AdminError(401, 'this route needs Authorization: Bearer <token>')) }));
    await expect.element(page.getByText('The messages could not be listed', { exact: false })).toBeVisible();
    await expect.element(page.getByText('this route needs Authorization: Bearer <token>')).toBeVisible();
  });

  test('shows every attempt step by step, with times, outcomes, errors and the state of the latest attempt', async () => {
    render(fakeApi());
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

    await expect.element(page.getByRole('tabpanel', { name: 'm-1' }).getByText('demo/orders')).toBeVisible();
    await expect.element(page.getByText('Step 1 (send) of attempt 2 failed', { exact: false })).toBeVisible();
  });

  test('shows the wait for a worker between two steps, so the rows add up to the duration in the list', async () => {
    render(fakeApi({ list: async () => ({ items: [row('m-3', { flow: 'demo/invoice-async', durationNanos: 26_200_000 })], next: null }) }));
    await expect.element(page.getByRole('grid', { name: 'Messages' }).getByText('26.2 ms')).toBeVisible();
    await page.getByRole('button', { name: 'm-3' }).click();

    const steps = page.getByRole('grid', { name: 'Steps of attempt 1' });
    const rows = steps.getByRole('row');
    await expect.element(rows.nth(2)).toHaveTextContent('Saved, waiting for a worker19.6 ms');
    expect(rows.elements().map((r) => r.querySelector('[data-label="Wall time"]')?.textContent)).toEqual([undefined, '', '19.6 ms', '2.2 ms', '4.3 ms']);
    expect(rows.nth(1).element().querySelector('[data-label="CPU time"]')?.textContent).toBe('');
    expect(steps.getByText('Between steps').elements()).toHaveLength(0);

    const total = page.getByRole('tabpanel', { name: 'm-3' }).getByLabelText('Duration');
    await expect.element(total.getByText('Other time between steps')).toBeVisible();
    await expect.element(total.getByText('100.0 µs')).toBeVisible();
    await expect.element(total.getByText('26.2 ms')).toBeVisible();
  });

  test('restarts a message whose latest attempt failed, and shows a refusal with its reason', async () => {
    const api = fakeApi();
    render(api);
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
    await page.getByRole('button', { name: 'Load more' }).click();
    await page.getByRole('button', { name: 'm-2' }).click();
    await expect.element(page.getByRole('heading', { name: 'Attempt 1' })).toBeVisible();
    await expect.element(page.getByRole('button', { name: 'Restart' })).not.toBeInTheDocument();
  });

  test('shows the payload after a step in a dialog', async () => {
    const api = fakeApi();
    render(api);
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
    render(fakeApi({ list: async () => ({ items: [row('gone')], next: null }) }));
    await page.getByRole('button', { name: 'gone' }).click();
    await expect.element(page.getByRole('heading', { name: 'No such message' })).toBeVisible();
  });
});
