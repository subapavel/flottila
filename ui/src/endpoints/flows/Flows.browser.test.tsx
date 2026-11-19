import '@patternfly/react-core/dist/styles/base.css';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { Shell } from '../../Shell';
import type { ComponentSettings, EndpointFile, EndpointsApi } from '../api';
import { flowsScreen } from '../screen';
import { FlowsError, type FlowFile, type FlowsApi } from './api';

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
    { path: 'demo/flows/payment.yaml', id: 'payment', mode: 'sync', address: '/payments' },
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

/**
 * A fake of the server over a working folder holding `texts`, by path, each file at version "v1". A save with the
 * file's version writes it as the next one, and one with no version writes a new file; any other version, or no
 * version for a file that exists, is refused with 412.
 */
function fakeFlows(texts: Record<string, string> = { 'demo/flows/invoice.yaml': invoice, 'demo/flows/order.yaml': order }, overrides: Partial<FlowsApi> = {}): FlowsApi {
  const files = { ...texts };
  const versions = new Map<string, number>();
  const etag = (path: string) => `"v${versions.get(path) ?? 1}"`;
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
      file: { path, text: files[path] ?? 'kind: flow\nid: [unclosed\n', editable: true, context },
      etag: etag(path),
    })),
    save: vi.fn(async (path: string, text: string, version: string | null) => {
      if (version === null && files[path] !== undefined) throw new FlowsError(412, 'the file exists already; nothing was written');
      if (version !== null && version !== etag(path)) throw new FlowsError(412, 'the file changed since it was read; nothing was saved');
      files[path] = text;
      versions.set(path, (versions.get(path) ?? 1) + 1);
      return { file: { path, text, editable: true, context }, etag: etag(path) };
    }),
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
    create: vi.fn(async (path: string) => ({ file: { ...httpIn, path }, etag: '"1"' })),
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

  test("draws the flow's steps, with the flow's own settings beside them, and offers Edit", async () => {
    await open();
    await expect.element(button('Edit')).toBeVisible();
    for (const label of ['http-in', 'validate', 'route', 'partner', 'partner-domestic', 'partner-foreign', 'hand-over', 'order', 'end']) {
      await expect.element(nodeOf(label)).toBeVisible();
    }
    await expect.element(text("domestic · when /Invoice/Currency = 'CZK'")).toBeVisible();
    await expect.element(page.getByRole('heading', { name: 'Flow invoice' })).toBeVisible();
    await expect.element(page.getByRole('textbox', { name: 'description' })).toHaveValue('Validate an invoice and send it on.');
    await expect.element(page.getByRole('textbox', { name: 'delivery.mode' })).toHaveValue('sync');
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
    // the flow's own Edit, and none in the endpoint's form
    expect(button('Edit').elements()).toHaveLength(1);
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

const handOver = '  - type: to\n    name: hand-over\n    endpoint: order-call\n';
const partner = '  - type: to\n    name: partner\n';

describe('Editing a flow', () => {
  test('adds a step of the type chosen where the button on an edge is pressed, and Save writes the text with the version it read', async () => {
    const flows = fakeFlows();
    await open(flows);
    await button('Edit').click();
    await button('Add a step at steps[1]').click();
    await page.getByRole('menuitem', { name: /^filter/ }).click();
    await expect.element(nodeOf('filter')).toBeVisible();
    await expect.element(page.getByRole('heading', { name: 'filter', level: 2 })).toBeVisible();
    await button('Save').click();
    expect(flows.save).toHaveBeenCalledWith('demo/flows/invoice.yaml', invoice.replace('  - type: route\n', '  - type: filter\n  - type: route\n'), '"v1"');
    await expect.element(text('Flow invoice saved')).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
  });

  test("moves and deletes the chosen step with the drawer's buttons, and Undo takes a change back", async () => {
    const flows = fakeFlows();
    await open(flows);
    await button('Edit').click();
    await nodeOf('hand-over').click();
    await expect.element(button('Move down')).toBeDisabled();
    await button('Move up').click();
    // the selection followed the step
    await expect.element(button('Move down')).toBeEnabled();
    await button('Save').click();
    expect(flows.save).toHaveBeenCalledWith('demo/flows/invoice.yaml', invoice.replace(handOver, '').replace(partner, `${handOver}${partner}`), '"v1"');
    await expect.element(button('Save')).toBeDisabled();
    await button('Delete').click();
    await expect.element(nodeOf('hand-over')).not.toBeInTheDocument();
    await button('Undo').click();
    await expect.element(nodeOf('hand-over')).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
    // undo goes back through the save too
    await button('Undo').click();
    await expect.element(button('Save')).toBeEnabled();
    await nodeOf('hand-over').click();
    await expect.element(button('Move down')).toBeDisabled();
  });

  test('a field holding what cannot become a value blocks Save until its form is gone', async () => {
    await open(fakeFlows(undefined, { steps: vi.fn(async () => ({ missing: 'the server does not answer' })) }));
    await button('Edit').click();
    await nodeOf('route').click();
    await button('Move up').click();
    await nodeOf('validate').click();
    await page.getByRole('textbox', { name: 'Settings', exact: true }).fill('{ not json');
    await expect.element(button('Save')).toBeDisabled();
    await button('Delete').click();
    await expect.element(button('Save')).toBeEnabled();
  });

  test("an undo that takes away the selection's place shows the flow's own settings", async () => {
    await open();
    await button('Edit').click();
    await button('Add a step at steps[4]').click();
    await page.getByRole('menuitem', { name: /^filter/ }).click();
    await expect.element(page.getByRole('heading', { name: 'filter', level: 2 })).toBeVisible();
    await button('Undo').click();
    await expect.element(page.getByRole('heading', { name: 'Flow invoice' })).toBeVisible();
  });

  test('the canvas takes the arrows, Ctrl+Z and Delete while it has the focus, and never the keys typed into a field', async () => {
    await open();
    await button('Edit').click();
    await nodeOf('validate').click();
    await userEvent.keyboard('{ArrowDown}');
    await expect.element(button('Move up')).toBeEnabled();
    await expect.element(page.getByRole('heading', { name: 'validate', level: 2 })).toBeVisible();
    await userEvent.keyboard('{Control>}z{/Control}');
    // the selection keeps its place, which holds the route again
    await expect.element(page.getByRole('heading', { name: 'route', level: 2 })).toBeVisible();
    await nodeOf('validate').click();
    await expect.element(button('Move up')).toBeDisabled();
    await page.getByRole('tab', { name: 'Step', exact: true }).click();
    const name = page.getByLabelText('name', { exact: false });
    await name.click();
    await userEvent.keyboard('{End}{Backspace}');
    await expect.element(name).toHaveValue('validat');
    await expect.element(nodeOf('validat')).toBeVisible();
    await expect.element(text('Renamed from validate')).toBeVisible();
    await nodeOf('validat').click();
    await userEvent.keyboard('{Delete}');
    await expect.element(nodeOf('validat')).not.toBeInTheDocument();
  });

  test("a field's changes in a row are undone at once, and the problems are found again after each change without blocking Save", async () => {
    const flows = fakeFlows();
    await open(flows);
    await button('Edit').click();
    await nodeOf('validate').click();
    const schema = page.getByLabelText('schema', { exact: false });
    await schema.fill('flows/other.xsd');
    await schema.fill('');
    await expect.element(text('1 problem')).toBeVisible();
    await button('Undo').click();
    await expect.element(schema).toHaveValue('flows/invoice.xsd');
    await expect.element(text('1 problem')).not.toBeInTheDocument();
    await expect.element(button('Undo')).toBeDisabled();
    await schema.fill('');
    await button('Save').click();
    expect(flows.save).toHaveBeenCalledWith('demo/flows/invoice.yaml', invoice.replace('schema: flows/invoice.xsd', 'schema: ""'), '"v1"');
  });

  test('Check lists the problems the flow has now', async () => {
    await open();
    await button('Edit').click();
    await button('Check').click();
    await expect.element(text('Check passed')).toBeVisible();
    await nodeOf('validate').click();
    await page.getByLabelText('schema', { exact: false }).fill('');
    await button('Check').click();
    await expect.element(text('Check found issues')).toBeVisible();
    await expect.element(page.getByRole('list', { name: 'Issues' }).getByText('steps[0].schema', { exact: false })).toBeVisible();
  });

  test('a file changed on disk since it was read opens a dialog, and Reload shows it as it is now', async () => {
    const flows = fakeFlows();
    await open(flows);
    await button('Edit').click();
    await nodeOf('validate').click();
    await page.getByLabelText('schema', { exact: false }).fill('flows/other.xsd');
    // another program writes the file
    await flows.save('demo/flows/invoice.yaml', invoice.replace('name: validate', 'name: check'), '"v1"');
    await button('Save').click();
    await expect.element(text('The file changed on disk')).toBeVisible();
    await button('Reload').click();
    await expect.element(nodeOf('check')).toBeVisible();
    await expect.element(button('Edit')).toBeVisible();
  });

  test('a save the server refuses says why, and the changes stay', async () => {
    await open(fakeFlows(undefined, { save: vi.fn(async () => Promise.reject(new FlowsError(403, 'this server shows flow files read-only'))) }));
    await button('Edit').click();
    await nodeOf('validate').click();
    await button('Delete').click();
    await button('Save').click();
    await expect.element(text('The file was not saved')).toBeVisible();
    await expect.element(text('this server shows flow files read-only')).toBeVisible();
    await expect.element(nodeOf('validate')).not.toBeInTheDocument();
  });

  test('a file the server shows read-only offers no Edit', async () => {
    const flows = fakeFlows();
    const read = flows.read;
    await open({
      ...flows,
      read: async (path) => {
        const stored = await read(path);
        return { ...stored, file: { ...stored.file, editable: false } };
      },
    });
    await expect.element(page.getByText('Read-only', { exact: true })).toBeVisible();
    expect(button('Edit').elements()).toHaveLength(0);
  });

  test('Cancel and closing the tab with unsaved changes ask first', async () => {
    await open();
    await button('Edit').click();
    await nodeOf('validate').click();
    await button('Delete').click();
    await button('Cancel').click();
    await button('Keep editing').click();
    await expect.element(nodeOf('validate')).not.toBeInTheDocument();
    await button('Cancel').click();
    await button('Discard changes').click();
    await expect.element(nodeOf('validate')).toBeVisible();
    await button('Edit').click();
    await nodeOf('validate').click();
    await button('Delete').click();
    await button('Close invoice').click();
    await expect.element(text('Discard changes?')).toBeVisible();
    await button('Discard changes').click();
    await expect.element(page.getByRole('heading', { name: 'invoice', level: 1 })).not.toBeInTheDocument();
  });

  test('a change the editor cannot make says why and changes nothing', async () => {
    render(fakeFlows());
    await button('order').click();
    await button('Edit').click();
    await nodeOf('to').click();
    await button('Delete').click();
    await expect.element(text('The change was not made')).toBeVisible();
    await expect.element(nodeOf('to')).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
  });

  test('a flow written in a form the editor does not keep is shown read-only, with the reason', async () => {
    await open(fakeFlows({ 'demo/flows/invoice.yaml': invoice.replace(/steps:\n[\s\S]*$/, 'steps: [{ type: to, endpoint: partner-domestic }]\n') }));
    await expect.element(page.getByText('Read-only', { exact: true })).toBeVisible();
    await expect.element(text('The editor shows this flow read-only: steps is written on one line ([ … ]).')).toBeVisible();
    expect(button('Edit').elements()).toHaveLength(0);
  });
});

const processes = `schemaVersion: 1
kind: flow
id: invoice
version: 0.1.0
from: { endpoint: http-in }
delivery: { mode: sync }
limits: { maxBodyBytes: 1048576 }
steps:
  - type: process
    process: enrich
  - type: to
    endpoint: partner-domestic
processes:
  enrich:
    steps:
      - type: filter
        when: "true()"
`;

const lastSave = (flows: FlowsApi) => vi.mocked(flows.save).mock.lastCall?.[1] ?? '';

describe('Editing the rest of a flow', () => {
  test("adds, changes, moves and removes the rows of a list field, and renames a map field's key in place", async () => {
    const flows = fakeFlows();
    await open(flows);
    await button('Edit').click();
    await nodeOf('route').click();
    await expect.element(button('Remove row 1')).toBeDisabled();
    await button('Add a row').click();
    await page.getByRole('textbox', { name: 'when of row 2' }).fill('true()');
    await page.getByRole('textbox', { name: 'set of row 2' }).fill('euro');
    await button('Move row 2 up').click();
    await button('Save').click();
    expect(lastSave(flows)).toContain(`    routes:\n      - when: true()\n        set: euro\n      - when: "/Invoice/Currency = 'CZK'"\n        set: domestic\n`);
    await button('Remove row 1').click();
    await nodeOf('partner').click();
    const key = page.getByRole('textbox', { name: 'Value foreign' });
    await key.fill('domestic');
    await expect.element(text('is taken')).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
    await key.fill('abroad');
    await userEvent.keyboard('{Enter}');
    await button('Save').click();
    expect(lastSave(flows)).toBe(invoice.replace('      foreign: partner-foreign\n', '      abroad: partner-foreign\n'));
  });

  test("changes the flow's own settings in their form", async () => {
    const flows = fakeFlows();
    await open(flows);
    await button('Edit').click();
    await page.getByRole('textbox', { name: 'description' }).fill('Send an invoice on.');
    await page.getByRole('combobox', { name: 'delivery.mode' }).selectOptions('at-least-once');
    await button('Save').click();
    expect(lastSave(flows)).toBe(invoice.replace('Validate an invoice and send it on.', 'Send an invoice on.').replace('{ mode: sync }', '{ mode: at-least-once }'));
  });

  test('adds a process with its first step, renames it and every call of it, moves a step into it, and deletes it once no step calls it', async () => {
    const flows = fakeFlows({ 'demo/flows/invoice.yaml': processes });
    await open(flows);
    await button('Edit').click();
    await button('Add a process').click();
    // a refused name says why in the dialog
    await page.getByRole('textbox', { name: 'Name' }).fill('enrich');
    await page.getByRole('dialog').getByRole('button', { name: 'Add', exact: true }).click();
    await expect.element(text('the flow has a process enrich already')).toBeVisible();
    await page.getByRole('textbox', { name: 'Name' }).fill('audit');
    await page.getByRole('combobox', { name: 'First step' }).selectOptions('validate');
    await page.getByRole('dialog').getByRole('button', { name: 'Add', exact: true }).click();
    await expect.element(canvas().getByText('process audit')).toBeVisible();
    await canvas().getByText('process enrich').click();
    await expect.element(text('the message history keeps enrich')).toBeVisible();
    await expect.element(button('Delete process')).toBeDisabled();
    await page.getByRole('textbox', { name: 'Name' }).fill('lookup');
    await button('Rename').click();
    await expect.element(canvas().getByText('process lookup')).toBeVisible();
    await nodeOf('to').click();
    await page.getByRole('combobox', { name: 'Move to the end of' }).selectOptions('process audit');
    await button('Save').click();
    expect(lastSave(flows)).toBe(`${processes.replace('  - type: process\n    process: enrich\n  - type: to\n    endpoint: partner-domestic\n', '  - type: process\n    process: lookup\n').replace('  enrich:\n', '  lookup:\n')}  audit:
    steps:
      - type: validate
      - type: to
        endpoint: partner-domestic
`);
    await canvas().getByText('process audit').click();
    await button('Delete process').click();
    await expect.element(canvas().getByText('process audit')).not.toBeInTheDocument();
  });

  test('refuses a process rename that gives a call without a name the name of another step of its list', async () => {
    await open(fakeFlows({ 'demo/flows/invoice.yaml': processes.replace('  - type: to\n', '  - type: to\n    name: partner\n') }));
    await button('Edit').click();
    await canvas().getByText('process enrich').click();
    await page.getByRole('textbox', { name: 'Name' }).fill('partner');
    await button('Rename').click();
    await expect.element(text('steps[0] has no name and would be named partner, the name of steps[1] in its list')).toBeVisible();
  });

  test('calls another flow through the direct endpoint of its address, created first when the package has none', async () => {
    // the server lists the endpoint once it is created
    let created = false;
    const callPayment = { id: 'call-payment', path: 'demo/endpoints/call-payment.yaml', scheme: 'direct', direction: 'out' as const, address: '/payments' };
    const endpoints = {
      ...fakeEndpoints(),
      create: vi.fn(async (path: string) => {
        created = true;
        return { file: { ...httpIn, path }, etag: '"1"' };
      }),
    };
    const served = fakeFlows();
    const flows = {
      ...served,
      read: vi.fn(async (path: string) => {
        const stored = await served.read(path);
        return created ? { ...stored, file: { ...stored.file, context: { ...context, endpoints: [...context.endpoints, callPayment] } } } : stored;
      }),
    };
    await open(flows, endpoints);
    await button('Edit').click();
    await button('Add a step at steps[4]').click();
    await page.getByRole('menuitem', { name: /^order/ }).click();
    await expect.element(page.getByRole('heading', { name: 'to', level: 2 })).toBeVisible();
    expect(endpoints.create).not.toHaveBeenCalled();
    await button('Add a step at steps[5]').click();
    await page.getByRole('menuitem', { name: /^payment/ }).click();
    await expect.element(page.getByRole('heading', { name: 'to-2', level: 2 })).toBeVisible();
    expect(endpoints.create).toHaveBeenCalledWith(
      'demo/endpoints/call-payment.yaml',
      'schemaVersion: 1\nkind: endpoint\nid: call-payment\nscheme: direct\ndirection: out\nconfig:\n  address: /payments\n',
    );
    // the picture knows the new endpoint: the step names one, and a second call goes through it
    await expect.element(page.getByText(/^\d+ problems?$/)).not.toBeInTheDocument();
    await button('Add a step at steps[6]').click();
    await page.getByRole('menuitem', { name: /^payment/ }).click();
    await expect.element(page.getByRole('heading', { name: 'to-3', level: 2 })).toBeVisible();
    expect(endpoints.create).toHaveBeenCalledTimes(1);
    await button('Save').click();
    expect(lastSave(flows)).toBe(
      `${invoice}  - type: to\n    endpoint: order-call\n  - type: to\n    name: to-2\n    endpoint: call-payment\n  - type: to\n    name: to-3\n    endpoint: call-payment\n`,
    );
  });

  test('a call whose endpoint cannot be created is not added, and says why', async () => {
    const endpoints = { ...fakeEndpoints(), create: vi.fn(async () => Promise.reject(new Error('the file exists already; nothing was written'))) };
    await open(fakeFlows(), endpoints);
    await button('Edit').click();
    await button('Add a step at steps[4]').click();
    await page.getByRole('menuitem', { name: /^payment/ }).click();
    await expect.element(text('demo/endpoints/call-payment.yaml was not created: the file exists already')).toBeVisible();
    await page.getByRole('dialog').getByRole('button', { name: 'Close' }).click();
    await expect.element(button('Save')).toBeDisabled();
  });

  test('New flow writes a file that does not exist yet and opens it; one over an existing file says it exists', async () => {
    const flows = fakeFlows();
    render(flows);
    await button('New flow').click();
    await page.getByRole('textbox', { name: 'Id' }).fill('order');
    await page.getByRole('textbox', { name: 'Arrives at' }).fill('http-in');
    await page.getByRole('textbox', { name: 'Sends to' }).fill('partner-domestic');
    await expect.element(page.getByRole('textbox', { name: 'File' })).toHaveValue('demo/flows/order.yaml');
    await button('Create').click();
    await expect.element(text('demo/flows/order.yaml exists already; nothing was written.')).toBeVisible();
    await page.getByRole('textbox', { name: 'Id' }).fill('payment');
    await button('Create').click();
    expect(flows.save).toHaveBeenLastCalledWith(
      'demo/flows/payment.yaml',
      `schemaVersion: 1
kind: flow
id: payment
version: 0.1.0
from:
  endpoint: http-in
delivery:
  mode: sync
limits:
  maxBodyBytes: 1048576
steps:
  - type: to
    endpoint: partner-domestic
`,
      null,
    );
    await expect.element(page.getByRole('heading', { name: 'payment', level: 1 })).toBeVisible();
  });
});
