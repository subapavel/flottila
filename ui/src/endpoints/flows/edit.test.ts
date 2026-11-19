import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parse as parseYaml } from 'yaml';
import { edit, editor, EditRefused, follow, spanOf, stepLists, type Op } from './edit';
import { addStep, deleteStep, moveStep, setField, setKeys } from './ops';
import { BUILT_IN, type StepType } from './steps';
import type { FlowContext, FlowView, Path } from './types';
import { pointer, view } from './view';

const repo = join(__dirname, '..', '..', '..', '..');
const fixtures = join(__dirname, 'fixtures');

/** The demo package as a flow of it sees it. */
const demo: FlowContext = {
  endpoints: [
    { id: 'http-in', path: 'demo/endpoints/http-in.yaml', scheme: 'http', direction: 'in' },
    { id: 'order-partner-call', path: 'demo/endpoints/order-partner-call.yaml', scheme: 'direct', direction: 'out', address: '/orders/partner' },
    { id: 'partner-domestic', path: 'demo/endpoints/partner-domestic.yaml', scheme: 'http', direction: 'out' },
    { id: 'partner-foreign', path: 'demo/endpoints/partner-foreign.yaml', scheme: 'http', direction: 'out' },
  ],
  flows: [{ path: 'demo/flows/order-partner.yaml', id: 'order-partner', mode: 'sync', address: '/orders/partner' }],
  partial: false,
  steps: [{ type: 'xsd', configSchema: { type: 'object', required: ['schema'], properties: { schema: { type: 'string' } } } }],
};

/**
 * The texts of the flow files under examples/, of the awkward files of the fixtures, and of the comments fixture with
 * CRLF line ends, by the name of their golden file.
 */
const files: [string, string][] = [
  ...['invoice-async', 'invoice-sync', 'order-jq', 'order-partner', 'sftp-invoice'].map((n) => [n, readFileSync(join(repo, 'examples', 'demo', 'flows', `${n}.yaml`), 'utf8')] as [string, string]),
  ['relay', readFileSync(join(repo, 'examples', 'webhook-plugin', 'package', 'flows', 'relay.yaml'), 'utf8')],
  ...readdirSync(fixtures)
    .filter((f) => f.endsWith('.yaml'))
    .map((f) => [f.replace('.yaml', ''), readFileSync(join(fixtures, f), 'utf8')] as [string, string]),
  ['comments-crlf', readFileSync(join(fixtures, 'comments.yaml'), 'utf8').replace(/\n/g, '\r\n')],
];

/** A text as the editor reads it: keys used twice and merge keys taken as they stand. */
const parse = (text: string): unknown => parseYaml(text, { uniqueKeys: false, merge: false });

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

const valueAt = (value: unknown, path: Path) => path.reduce<unknown>((v, s) => (Array.isArray(v) || isRecord(v) ? (v as Record<string | number, unknown>)[s] : undefined), value);

const stepNode = (v: FlowView, path: Path) => v.nodes.find((n) => n.id === pointer(path));

/** What an edit gives: the new text, or the reason it was refused. */
function attempt(f: () => string): string | EditRefused {
  try {
    return f();
  } catch (failed) {
    if (failed instanceof EditRefused) return failed;
    throw failed;
  }
}

/**
 * The lines that differ between `before` and `after`, as hunks of removed (`-`) and added (`+`) lines, each headed by the
 * number of its first line in `before`: what a golden pair shows.
 */
function hunk(before: string, after: string): string {
  if (before === after) return 'unchanged\n';
  const a = before.split(/(?<=\n)/);
  const b = after.split(/(?<=\n)/);
  // the longest common subsequence of lines, from the end
  const common = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) for (let j = b.length - 1; j >= 0; j--) common[i]![j] = a[i] === b[j] ? common[i + 1]![j + 1]! + 1 : Math.max(common[i + 1]![j]!, common[i]![j + 1]!);
  const show = (line: string, sign: string) => `${sign}${line.replace(/\r?\n$/, '')}${line.endsWith('\r\n') ? '␍' : ''}\n`;
  let out = '';
  let open = false;
  let i = 0;
  let j = 0;
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      open = false;
      i++;
      j++;
      continue;
    }
    if (!open) out += `@@ line ${i + 1}\n`;
    open = true;
    if (i < a.length && (j === b.length || common[i + 1]![j]! >= common[i]![j + 1]!)) out += show(a[i++]!, '-');
    else out += show(b[j++]!, '+');
  }
  return out;
}

