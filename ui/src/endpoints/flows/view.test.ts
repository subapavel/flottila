import { readdirSync, readFileSync } from 'node:fs';
import { createServer, request, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { stringify } from 'yaml';
import { flowFiles, type FlowFile } from '../files/flowFiles';
import { BUILT_IN, type StepType } from './steps';
import type { FlowContext, FlowView } from './types';
import { MAX_NODES, pointer, view } from './view';

const repo = join(__dirname, '..', '..', '..', '..');
const fixtures = join(__dirname, 'fixtures');

/** The step types of the node's own modules, with the settings each one describes. */
const moduleSteps: FlowContext['steps'] = [
  { type: 'jq', configSchema: { type: 'object', required: ['filter'], properties: { filter: { type: 'string', minLength: 1 }, maxRecursionDepth: { type: 'integer', minimum: 1, maximum: 4194304 } } } },
  { type: 'mapping', configSchema: { type: 'object', required: ['mapping'], additionalProperties: false, properties: { mapping: { type: 'string', minLength: 1 } } } },
  { type: 'xsd', configSchema: { type: 'object', required: ['schema'], properties: { schema: { type: 'string', minLength: 1 } } } },
  { type: 'xslt', configSchema: { type: 'object', required: ['stylesheet'], properties: { stylesheet: { type: 'string', minLength: 1 } } } },
];

/** The demo package's endpoints and flows, as the development server gives them for a flow of the demo. */
const demo: FlowContext = {
  endpoints: [
    { id: 'http-in', path: 'demo/endpoints/http-in.yaml', scheme: 'http', direction: 'in' },
    { id: 'order-partner-call', path: 'demo/endpoints/order-partner-call.yaml', scheme: 'direct', direction: 'out', address: '/orders/partner' },
    { id: 'partner-domestic', path: 'demo/endpoints/partner-domestic.yaml', scheme: 'http', direction: 'out' },
    { id: 'partner-foreign', path: 'demo/endpoints/partner-foreign.yaml', scheme: 'http', direction: 'out' },
  ],
  flows: [
    { path: 'demo/flows/invoice-sync.yaml', id: 'invoice-sync', mode: 'sync' },
    { path: 'demo/flows/order-partner.yaml', id: 'order-partner', mode: 'sync', address: '/orders/partner' },
  ],
  partial: false,
  steps: moduleSteps,
};

/** The view as a golden file holds it: each node on one line, so that a change shows where it is. */
const golden = (v: FlowView) =>
  [
    `readable: ${v.readable}`,
    'nodes:',
    ...v.nodes.map((n) => `  ${JSON.stringify({ ...n, form: n.form === undefined ? undefined : { path: n.form.path, fields: n.form.settings.fields.map((f) => f.name), missing: n.form.missing } })}`),
    'edges:',
    ...v.edges.map((e) => `  ${JSON.stringify(e)}`),
    'problems:',
    ...v.problems.map((p) => `  ${JSON.stringify(p)}`),
    '',
  ].join('\n');

describe('view of the flows under examples', () => {
  let server: Server;
  let base: string;

  beforeAll(async () => {
    const middleware = flowFiles({ workspace: join(repo, 'examples'), editable: false, origins: ['http://localhost:5173'] });
    server = createServer((req, res) => middleware(req, res, () => res.writeHead(404).end()));
    await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/files/flows`;
  });

  afterAll(() => new Promise<void>((done) => server.close(() => done())));

  const get = (url: string) =>
    new Promise<unknown>((done, failed) => {
      request(url, { headers: { 'sec-fetch-site': 'same-origin' } }, (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () => done(JSON.parse(Buffer.concat(chunks).toString('utf8'))));
      })
        .on('error', failed)
        .end();
    });

  it('draws every flow as its golden view says, with no problem', async () => {
    const { items } = (await get(base)) as { items: { path: string; id?: string }[] };
    expect(items.map((i) => i.path)).toEqual([
      'demo/flows/invoice-async.yaml',
      'demo/flows/invoice-sync.yaml',
      'demo/flows/order-jq.yaml',
      'demo/flows/order-partner.yaml',
      'demo/flows/sftp-invoice.yaml',
      'webhook-plugin/package/flows/relay.yaml',
    ]);
    for (const item of items) {
      const file = (await get(`${base}/${item.path}`)) as FlowFile;
      const drawn = view(file.text, { ...file.context, steps: moduleSteps });
      expect(drawn.problems, item.path).toEqual([]);
      await expect(golden(drawn)).toMatchFileSnapshot(join('golden', `${item.path.split('/').at(-1)!.replace(/\.ya?ml$/, '')}.txt`));
    }
  });
});

describe('view of awkward files', () => {
  const names = readdirSync(fixtures).filter((f) => f.endsWith('.yaml'));

  it.each(names)('draws %s as its golden view says', async (name) => {
    const drawn = view(readFileSync(join(fixtures, name), 'utf8'), demo);
    await expect(golden(drawn)).toMatchFileSnapshot(join('golden', name.replace('.yaml', '.txt')));
  });

  it('gives the same view of a file with CRLF line ends', () => {
    const text = readFileSync(join(fixtures, 'comments.yaml'), 'utf8');
    expect(view(text.replaceAll('\n', '\r\n'), demo)).toEqual(view(text, demo));
  });

  it('gives the same view every time', () => {
    const text = readFileSync(join(fixtures, 'processes.yaml'), 'utf8');
    expect(view(text, demo)).toEqual(view(text, demo));
  });

  it('locks what the editor cannot change, and says why', () => {
    const drawn = view(readFileSync(join(fixtures, 'anchors.yaml'), 'utf8'), demo);
    const locked = Object.fromEntries(drawn.nodes.filter((n) => n.locked !== undefined).map((n) => [n.id, n.locked]));
    expect(locked).toEqual({
      '/steps/0': 'The file holds an anchor (&check) here, which the editor does not change.',
      '/steps/1': 'The file holds a merge key (<<) here, which the editor does not change.',
      '/steps/2': 'The file holds a tag (!!str) here, which the editor does not change.',
    });
  });

  it('finds the problems of each awkward file, each at its path', () => {
    const problems = (name: string) => view(readFileSync(join(fixtures, name), 'utf8'), demo).problems;
    expect(problems('comments.yaml')).toEqual([]);
    expect(problems('anchors.yaml')).toEqual([
      { path: ['steps', 1, '<<'], message: 'the node does not merge keys: it reads << as a key of its own' },
      { path: ['steps', 1, '<<'], message: 'the node reads the alias *check as the text check, not as what &check holds' },
      { path: ['steps', 1], message: 'a step is a mapping with a text type' },
    ]);
    expect(view(readFileSync(join(fixtures, 'one-line.yaml'), 'utf8'), demo).readOnly).toBe('steps is written on one line ([ … ])');
    expect(view(readFileSync(join(fixtures, 'comments.yaml'), 'utf8'), demo).readOnly).toBeUndefined();
    expect(problems('one-line.yaml')).toEqual([
      { path: ['steps'], message: 'the list is written on one line ([ … ]): the editor shows this flow read-only', warning: true },
    ]);
    expect(problems('unknown.yaml')).toEqual([
      { path: ['steps', 1, 'extra'], message: 'no such setting' },
      { path: ['steps', 2, 'type'], message: 'the node serves no step of the type sign', warning: true },
      { path: ['steps', 4, 'endpoint'], message: "'nowhere' names no endpoint of the package" },
    ]);
    expect(problems('values.yaml')).toEqual([
      { path: ['limits', 'maxBodyBytes'], message: 'the node reads 1_000 otherwise than the editor: write it in quotes' },
      { path: ['limits', 'maxInFlight'], message: 'the node reads 0o17 otherwise than the editor: write it in quotes' },
      { path: ['steps', 1, 'schema'], message: 'the key schema is used twice in one mapping' },
    ]);
    expect(problems('processes.yaml')).toEqual([
      { path: ['steps', 2, 'endpoints'], message: 'the route route sets PARTNER to euro, which no entry of endpoints covers' },
    ]);
  });

  it('labels the choices of a to by prop with the conditions its route makes certain', () => {
    const drawn = view(readFileSync(join(fixtures, 'processes.yaml'), 'utf8'), demo);
    expect(drawn.edges.filter((e) => e.kind === 'choice').map((e) => e.label)).toEqual([
      "domestic · when /Invoice/Currency = 'CZK'",
      'foreign · otherwise',
      'spare · never taken',
    ]);
  });

  it('counts the names a process step runs under, and frames what a split in a process repeats', () => {
    const drawn = view(readFileSync(join(fixtures, 'processes.yaml'), 'utf8'), demo);
    const byId = new Map(drawn.nodes.map((n) => [n.id, n]));
    expect(byId.get('/processes/lines/steps/1')).toMatchObject({ runs: 2, runsAs: ['first/lines/validate', 'second/lines/validate'] });
    expect(byId.get('/processes/enrich/steps/0')).toMatchObject({ runs: 2, runsAs: ['first/extractKey', 'second/extractKey'] });
    expect(byId.get('/processes/spare/steps/0')).toMatchObject({ runs: 0, runsAs: [] });
    expect(byId.get('/steps/0#each')).toMatchObject({ kind: 'group', label: 'each part of /Invoice/Line' });
    expect(byId.get('/steps/4#calls')).toMatchObject({ kind: 'flow', label: 'order-partner', opens: { flow: 'demo/flows/order-partner.yaml' } });
  });

  it('warns, and does not refuse, what is not found in a working folder read in part', () => {
    const drawn = view(readFileSync(join(fixtures, 'unknown.yaml'), 'utf8'), { ...demo, partial: true });
    expect(drawn.problems).toContainEqual({ path: ['steps', 4, 'endpoint'], message: "'nowhere' names no endpoint of the package: not found in the working folder", warning: true });
  });

  it('shows the steps of modules undescribed when the node cannot say what it serves', () => {
    const drawn = view(readFileSync(join(fixtures, 'comments.yaml'), 'utf8'), { ...demo, steps: { missing: 'the node is not running' } });
    expect(drawn.nodes.find((n) => n.id === '/steps/0')?.form?.missing).toBe("The node's step types could not be read: the node is not running");
    expect(drawn.problems).toEqual([]);
  });
});

describe('view of a file it cannot draw', () => {
  it('is not readable, with the reason', () => {
    expect(view('kind: flow\nid: [unclosed\n', demo)).toMatchObject({ readable: false, nodes: [], problems: [{ path: [], message: expect.stringContaining('the file is not YAML') }] });
    expect(view('kind: flow\nid: x\nsteps: {}\n', demo)).toMatchObject({ readable: false, problems: [{ path: [], message: 'the flow has no list of steps under steps' }] });
    expect(view('kind: endpoint\nid: x\n', demo)).toMatchObject({ readable: false, problems: [{ path: [], message: 'the file is not a flow' }] });
  });

  it(`draws no flow of more than ${MAX_NODES} nodes`, () => {
    const steps = Array.from({ length: MAX_NODES }, (_, i) => ({ type: 'xsd', name: `s${i}`, schema: 'flows/invoice.xsd' }));
    const drawn = view(stringify({ schemaVersion: 1, kind: 'flow', id: 'big', from: { endpoint: 'http-in' }, steps }), demo);
    expect(drawn).toMatchObject({ readable: false, nodes: [], problems: [{ path: [], message: `the flow would be drawn with ${MAX_NODES + 2} nodes, more than the ${MAX_NODES} the editor draws` }] });
  });

  it('draws a file of another schemaVersion, with the problem', () => {
    const drawn = view('schemaVersion: 2\nkind: flow\nid: x\nfrom: { endpoint: http-in }\nsteps:\n  - type: to\n    endpoint: partner-domestic\n', demo);
    expect(drawn.readable).toBe(true);
    expect(drawn.problems).toEqual([{ path: ['schemaVersion'], message: 'schemaVersion is 2, not 1: the editor shows this flow read-only' }]);
    expect(drawn.readOnly).toBe('schemaVersion is 2, not 1');
  });

  it('draws a step that is no mapping in its place, locked', () => {
    const drawn = view('schemaVersion: 1\nkind: flow\nid: x\nfrom: { endpoint: http-in }\nsteps:\n  - just text\n', demo);
    expect(drawn.problems).toEqual([{ path: ['steps', 0], message: 'a step is a mapping with a text type' }]);
    expect(drawn.nodes.find((n) => n.id === '/steps/0')).toMatchObject({ label: 'step 1', locked: expect.stringContaining('no mapping') });
  });
});

describe('the rules of names and processes, shared with the node', () => {
  const file = join(repo, 'platform', 'core', 'src', 'test', 'resources', 'dev', 'rail8', 'core', 'flow', 'flow-rule-cases.json');
  const { cases } = JSON.parse(readFileSync(file, 'utf8')) as {
    cases: { name: string; steps: unknown[]; processes?: Record<string, unknown>; problems: { path: (string | number)[]; message: string }[] }[];
  };
  const context: FlowContext = {
    endpoints: [{ id: 'in', path: 'p/endpoints/in.yaml', scheme: 'http', direction: 'in' }],
    flows: [],
    steps: [{ type: 'xsd', configSchema: { type: 'object', required: ['schema'], properties: { schema: { type: 'string' } } } }],
  };

  it.each(cases)('$name', ({ steps, processes, problems }) => {
    const text = stringify({ schemaVersion: 1, kind: 'flow', id: 'cases', version: '0.1.0', from: { endpoint: 'in' }, steps, ...(processes === undefined ? {} : { processes }) });
    const order = (p: { path: readonly (string | number)[] }) => pointer(p.path);
    const found = view(text, context).problems;
    expect([...found].sort((a, b) => order(a).localeCompare(order(b)))).toEqual([...problems].sort((a, b) => order(a).localeCompare(order(b))));
  });
});

describe('steps inside a step', () => {
  /** A made-up branching step: one list of steps for each condition. */
  const types: Record<string, StepType> = {
    ...BUILT_IN,
    choose: {
      fields: [],
      stepLists: [['when', '*', 'steps']],
      detail: 'prop',
    },
  };
  const text = `schemaVersion: 1
kind: flow
id: branching
version: 0.1.0
from: { endpoint: http-in }
steps:
  - type: choose
    when:
      - test: "/Invoice/Currency = 'CZK'"
        steps:
          - type: xsd
            schema: flows/invoice.xsd
          - type: xsd
            schema: flows/invoice.xsd
      - test: "true()"
        steps:
          - type: to
            endpoint: partner-foreign
  - type: to
    endpoint: partner-domestic
`;

  it('draws a fork of one column per list, each joining the step after it', async () => {
    const drawn = view(text, demo, types);
    await expect(golden(drawn)).toMatchFileSnapshot(join('golden', 'branching.txt'));
    expect(drawn.edges.filter((e) => e.source === '/steps/0')).toEqual([
      { id: '/steps/0->/steps/0/when/0/steps/0', source: '/steps/0', target: '/steps/0/when/0/steps/0', kind: 'choice', label: 'when 0' },
      { id: '/steps/0->/steps/0/when/1/steps/0', source: '/steps/0', target: '/steps/0/when/1/steps/0', kind: 'choice', label: 'when 1' },
    ]);
    expect(drawn.edges.filter((e) => e.target === '/steps/1').map((e) => e.source)).toEqual(['/steps/0/when/0/steps#end', '/steps/0/when/1/steps#end']);
  });

  it('applies the rules of names to each list on its own', () => {
    expect(view(text, demo, types).problems).toEqual([
      { path: ['steps', 0, 'when', 0, 'steps', 1], message: "has no name, and its default 'xsd' is the name of steps[0].when[0].steps[0] too: step names are unique in a list" },
    ]);
  });
});
