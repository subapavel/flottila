import '@patternfly/react-core/dist/styles/base.css';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { page } from 'vitest/browser';
import { Shell } from '../../Shell';
import type { ComponentSettings, EndpointFile, EndpointsApi } from '../api';
import { flowsScreen } from '../screen';
import type { FlowFile, FlowsApi } from './api';

const invoice = `schemaVersion: 1
kind: flow
id: invoice
version: 0.1.0
description: Validate an invoice and send it on.
from: { endpoint: http-in }
delivery: { mode: sync }
limits: { maxBodyBytes: 1048576 }
steps:
  - type: xsd
    name: validate
    schema: flows/invoice.xsd
  - type: route
    prop: PARTNER
    routes:
      - when: "/Invoice/Currency = 'CZK'"
        set: domestic
    otherwise: foreign
  - type: to
    name: partner
    byProp: PARTNER
    endpoints:
      domestic: partner-domestic
      foreign: partner-foreign
  - type: to
    name: hand-over
    endpoint: order-call
`;

const context: FlowFile['context'] = {
  endpoints: [
    { id: 'http-in', path: 'demo/endpoints/http-in.yaml', scheme: 'http', direction: 'in' },
    { id: 'order-call', path: 'demo/endpoints/order-call.yaml', scheme: 'direct', direction: 'out', address: '/orders' },
    { id: 'partner-domestic', path: 'demo/endpoints/partner-domestic.yaml', scheme: 'http', direction: 'out' },
    { id: 'partner-foreign', path: 'demo/endpoints/partner-foreign.yaml', scheme: 'http', direction: 'out' },
  ],
  flows: [
    { path: 'demo/flows/invoice.yaml', id: 'invoice', mode: 'sync' },
    { path: 'demo/flows/order.yaml', id: 'order', mode: 'at-least-once', address: '/orders' },
  ],
  partial: false,
};

const order = `schemaVersion: 1
kind: flow
id: order
version: 0.1.0
from: { endpoint: order-in }
steps:
  - type: to
    endpoint: partner-domestic
`;

/** A fake of the server over a working folder holding `texts`, by path; its ETag counts the reads. */
function fakeFlows(texts: Record<string, string> = { 'demo/flows/invoice.yaml': invoice, 'demo/flows/order.yaml': order }, overrides: Partial<FlowsApi> = {}): FlowsApi {
  let reads = 0;
  return {
    list: vi.fn(async () => ({
      configured: true,
      editable: true,
      truncated: false,
      items: [
        ...Object.keys(texts).map((path) => ({ path, id: path.split('/').at(-1)!.replace('.yaml', '') })),
        { path: 'demo/flows/broken.yaml', problem: 'the file is not YAML: unclosed' },
      ],
    })),
    read: vi.fn(async (path: string) => ({
      file: { path, text: texts[path] ?? 'kind: flow\nid: [unclosed\n', editable: true, context },
      etag: `"${++reads}"`,
    })),
    steps: vi.fn(async () => [{ type: 'xsd', configSchema: { type: 'object', required: ['schema'], properties: { schema: { type: 'string', description: 'The XSD file.' } } } }]),
    ...overrides,
  };
}

const httpIn: EndpointFile = {
  path: 'demo/endpoints/http-in.yaml',
  editable: true,
  id: 'http-in',
  scheme: 'http',
  direction: 'in',
  description: null,
  config: { port: 8080 },
  environments: [],
};

function fakeEndpoints(): EndpointsApi {
  return {
    list: vi.fn(async () => ({ configured: true, editable: true, truncated: false, items: [] })),
    read: vi.fn(async () => ({ file: httpIn, etag: '"1"' })),
    save: vi.fn(),
    components: vi.fn(async (): Promise<ComponentSettings[]> => [
      {
        scheme: 'http',
        settings: {
          format: 1,
          described: true,
          tabs: [{ id: 'general', label: 'General', common: true }],
          fields: [{ name: 'port', type: 'integer', help: 'The port.', tab: 'general', required: true, directions: ['in', 'out'] }],
          rules: [],
        },
      },
    ]),
  };
}

let root: Root | undefined;
let host: HTMLElement | undefined;