/** The edits each golden pair makes, built from the view as the screen builds them. */
function cases(text: string): [string, Op[]][] {
  const drawn = view(text, demo);
  const value = parse(text) as Record<string, unknown>;
  const steps = (value.steps as unknown[] | undefined) ?? [];
  const n = steps.length;
  const first = (isRecord(steps[0]) ? steps[0] : {}) as Record<string, unknown>;
  const list: [string, Op[]][] = [
    ['add a validate step first', addStep(drawn, ['steps', 0], 'validate', { required: 'Amount > 0' }, ['required', 'language'])],
    ['add a to step last', addStep(drawn, ['steps', n], 'to', { endpoint: 'partner-domestic' }, ['endpoint', 'byProp', 'endpoints'])],
    ['delete the first step', deleteStep(['steps', 0])],
    ['move the last step first', moveStep(['steps', n - 1], ['steps', 0])],
    ['move the first step last', moveStep(['steps', 0], ['steps', n - 1])],
    ['name the first step', setField(['steps', 0], first, 'name', 'renamed', false)],
    ['take the name of the first step', setField(['steps', 0], first, 'name', undefined, false)],
    ['record all of the first step', setField(['steps', 0], first, 'recording.level', 'all', false)],
    ['write a note of several lines on the first step', setField(['steps', 0], first, 'note', 'first line\nsecond line\n', false)],
    ['write a note the node reads as true', setField(['steps', 0], first, 'note', 'yes', false)],
  ];
  const processes = isRecord(value.processes) ? Object.keys(value.processes) : [];
  if (processes.length > 0) {
    list.push(['move the first step into the first process', moveStep(['steps', 0], ['processes', processes[0]!, 'steps', 0])]);
    list.push(['add a step to the first process', addStep(drawn, ['processes', processes[0]!, 'steps', 1], 'validate', { required: 'true()' })]);
  }
  return list;
}

describe('edit, on golden pairs', () => {
  it.each(files)('edits %s as its golden pairs say', async (name, before) => {
    const out = cases(before).map(([label, ops]) => {
      const after = attempt(() => edit(before, ...ops));
      return `=== ${label}\n${ops.map((op) => `${JSON.stringify(op)}\n`).join('')}${after instanceof EditRefused ? `refused: ${after.message}\n` : hunk(before, after)}`;
    });
    await expect(out.join('\n')).toMatchFileSnapshot(join('golden', 'edits', `${name}.txt`));
  });

  it('keeps the file as it is when the edits change nothing', () => {
    for (const [, text] of files) expect(edit(text)).toBe(text);
  });
});

