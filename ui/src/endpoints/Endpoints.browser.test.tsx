import '@patternfly/react-core/dist/styles/base.css';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { page } from 'vitest/browser';
import { Shell } from '../Shell';
import { EndpointsError, type ComponentSettings, type EndpointFile, type EndpointsApi } from './api';
import { endpointsScreen } from './screen';
import type { SettingsDescription } from './settings';

const drop: SettingsDescription = {
  format: 1,
  described: true,
  tabs: [
    { id: 'general', label: 'General', common: true },
    { id: 'polling', label: 'Polling', common: false },
    { id: 'retry', label: 'Retry', common: true },
    { id: 'recording', label: 'Recording', common: true },
    { id: 'security', label: 'Security', common: true },
  ],
  fields: [
    { name: 'host', type: 'text', help: 'The server.', tab: 'general', required: true, directions: ['in', 'out'] },
    { name: 'port', type: 'integer', help: "The server's port.", tab: 'general', required: false, directions: ['in', 'out'], default: 22, min: 1, max: 65535 },
    { name: 'password', type: 'secret', help: 'The password.', tab: 'security', required: false, directions: ['in', 'out'] },
    { name: 'afterSuccess', type: 'choice', help: 'What happens to a delivered file.', tab: 'polling', required: false, directions: ['in'], default: 'delete', choices: ['delete', 'move'] },
    { name: 'moveTo', type: 'text', help: 'Where a delivered file goes.', tab: 'polling', required: true, directions: ['in'], visibleWhen: { field: 'afterSuccess', values: ['move'] } },
    { name: 'url', type: 'text', help: 'Where a producer sends.', tab: 'general', required: false, directions: ['out'] },
  ],
  rules: [],
};

const inbox: EndpointFile = {
  path: 'demo/endpoints/inbox.yaml',
  editable: true,
  id: 'inbox',
  scheme: 'drop',
  direction: 'in',
  description: 'The drop of the demo.',
  config: { host: 'sftp', password: 'secret://sftp-password', colour: 'red' },
  environments: [{ name: 'prod', options: ['host'] }],
};

const components: ComponentSettings[] = [
  { scheme: 'drop', settings: drop },
  { scheme: 'raw', settings: { format: 1, described: false, tabs: drop.tabs.filter((t) => t.common), fields: [], rules: [] } },
];

/** An API over one working folder holding `files`; every call can be replaced in a test. */
function fakeApi(files: EndpointFile[] = [inbox], overrides: Partial<EndpointsApi> = {}): EndpointsApi {
  const byPath = new Map(files.map((f) => [f.path, f]));
  return {
    list: vi.fn(async () => ({
      configured: true,
      editable: files.every((f) => f.editable),
      truncated: false,
      items: [...files.map(({ path, id, scheme, direction }) => ({ path, id, scheme, direction })), { path: 'demo/endpoints/broken.yaml', problem: 'an endpoint file names its id and scheme' }],
    })),
    read: vi.fn(async (path: string) => ({ file: byPath.get(path)!, etag: '"v1"' })),
    save: vi.fn(async (path: string, config) => ({ file: { ...byPath.get(path)!, config }, etag: '"v2"' })),
    components: vi.fn(async () => components),
    ...overrides,
  };
}

let root: Root | undefined;
let host: HTMLElement | undefined;

function render(api: EndpointsApi) {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  root.render(<Shell screens={[endpointsScreen(api)]} start="endpoints" />);
}

afterEach(() => {
  root?.unmount();
  host?.remove();
});

const field = (name: string) => page.getByLabelText(name, { exact: false });
const text = (shown: string) => page.getByText(shown, { exact: false });
const button = (name: string) => page.getByRole('button', { name, exact: true });
const settingsTab = (name: string) => page.getByRole('tab', { name, exact: true });

async function open(api: EndpointsApi, id = 'inbox') {
  render(api);
  await button(id).click();
  await expect.element(page.getByRole('heading', { name: id, level: 1 })).toBeVisible();
}