function render(flows: FlowsApi, endpoints: EndpointsApi = fakeEndpoints()) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={[flowsScreen(flows, endpoints)]} start="flows" />);
}

afterEach(() => {
  root?.unmount();
  host?.remove();
});

const text = (shown: string) => page.getByText(shown, { exact: false });
const button = (name: string) => page.getByRole('button', { name, exact: true });
const canvas = () => page.getByTestId('flow-canvas');
const nodeOf = (label: string) => canvas().getByText(label, { exact: true });

async function open(flows: FlowsApi = fakeFlows(), endpoints?: EndpointsApi) {
  render(flows, endpoints);
  await button('invoice').click();
  await expect.element(page.getByRole('heading', { name: 'invoice', level: 1 })).toBeVisible();
}

describe('Flows', () => {
  test('lists the flow files of the working folder, and a file that cannot be read says why', async () => {
    render(fakeFlows());
    await expect.element(button('invoice')).toBeVisible();
    await expect.element(text('demo/flows/order.yaml')).toBeVisible();
    await expect.element(text('the file is not YAML: unclosed')).toBeVisible();
  });

  test("draws the flow's steps, read-only, with the flow's own settings beside them", async () => {
    await open();
    await expect.element(text('Read-only')).toBeVisible();
    for (const label of ['http-in', 'validate', 'route', 'partner', 'partner-domestic', 'partner-foreign', 'hand-over', 'order', 'end']) {
      await expect.element(nodeOf(label)).toBeVisible();
    }
    await expect.element(text("domestic · when /Invoice/Currency = 'CZK'")).toBeVisible();
    await expect.element(page.getByRole('heading', { name: 'Flow invoice' })).toBeVisible();
    await expect.element(text('Validate an invoice and send it on.')).toBeVisible();
  });

  test("shows a step's settings when its node is chosen, and its name on the Step tab", async () => {
    await open();
    await nodeOf('validate').click();
    await expect.element(page.getByRole('heading', { name: 'validate', level: 2 })).toBeVisible();
    await expect.element(page.getByLabelText('schema', { exact: false })).toHaveValue('flows/invoice.xsd');
    await page.getByRole('tab', { name: 'Step', exact: true }).click();
    await expect.element(page.getByLabelText('name', { exact: false })).toHaveValue('validate');
    await button("Show the flow's settings").click();
    await expect.element(page.getByRole('heading', { name: 'Flow invoice' })).toBeVisible();
  });

  test("shows a route's rules and a to's endpoints as tables", async () => {
    await open();
    await nodeOf('partner').click();
    const endpoints = page.getByRole('grid', { name: 'endpoints' });
    await expect.element(endpoints.getByText('partner-foreign')).toBeVisible();
    await nodeOf('route').click();
    await expect.element(page.getByRole('grid', { name: 'routes' }).getByText("/Invoice/Currency = 'CZK'")).toBeVisible();
  });

  test("shows the arrival's endpoint in its component's form, and opens it in a tab of its own", async () => {
    await open();
    await nodeOf('http-in').click();
    await expect.element(page.getByLabelText('port', { exact: false })).toHaveValue('8080');
    expect(button('Edit').elements()).toHaveLength(0);
    await button('Open endpoint').click();
    await expect.element(page.getByRole('tab', { name: 'http-in', exact: true })).toBeVisible();
  });

  test('opens the flow a to calls', async () => {
    await open();
    await nodeOf('hand-over').click();
    await button('Open flow order').click();
    await expect.element(page.getByRole('heading', { name: 'order', level: 1 })).toBeVisible();
  });

  test('shows the text of a file it cannot draw, with the problem', async () => {
    render(fakeFlows());
    await button('broken.yaml').click();
    await expect.element(text('The flow cannot be drawn')).toBeVisible();
    await expect.element(text('id: [unclosed')).toBeVisible();
    await expect.element(text('the file: the file is not YAML')).toBeVisible();
  });

  test("shows a module's step undescribed when the node does not answer", async () => {
    await open(fakeFlows(undefined, { steps: vi.fn(async () => ({ missing: 'the server does not answer' })) }));
    await nodeOf('validate').click();
    await expect.element(text("The node's step types could not be read: the server does not answer")).toBeVisible();
    await expect.element(text('"schema": "flows/invoice.xsd"')).toBeVisible();
  });
});