describe('edit, what it writes', () => {
  const comments = readFileSync(join(fixtures, 'comments.yaml'), 'utf8');
  const processes = readFileSync(join(fixtures, 'processes.yaml'), 'utf8');
  const flow = 'schemaVersion: 1\nkind: flow\nid: x\nfrom: { endpoint: http-in }\nsteps:\n  - type: to\n    endpoint: partner-domestic\n';

  it('takes a step with the comment lines directly above it, leaving one blank line of two', () => {
    expect(edit(comments, { op: 'remove', path: ['steps', 1] })).toBe(comments.replace('  - type: filter\n    when: "/Invoice/Total > 0" # a quoted value\n', ''));
    expect(edit(comments, { op: 'remove', path: ['steps', 0] })).toBe(
      comments.replace('  # before the first step\n  - type: xsd # the type\n    # between keys\n    name: validate\n    schema: flows/invoice.xsd\n', ''),
    );
    expect(spanOf(comments, ['steps', 1])).toEqual({ start: comments.indexOf('  - type: filter'), end: comments.indexOf('  - type: to') });
    const spaced = 'kind: flow\nsteps:\n  - type: a\n\n  # b\n  - type: b\n\n  - type: c\n';
    expect(edit(spaced, { op: 'remove', path: ['steps', 1] })).toBe('kind: flow\nsteps:\n  - type: a\n\n  - type: c\n');
  });

  it('moves a step with its comments, re-indented when the lists are', () => {
    const out = edit(processes, { op: 'move', from: ['steps', 4], path: ['processes', 'spare', 'steps', 0] });
    expect(out).toContain('  spare:\n    steps:\n      - type: to\n        name: hand-over\n        endpoint: order-partner-call\n      - type: xslt\n');
    expect(edit(out, { op: 'move', from: ['processes', 'spare', 'steps', 0], path: ['steps', 4] })).toBe(processes);
  });

  it('writes a text plain only where the browser and the node both read it as that text', () => {
    const write = (value: unknown) => edit(flow, { op: 'add', path: ['steps', 0, 'note'], value }).split('\n').at(-2);
    expect(write('plain text')).toBe('    note: plain text');
    expect(['yes', 'no', 'on', 'off', 'y', 'n', 'true', 'null', '~', '1_000', '0o17', '10:30', '12', '1e3', '#x', 'a: b', ' lead', 'trail ', '', '"q"', '- x', '*a', '&a', '!t', '@x', '%x']
      .map((text) => write(text))).toEqual(
      ['"yes"', '"no"', '"on"', '"off"', '"y"', '"n"', '"true"', '"null"', '"~"', '"1_000"', '"0o17"', '"10:30"', '"12"', '"1e3"', '"#x"', '"a: b"', '" lead"', '"trail "', '""', '"\\"q\\""', '"- x"', '"*a"', '"&a"', '"!t"', '"@x"', '"%x"']
        .map((text) => `    note: ${text}`),
    );
    expect([3, -1.5, true, false, null].map((v) => write(v))).toEqual(['    note: 3', '    note: -1.5', '    note: true', '    note: false', '    note: null']);
  });

  it('writes text of several lines as a literal block with the indicators it needs', () => {
    const write = (value: string) => edit(flow, { op: 'add', path: ['steps', 0, 'note'], value }).slice(flow.length);
    expect(write('a\nb\n')).toBe('    note: |\n      a\n      b\n');
    expect(write('a\nb')).toBe('    note: |-\n      a\n      b\n');
    expect(write('a\nb\n\n')).toBe('    note: |+\n      a\n      b\n\n');
    expect(write('  a\nb\n')).toBe('    note: |2\n        a\n      b\n');
    expect(write('a\n\nb\n')).toBe('    note: |\n      a\n\n      b\n');
    expect(write('tab\there\nx\r\n')).toBe('    note: "tab\\there\\nx\\r\\n"\n');
    for (const text of ['a\nb\n', 'a\nb', 'a\nb\n\n', '  a\nb\n', 'a\n\nb\n', 'tab\there\nx\r\n', '\n\n', ' \n']) {
      const out = edit(flow, { op: 'add', path: ['steps', 0, 'note'], value: text });
      expect((parse(out) as { steps: { note: string }[] }).steps[0]!.note).toBe(text);
    }
  });

  it('writes text of several lines double-quoted inside a mapping on one line', () => {
    const out = edit('kind: flow\nfrom: { endpoint: http-in }\nsteps:\n  - { type: filter, when: x }\n', { op: 'replace', path: ['steps', 0, 'when'], value: 'a\nb' });
    expect(out).toBe('kind: flow\nfrom: { endpoint: http-in }\nsteps:\n  - { type: filter, when: "a\\nb" }\n');
  });

  it('changes a mapping key by key, so a new destination is one new line', () => {
    const out = edit(processes, { op: 'replace', path: ['steps', 2, 'endpoints'], value: { domestic: 'partner-domestic', foreign: 'partner-foreign', spare: 'partner-foreign', euro: 'partner-foreign' } });
    expect(hunk(processes, out)).toBe('@@ line 26\n+      euro: partner-foreign\n');
  });

  it('renames a key in place by a move between two keys of one mapping, keeping its comment', () => {
    const out = edit(comments, { op: 'move', from: ['from', 'endpoint'], path: ['from', 'door'] });
    expect(hunk(comments, out)).toBe('@@ line 8\n-  endpoint: http-in # a comment after the value\n+  door: http-in # a comment after the value\n');
    expect(Object.keys((parse(edit(processes, { op: 'move', from: ['processes', 'spare'], path: ['processes', 'extra'] })) as { processes: object }).processes)).toEqual(['enrich', 'lines', 'extra']);
  });

  it('keeps the comment after a scalar it changes', () => {
    expect(hunk(comments, edit(comments, { op: 'replace', path: ['steps', 0, 'type'], value: 'xslt' }))).toBe('@@ line 13\n-  - type: xsd # the type\n+  - type: xslt # the type\n');
  });

  it('writes a new step in the style of its neighbour', () => {
    const oneLine = readFileSync(join(fixtures, 'one-line.yaml'), 'utf8');
    const out = edit(oneLine, { op: 'add', path: ['processes', 'enrich', 'steps', 1], value: { type: 'validate', required: 'Amount > 0' } });
    expect(hunk(oneLine, out)).toBe('@@ line 13\n+      - { type: validate, required: Amount > 0 }\n');
    const wide = edit(oneLine, { op: 'add', path: ['processes', 'enrich', 'steps', 1], value: { type: 'validate', required: 'x'.repeat(80) } });
    expect(hunk(oneLine, wide)).toBe(`@@ line 13\n+      - type: validate\n+        required: ${'x'.repeat(80)}\n`);
    const wider = 'kind: flow\nsteps:\n-   type: to\n    endpoint: a\n';
    expect(edit(wider, { op: 'add', path: ['steps', 1], value: { type: 'to', endpoint: 'b' } })).toBe(`${wider}-   type: to\n    endpoint: b\n`);
  });

  it('keeps CRLF line ends, and a file that ends without a line break', () => {
    const crlf = comments.replaceAll('\n', '\r\n');
    const out = edit(crlf, { op: 'add', path: ['steps', 3], value: { type: 'to', endpoint: 'partner-foreign' } });
    expect(out).toBe(edit(comments, { op: 'add', path: ['steps', 3], value: { type: 'to', endpoint: 'partner-foreign' } }).replaceAll('\n', '\r\n'));
    expect(edit(flow.slice(0, -1), { op: 'replace', path: ['id'], value: 'z' })).toBe(flow.replace('id: x', 'id: z').slice(0, -1));
  });

  it('writes a new file in block style with two spaces of indent', () => {
    expect(edit('', { op: 'add', path: [], value: { schemaVersion: 1, kind: 'flow', id: 'new', from: { endpoint: 'http-in' }, steps: [{ type: 'to', endpoint: 'partner-domestic' }] } })).toBe(
      'schemaVersion: 1\nkind: flow\nid: new\nfrom:\n  endpoint: http-in\nsteps:\n  - type: to\n    endpoint: partner-domestic\n',
    );
  });
});