describe('Endpoints', () => {
  test('lists the endpoint files of the working folder, and a file that cannot be read says why', async () => {
    render(fakeApi());
    await expect.element(button('inbox')).toBeVisible();
    await expect.element(text('demo/endpoints/inbox.yaml')).toBeVisible();
    await expect.element(text('an endpoint file names its id and scheme')).toBeVisible();
  });

  test('says how to set a working folder when none is set', async () => {
    render(fakeApi([], { list: vi.fn(async () => ({ configured: false, editable: false, truncated: false, items: [] })) }));
    await expect.element(text('No working folder')).toBeVisible();
  });

  test("opens read-only, with the platform's tabs around the adapter's own and the fields of the endpoint's direction", async () => {
    await open(fakeApi());
    for (const name of ['General', 'Polling', 'Retry', 'Recording', 'Security']) await expect.element(settingsTab(name)).toBeVisible();
    await expect.element(field('host')).toHaveValue('sftp');
    await expect.element(field('host')).toHaveAttribute('readonly');
    await expect.element(field('port')).toHaveAttribute('placeholder', 'Default: 22');
    await expect.element(text('Where a producer sends.')).not.toBeInTheDocument();
    await expect.element(field('colour')).toHaveValue('"red"');
    await expect.element(page.getByText(/^no such setting/)).toBeVisible();
    await expect.element(text('Environments override some of these settings')).toBeVisible();
    await settingsTab('Security').click();
    await expect.element(field('password')).toHaveAttribute('type', 'password');
    await settingsTab('Retry').click();
    await expect.element(page.getByRole('tabpanel', { name: 'Retry' }).getByText('No settings on this tab for an in endpoint.')).toBeVisible();
    await expect.element(button('Edit')).toBeVisible();
  });

  test('offers no Edit when the working folder is read-only', async () => {
    await open(fakeApi([{ ...inbox, editable: false }]));
    await expect.element(page.getByRole('tabpanel', { name: 'inbox' }).getByText('Read-only')).toBeVisible();
    await expect.element(field('host')).toHaveValue('sftp');
    expect(button('Edit').elements()).toHaveLength(0);
  });

  test('shows a field while the choice it depends on has its value, and Check names what is missing', async () => {
    await open(fakeApi());
    await button('Edit').click();
    await settingsTab('Polling').click();
    await expect.element(text('Where a delivered file goes.')).not.toBeInTheDocument();
    await field('afterSuccess').selectOptions('move');
    await expect.element(field('moveTo')).toBeVisible();
    await button('Check').click();
    await expect.element(text('Check found issues')).toBeVisible();
    await expect.element(text('moveTo is required when afterSuccess is move').first()).toBeVisible();
    await field('moveTo').fill('/done');
    await button('Check').click();
    await expect.element(page.getByRole('listitem').filter({ hasText: 'colour no such setting' })).toBeVisible();
  });

  test('saves only the options, with the version it read, and keeps editing', async () => {
    const api = fakeApi();
    await open(api);
    await button('Edit').click();
    await field('port').fill('2222');
    await field('host').fill('');
    await button('Save').click();
    expect(api.save).toHaveBeenCalledWith('demo/endpoints/inbox.yaml', { password: 'secret://sftp-password', colour: 'red', port: 2222 }, '"v1"');
    await expect.element(text('Endpoint inbox saved')).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
  });

  test('a file changed on disk since it was read opens a dialog, and Reload shows it as it is now', async () => {
    let reads = 0;
    const api = fakeApi([inbox], {
      read: vi.fn(async () => ({ file: reads++ === 0 ? inbox : { ...inbox, config: { host: 'elsewhere' } }, etag: `"v${reads}"` })),
      save: vi.fn(async () => Promise.reject(new EndpointsError(412, 'the file changed since it was read; nothing was saved'))),
    });
    await open(api);
    await button('Edit').click();
    await field('host').fill('other');
    await button('Save').click();
    await expect.element(text('The file changed on disk')).toBeVisible();
    await button('Reload').click();
    await expect.element(field('host')).toHaveValue('elsewhere');
    await expect.element(button('Edit')).toBeVisible();
  });

  test('Cancel and closing the tab with unsaved changes ask first', async () => {
    await open(fakeApi());
    await button('Edit').click();
    await field('host').fill('other');
    await button('Cancel').click();
    await button('Keep editing').click();
    await expect.element(field('host')).toHaveValue('other');
    await button('Close inbox').click();
    await expect.element(text('Discard changes?')).toBeVisible();
    await button('Discard changes').click();
    await expect.element(page.getByRole('heading', { name: 'inbox', level: 1 })).not.toBeInTheDocument();
  });

  test("without the component's description the options are shown as JSON, read-only, with the reason", async () => {
    await open(fakeApi([inbox], { components: vi.fn(async () => Promise.reject(new EndpointsError(502, 'the node does not answer'))) }));
    await expect.element(text("The node's components could not be read: the node does not answer")).toBeVisible();
    await expect.element(field('Settings')).toHaveAttribute('readonly');
    expect(button('Edit').elements()).toHaveLength(0);
  });

  test('a component that describes nothing gets a raw editor of all its options', async () => {
    const raw: EndpointFile = { ...inbox, id: 'raw-in', path: 'demo/endpoints/raw.yaml', scheme: 'raw', environments: [], config: { a: 1 } };
    const api = fakeApi([raw]);
    await open(api, 'raw-in');
    await button('Edit').click();
    await field('Settings').fill('{"a": ');
    await expect.element(text('is not a JSON object')).toBeVisible();
    await expect.element(button('Save')).toBeDisabled();
    await field('Settings').fill('{"a": 2, "b": true}');
    await button('Save').click();
    expect(api.save).toHaveBeenCalledWith('demo/endpoints/raw.yaml', { a: 2, b: true }, '"v1"');
  });
});