describe('edit, what it refuses', () => {
  const refusal = (text: string, ...ops: Op[]) => {
    const out = attempt(() => edit(text, ...ops));
    return out instanceof EditRefused ? out.message : `written: ${out}`;
  };
  const fixture = (name: string) => readFileSync(join(fixtures, name), 'utf8');

  it('refuses an operation that leaves a list of steps empty', () => {
    const flow = 'kind: flow\nsteps:\n  - type: to\n    endpoint: a\n';
    expect(refusal(flow, { op: 'remove', path: ['steps', 0] })).toBe('steps keeps at least one step');
    expect(refusal(fixture('processes.yaml'), { op: 'remove', path: ['processes', 'spare', 'steps', 0] })).toBe('processes.spare.steps keeps at least one step');
    expect(refusal(fixture('processes.yaml'), { op: 'move', from: ['processes', 'spare', 'steps', 0], path: ['steps', 0] })).toBe('processes.spare.steps keeps at least one step');
    expect(refusal(flow, { op: 'replace', path: ['steps'], value: [] })).toBe('steps keeps at least one step');
  });

  it('refuses removing a process a step still calls, and not one that the same edit stops calling', () => {
    const text = fixture('processes.yaml');
    expect(refusal(text, { op: 'remove', path: ['processes', 'lines'] })).toBe('process lines is still called by processes.enrich.steps[1]');
    expect(edit(text, { op: 'remove', path: ['processes', 'spare'] })).not.toContain('spare:\n');
    expect(edit(text, { op: 'remove', path: ['processes', 'enrich', 'steps', 1] }, { op: 'remove', path: ['processes', 'lines'] })).not.toContain('lines:');
  });

  it('refuses the forms it does not change, with the reason, and edits around them', () => {
    const anchors = fixture('anchors.yaml');
    expect(refusal(anchors, { op: 'remove', path: ['steps', 0] })).toBe('steps[0] holds an anchor (&check), which the editor does not change');
    expect(refusal(anchors, { op: 'replace', path: ['steps', 1, '<<'], value: 'x' })).toBe('steps[1] holds a merge key (<<), which the editor does not change');
    expect(hunk(anchors, edit(anchors, { op: 'replace', path: ['steps', 1, 'name'], value: 'twice' }))).toBe('@@ line 14\n-    name: again\n+    name: twice\n');
    expect(refusal(anchors, { op: 'replace', path: ['steps', 2, 'stylesheet'], value: 'x' })).toBe('steps[2].stylesheet holds a tag (!!str), which the editor does not change');
    expect(refusal(anchors, { op: 'add', path: ['limits', 'maxInFlight'], value: 4 })).toBe('limits holds an anchor (&limits), which the editor does not change');
    expect(edit(anchors, { op: 'remove', path: ['steps', 3] })).toBe(anchors.replace('  - type: to\n    endpoint: partner-domestic\n', ''));
    const oneLine = fixture('one-line.yaml');
    expect(refusal(oneLine, { op: 'add', path: ['steps', 1], value: { type: 'to', endpoint: 'a' } })).toBe('steps holds a list written on one line ([ … ]), which the editor does not change');
    expect(refusal(oneLine, { op: 'remove', path: ['processes', 'enrich', 'steps', 1, 'stylesheet'] })).toBe(
      'processes.enrich.steps[1] holds a mapping written over several lines in braces, which the editor does not change',
    );
    expect(refusal(fixture('values.yaml'), { op: 'replace', path: ['steps', 1, 'schema'], value: 'x' })).toBe('steps[1] holds the key schema used twice, which the editor does not change');
    const two = 'kind: flow\nsteps:\n  - type: to\n---\nkind: flow\n';
    expect(edit(two, { op: 'add', path: ['steps', 1], value: { type: 'to' } })).toBe('kind: flow\nsteps:\n  - type: to\n  - type: to\n---\nkind: flow\n');
  });

  it('refuses what it cannot do, and limits what one edit holds', () => {
    const flow = 'kind: flow\nsteps:\n  - type: to\n    endpoint: a\n';
    expect(refusal(flow, { op: 'remove', path: ['steps', 5] })).toBe('there is nothing at steps[5]');
    expect(refusal(flow, { op: 'add', path: ['steps', 3], value: { type: 'to' } })).toBe('steps has no place 3');
    expect(refusal(flow, { op: 'move', from: ['steps', 0], path: ['steps', 0, 'x', 0] })).toBe('a step cannot be moved into itself');
    expect(refusal(flow, { op: 'add', path: ['kind'], value: () => 1 })).toBe('a value of the kind function cannot be written');
    expect(refusal(flow, { op: 'replace', path: [], value: {} })).toBe('the whole file is written only when it is new');
    expect(refusal(flow, ...Array.from({ length: 257 }, () => ({ op: 'replace', path: ['kind'], value: 'flow' }) as Op))).toBe('an edit holds at most 256 operations');
    expect(refusal(flow, { op: 'add', path: Array.from({ length: 33 }, () => 'a'), value: 1 })).toBe('a path is at most 32 steps deep');
    expect(refusal('kind: flow\nid: [unclosed\n', { op: 'remove', path: ['kind'] })).toMatch(/^the file is not YAML/);
  });

  it('changes nothing when one of its operations is refused', () => {
    const text = fixture('processes.yaml');
    expect(refusal(text, { op: 'remove', path: ['steps', 0] }, { op: 'remove', path: ['processes', 'spare', 'steps', 0] })).toBe('processes.spare.steps keeps at least one step');
  });
});

describe('follow', () => {
  it('carries a path through the steps added, removed and moved before it', () => {
    expect(follow(['steps', 2, 'name'], [{ op: 'add', path: ['steps', 0], value: {} }])).toEqual(['steps', 3, 'name']);
    expect(follow(['steps', 2], [{ op: 'add', path: ['steps', 3], value: {} }])).toEqual(['steps', 2]);
    expect(follow(['steps', 2], [{ op: 'remove', path: ['steps', 0] }])).toEqual(['steps', 1]);
    expect(follow(['steps', 2], [{ op: 'remove', path: ['steps', 2] }])).toBeNull();
    expect(follow(['steps', 2, 'name'], [{ op: 'remove', path: ['steps', 2] }])).toBeNull();
    expect(follow(['steps', 2], [{ op: 'move', from: ['steps', 2], path: ['processes', 'p', 'steps', 0] }])).toEqual(['processes', 'p', 'steps', 0]);
    expect(follow(['steps', 1], [{ op: 'move', from: ['steps', 0], path: ['steps', 2] }])).toEqual(['steps', 0]);
    expect(follow(['steps', 3], [{ op: 'move', from: ['steps', 0], path: ['steps', 2] }])).toEqual(['steps', 3]);
    expect(follow(['processes', 'a', 'steps', 0], [{ op: 'move', from: ['processes', 'a'], path: ['processes', 'b'] }])).toEqual(['processes', 'b', 'steps', 0]);
    expect(follow(['processes', 'a', 'steps', 0], [{ op: 'remove', path: ['processes', 'b'] }])).toEqual(['processes', 'a', 'steps', 0]);
  });

  it('keeps a path inside a replaced value while the new value holds it', () => {
    expect(follow(['steps', 0, 'routes', 1], [{ op: 'replace', path: ['steps', 0, 'routes'], value: [{}, {}] }])).toEqual(['steps', 0, 'routes', 1]);
    expect(follow(['steps', 0, 'routes', 1], [{ op: 'replace', path: ['steps', 0, 'routes'], value: [{}] }])).toBeNull();
    expect(follow(['steps', 0], [{ op: 'replace', path: ['steps', 0, 'name'], value: 'x' }])).toEqual(['steps', 0]);
  });

  it('follows the steps of two conditions that swap', () => {
    expect(follow(['steps', 3, 'when', 0, 'steps', 1], [{ op: 'move', from: ['steps', 3, 'when', 1], path: ['steps', 3, 'when', 0] }])).toEqual(['steps', 3, 'when', 1, 'steps', 1]);
  });
});

describe('the operations the screen builds', () => {
  const text = readFileSync(join(fixtures, 'processes.yaml'), 'utf8');
  const drawn = view(text, demo);

  it('adds a step with its keys in order, and a free name when its default is taken', () => {
    expect(addStep(drawn, ['steps', 1], 'to', { recording: { level: 'all' }, endpoint: 'partner-domestic', retryable: true, extra: 1 }, ['byProp', 'endpoint'])).toEqual([
      { op: 'add', path: ['steps', 1], value: { type: 'to', name: 'to-2', endpoint: 'partner-domestic', extra: 1, retryable: true, recording: { level: 'all' } } },
    ]);
    expect(addStep(drawn, ['steps', 1], 'process', { process: 'lines' })).toEqual([{ op: 'add', path: ['steps', 1], value: { type: 'process', process: 'lines' } }]);
    expect(addStep(drawn, ['processes', 'enrich', 'steps', 0], 'process', { process: 'lines' })).toEqual([
      { op: 'add', path: ['processes', 'enrich', 'steps', 0], value: { type: 'process', name: 'lines-2', process: 'lines' } },
    ]);
    expect(addStep(drawn, ['processes', 'lines', 'steps', 0], 'validate', { required: 'x' })).toEqual([
      { op: 'add', path: ['processes', 'lines', 'steps', 0], value: { type: 'validate', name: 'validate-2', required: 'x' } },
    ]);
    expect(addStep(drawn, ['steps', 0], 'split', { name: 'mine', xpath: '/a' })).toEqual([{ op: 'add', path: ['steps', 0], value: { type: 'split', name: 'mine', xpath: '/a' } }]);
  });

  it('cuts a default name so that its number fits in 64 characters', () => {
    const long = 'a'.repeat(64);
    const flow = `schemaVersion: 1\nkind: flow\nid: x\nfrom: { endpoint: http-in }\nsteps:\n  - type: ${long}\n  - type: ${long}\n    name: ${'a'.repeat(62)}-2\n`;
    expect(addStep(view(flow, demo), ['steps', 2], long)).toEqual([{ op: 'add', path: ['steps', 2], value: { type: long, name: `${'a'.repeat(62)}-3` } }]);
  });

  it('moves and deletes a step by its path', () => {
    expect(moveStep(['steps', 0], ['steps', 2])).toEqual([{ op: 'move', from: ['steps', 0], path: ['steps', 2] }]);
    expect(deleteStep(['steps', 0])).toEqual([{ op: 'remove', path: ['steps', 0] }]);
  });

  it('sets a field: an emptied optional field loses its key, a required one keeps empty text', () => {
    const values = { type: 'route', prop: 'P', recording: { level: 'all' }, otherwise: 'x' };
    expect(setField(['steps', 1], values, 'otherwise', undefined, false)).toEqual([{ op: 'remove', path: ['steps', 1, 'otherwise'] }]);
    expect(setField(['steps', 1], values, 'prop', undefined, true)).toEqual([{ op: 'replace', path: ['steps', 1, 'prop'], value: '' }]);
    expect(setField(['steps', 1], values, 'prop', 'P', true)).toEqual([]);
    expect(setField(['steps', 1], values, 'name', undefined, false)).toEqual([]);
    expect(setField(['steps', 1], values, 'recording.level', undefined, false)).toEqual([{ op: 'remove', path: ['steps', 1, 'recording'] }]);
    expect(setField(['steps', 1], values, 'recording.payload', 'keep', false)).toEqual([{ op: 'add', path: ['steps', 1, 'recording', 'payload'], value: 'keep' }]);
    expect(setField(['steps', 1], { type: 'to' }, 'recording.level', 'off', false)).toEqual([{ op: 'add', path: ['steps', 1, 'recording'], value: { level: 'off' } }]);
    expect(setField(['steps', 1], { type: 'to', recording: { level: 'all', payload: 'keep' } }, 'recording.level', undefined, false)).toEqual([
      { op: 'remove', path: ['steps', 1, 'recording', 'level'] },
    ]);
  });

  it('turns the keys of a raw editor into operations', () => {
    expect(setKeys(['steps', 0], { type: 'x', a: 1, b: 2 }, { type: 'x', b: 3, c: 4 })).toEqual([
      { op: 'remove', path: ['steps', 0, 'a'] },
      { op: 'replace', path: ['steps', 0, 'b'], value: 3 },
      { op: 'add', path: ['steps', 0, 'c'], value: 4 },
    ]);
  });
});

describe('a made-up branching type, edited', () => {
  const types: Record<string, StepType> = { ...BUILT_IN, choose: { fields: [], stepLists: [['when', '*', 'steps']], detail: 'prop' } };
  const change = editor(types);
  const text = `schemaVersion: 1
kind: flow
id: branching
from: { endpoint: http-in }
steps:
  - type: choose
    when:
      - test: "/Invoice/Currency = 'CZK'"
        steps:
          - type: xsd
            schema: flows/invoice.xsd
          - type: validate
            required: "Amount > 0"
      - test: "true()"
        steps:
          - type: to
            endpoint: partner-foreign
  - type: to
    endpoint: partner-domestic
`;

  it('adds, moves and deletes the steps of a branch', () => {
    const added = change(text, { op: 'add', path: ['steps', 0, 'when', 1, 'steps', 0], value: { type: 'validate', required: 'x' } });
    expect(hunk(text, added)).toBe('@@ line 16\n+          - type: validate\n+            required: x\n');
    const moved = change(text, { op: 'move', from: ['steps', 0, 'when', 0, 'steps', 1], path: ['steps', 0, 'when', 1, 'steps', 1] });
    expect((parse(moved) as { steps: { when: { steps: unknown[] }[] }[] }).steps[0]!.when.map((w) => w.steps.length)).toEqual([1, 2]);
    const out = change(text, { op: 'move', from: ['steps', 1], path: ['steps', 0, 'when', 0, 'steps', 0] });
    expect(out).toContain('        steps:\n          - type: to\n            endpoint: partner-domestic\n          - type: xsd\n');
    expect(change(text, { op: 'remove', path: ['steps', 0, 'when', 0, 'steps', 0] })).not.toContain('flows/invoice.xsd');
  });

  it('refuses to empty a branch, and swaps two branches whole', () => {
    expect(attempt(() => change(text, { op: 'remove', path: ['steps', 0, 'when', 1, 'steps', 0] }))).toEqual(new EditRefused('steps[0].when[1].steps keeps at least one step'));
    const swapped = change(text, { op: 'move', from: ['steps', 0, 'when', 1], path: ['steps', 0, 'when', 0] });
    expect((parse(swapped) as { steps: { when: { test: string }[] }[] }).steps[0]!.when.map((w) => w.test)).toEqual(['true()', "/Invoice/Currency = 'CZK'"]);
    expect(stepLists(parse(swapped), types)).toContainEqual(['steps', 0, 'when', 1, 'steps']);
  });
});

// ---------------------------------------------------------------------------------------------------------------------
// The property test: random series of operations on every file.

/** A small seeded random source, so a failure can be run again. */
function random(seed: number) {
  let s = seed >>> 0;
  const next = () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return { int: (n: number) => Math.floor(next() * n), pick: <T,>(items: readonly T[]) => items[Math.floor(next() * items.length)]! };
}

/** The value the operations make, applied to a model whose step objects keep their identity through moves. */
function applyModel(root: Record<string, unknown>, op: Op) {
  const holder = (path: Path) => valueAt(root, path.slice(0, -1)) as Record<string, unknown> | unknown[];
  const key = (path: Path) => path.at(-1)!;
  switch (op.op) {
    case 'add':
    case 'replace': {
      const parent = holder(op.path);
      const value = structuredClone(op.value);
      if (Array.isArray(parent) && op.op === 'add') parent.splice(key(op.path) as number, 0, value);
      else if (Array.isArray(parent)) parent[key(op.path) as number] = value;
      else parent[key(op.path) as string] = value;
      return;
    }
    case 'remove': {
      const parent = holder(op.path);
      if (Array.isArray(parent)) parent.splice(key(op.path) as number, 1);
      else delete parent[key(op.path) as string];
      return;
    }
    case 'move': {
      const parent = holder(op.from);
      if (Array.isArray(parent)) {
        const [moved] = parent.splice(key(op.from) as number, 1);
        (holder(op.path) as unknown[]).splice(key(op.path) as number, 0, moved);
        return;
      }
      const entries = Object.entries(parent).map(([k, v]) => [k === key(op.from) ? (key(op.path) as string) : k, v] as const);
      for (const k of Object.keys(parent)) delete parent[k];
      for (const [k, v] of entries) parent[k] = v;
    }
  }
}

const TEXTS = ['plain', 'yes', 'no', '1_000', '0o17', '10:30', '#x', 'a: b', ' lead', 'trail ', '', "it's", 'a\nb', 'a\nb\n', 'a\nb\n\n', '  a\nb\n', 'a\n\nb\n'];
const VALUES: unknown[] = [...TEXTS, 3, -1.5, true, false, undefined];
const NEW_STEPS: [string, Record<string, unknown>][] = [
  ['validate', { required: 'Amount > 0' }],
  ['to', { endpoint: 'partner-domestic' }],
  ['xsd', { schema: 'flows/invoice.xsd', retryable: true }],
  ['filter', { when: 'a\nb\n' }],
  ['split', { xpath: '/Invoice/Line', recording: { level: 'off' } }],
];

/** One random operation on the flow `value`, drawn as `drawn`, or null when the flow offers none of the kind drawn. */
function randomOps(r: ReturnType<typeof random>, value: Record<string, unknown>, drawn: FlowView): { ops: Op[]; step?: Path; kind: string } | null {
  const lists = stepLists(value);
  const list = r.pick(lists);
  const items = valueAt(value, list) as unknown[];
  const i = r.int(items.length);
  const step = [...list, i];
  const values = (isRecord(items[i]) ? items[i] : {}) as Record<string, unknown>;
  switch (r.int(6)) {
    case 0: {
      const [type, settings] = r.pick(NEW_STEPS);
      return { ops: addStep(drawn, [...list, r.int(items.length + 1)], type, settings), kind: 'add' };
    }
    case 1:
      return items.length > 1 ? { ops: deleteStep(step), kind: 'remove' } : null;
    case 2: {
      if (items.length < 2) return null;
      const to = r.pick(lists);
      const size = (valueAt(value, to) as unknown[]).length - (to === list ? 1 : 0);
      const index = r.int(size + 1);
      return to === list && index === i ? null : { ops: moveStep(step, [...to, index]), kind: 'move' };
    }
    case 3:
    case 4: {
      const keys = Object.keys(values).filter((k) => k !== 'type' && typeof values[k] !== 'object');
      const name = r.pick([...keys, 'name', 'note', 'retryable', 'recording.level']);
      return { ops: setField(step, values, name, r.pick(VALUES), false), step, kind: 'set' };
    }
    default: {
      const keys = Object.keys(values).filter((k) => k !== 'type' && !Object.hasOwn(values, `${k}Old`));
      if (keys.length === 0) return null;
      const k = r.pick(keys);
      return { ops: [{ op: 'move', from: [...step, k], path: [...step, `${k}Old`] }], step, kind: 'rename' };
    }
  }
}

/** Whether the lines of `outside` stand in `text`, in order. */
function inOrder(outside: string[], text: string): boolean {
  const lines = text.split(/(?<=\n)/);
  let at = 0;
  for (const line of outside) {
    while (at < lines.length && lines[at] !== line) at++;
    if (at === lines.length) return false;
    at++;
  }
  return true;
}

const cut = (text: string, span: { start: number; end: number }) => `${text.slice(0, span.start)}${text.slice(span.end)}`;

describe('edit, on random series of operations', () => {
  const SERIES = 12;
  const LENGTH = 6;

  it.each(files)('keeps its promises on %s', (name, file) => {
    for (let series = 0; series < SERIES; series++) {
      const r = random(series * 7919 + name.length);
      let text = file;
      const model = parse(text) as Record<string, unknown>;
      // every step of the file, by where it stands now
      const tracked = new Map<object, Path>(stepLists(model).flatMap((l) => (valueAt(model, l) as unknown[]).map((s, i) => [s as object, [...l, i]] as [object, Path])));
      for (let n = 0; n < LENGTH; n++) {
        const drawn = view(text, demo);
        const made = randomOps(r, model, drawn);
        if (made === null || made.ops.length === 0) continue;
        const where = `${name}, series ${series}, op ${n}: ${JSON.stringify(made.ops)}`;
        const after = attempt(() => edit(text, ...made.ops));
        if (after instanceof EditRefused) continue;
        for (const op of made.ops) applyModel(model, op);

        // it reads back, with the keys in order
        expect(JSON.stringify(parse(after)), where).toBe(JSON.stringify(model));

        // the bytes outside the spans the operations touch stay
        const op = made.ops[0]!;
        if (made.kind === 'add') expect(cut(after, spanOf(after, op.path)), where).toBe(text);
        else if (made.kind === 'remove') expect(cut(text, spanOf(text, op.path)), where).toBe(after);
        else if (op.op === 'move' && made.kind === 'move') expect(inOrder(cut(text, spanOf(text, op.from)).split(/(?<=\n)/), after), where).toBe(true);
        else {
          const span = spanOf(text, made.step!);
          expect(after.slice(0, span.start), where).toBe(text.slice(0, span.start));
          expect(after.slice(after.length - (text.length - span.end)), where).toBe(text.slice(span.end));
        }

        // the view draws the steps the operations make
        const next = view(after, demo);
        for (const l of stepLists(model)) {
          (valueAt(model, l) as unknown[]).forEach((s, i) => expect(stepNode(next, [...l, i])?.type, where).toBe((s as { type?: unknown }).type));
        }

        // a path carried by follow stays on its step
        for (const [step, path] of tracked) {
          const moved = follow(path, made.ops);
          if (moved === null) {
            expect(stepLists(model).some((l) => (valueAt(model, l) as unknown[]).includes(step)), where).toBe(false);
            tracked.delete(step);
          } else {
            expect(valueAt(model, moved), where).toBe(step);
            tracked.set(step, moved);
          }
        }
        text = after;
      }
    }
  });
});
