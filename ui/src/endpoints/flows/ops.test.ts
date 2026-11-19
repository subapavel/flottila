import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { edit, EditRefused } from './edit';
import { addEntry, addProcess, addRow, callable, callOf, deleteProcess, moveRow, moveToList, removeRow, renameEntry, renameProcess, stepListsOf } from './ops';
import type { FlowContext } from './types';
import { view } from './view';

const context: FlowContext = {
  endpoints: [
    { id: 'http-in', path: 'demo/endpoints/http-in.yaml', scheme: 'http', direction: 'in' },
    { id: 'order-partner-call', path: 'demo/endpoints/order-partner-call.yaml', scheme: 'direct', direction: 'out', address: '/orders/partner' },
    { id: 'partner-domestic', path: 'demo/endpoints/partner-domestic.yaml', scheme: 'http', direction: 'out' },
    { id: 'partner-foreign', path: 'demo/endpoints/partner-foreign.yaml', scheme: 'http', direction: 'out' },
  ],
  flows: [
    { path: 'demo/flows/processes.yaml', id: 'processes', mode: 'at-least-once', address: '/self' },
    { path: 'demo/flows/order-partner.yaml', id: 'order-partner', mode: 'sync', address: '/orders/partner' },
    { path: 'demo/flows/invoice.yaml', id: 'invoice', mode: 'sync', address: '/invoices' },
    { path: 'demo/flows/plain.yaml', id: 'plain', mode: 'sync' },
  ],
  partial: false,
  steps: [{ type: 'xslt', configSchema: { type: 'object', properties: { stylesheet: { type: 'string' } } } }],
};

const text = readFileSync(join(__dirname, 'fixtures', 'processes.yaml'), 'utf8');
const drawn = view(text, context);
const flow = parse(text) as Record<string, unknown>;
const refusal = (f: () => unknown) => {
  try {
    f();
  } catch (failed) {
    if (failed instanceof EditRefused) return failed.message;
    throw failed;
  }
  return null;
};

describe('the operations on processes', () => {
  it('rename a process in place and in every call of it, the calls inside processes too', () => {
    const ops = renameProcess(drawn, flow, 'lines', 'parts');
    expect(ops).toEqual([
      { op: 'move', from: ['processes', 'lines'], path: ['processes', 'parts'] },
      { op: 'replace', path: ['processes', 'enrich', 'steps', 1, 'process'], value: 'parts' },
    ]);
    const out = edit(text, ...ops);
    expect(out).toBe(text.replace('        process: lines\n', '        process: parts\n').replace('  lines:\n', '  parts:\n'));
    expect(view(out, context).problems).toEqual(drawn.problems);
  });

  it('refuse a rename that gives a call without a name the name of another step of its list', () => {
    expect(refusal(() => renameProcess(drawn, flow, 'lines', 'extractKey'))).toBe(
      'processes.enrich.steps[1] has no name and would be named extractKey, the name of processes.enrich.steps[0] in its list',
    );
    expect(refusal(() => renameProcess(drawn, flow, 'lines', 'spare'))).toBe('the flow has a process spare already');
    expect(refusal(() => renameProcess(drawn, flow, 'lines', 'Lines'))).toContain('a process name matches');
    // a call with a name keeps it
    expect(renameProcess(drawn, flow, 'enrich', 'first')).toHaveLength(3);
  });

  it('add a process with one step, and processes itself to a flow that has none', () => {
    expect(addProcess(drawn, flow, 'audit', 'validate')).toEqual([{ op: 'add', path: ['processes', 'audit'], value: { steps: [{ type: 'validate' }] } }]);
    const plain = 'schemaVersion: 1\nkind: flow\nid: plain\nsteps:\n  - type: to\n    endpoint: partner-domestic\n';
    const ops = addProcess(view(plain, context), parse(plain) as Record<string, unknown>, 'audit', 'validate');
    expect(edit(plain, ...ops)).toBe(`${plain}processes:\n  audit:\n    steps:\n      - type: validate\n`);
    expect(refusal(() => addProcess(drawn, flow, 'enrich', 'validate'))).toBe('the flow has a process enrich already');
  });

  it('delete a process no step calls, and processes with the last one; a called one is refused by edit', () => {
    expect(edit(text, ...deleteProcess(flow, 'spare'))).toBe(text.replace(/ {2}spare:\n[\s\S]*$/, ''));
    expect(refusal(() => edit(text, ...deleteProcess(flow, 'lines')))).toBe('process lines is still called by processes.enrich.steps[1]');
    const one = 'schemaVersion: 1\nkind: flow\nid: one\nsteps:\n  - type: to\n    endpoint: a\nprocesses:\n  spare:\n    steps:\n      - type: to\n        endpoint: b\n';
    expect(edit(one, ...deleteProcess(parse(one) as Record<string, unknown>, 'spare'))).toBe('schemaVersion: 1\nkind: flow\nid: one\nsteps:\n  - type: to\n    endpoint: a\n');
  });

  it('move a step to the end of another lane', () => {
    expect(stepListsOf(drawn)).toEqual([['steps'], ['processes', 'enrich', 'steps'], ['processes', 'lines', 'steps'], ['processes', 'spare', 'steps']]);
    const ops = moveToList(drawn, ['steps', 4], ['processes', 'spare', 'steps']);
    expect(ops).toEqual([{ op: 'move', from: ['steps', 4], path: ['processes', 'spare', 'steps', 1] }]);
    expect(parse(edit(text, ...ops)).processes.spare.steps[1]).toEqual({ type: 'to', name: 'hand-over', endpoint: 'order-partner-call' });
  });
});

describe('the operations on the rows of a list or a map field', () => {
  const route = flow.steps as Record<string, unknown>[];

  it('add, move and remove a rule of a route', () => {
    const added = edit(text, ...addRow(['steps', 1], route[1]!, 'routes', { when: 'true()', set: '' }));
    expect(added).toContain(`        set: euro\n      - when: true()\n        set: ""\n    otherwise: foreign\n`);
    const moved = edit(text, ...moveRow(['steps', 1, 'routes'], 1, 0));
    expect((parse(moved).steps[1].routes as { set: string }[]).map((r) => r.set)).toEqual(['euro', 'domestic']);
    const removed = edit(text, ...removeRow(['steps', 1, 'routes'], 0));
    expect(removed).toBe(text.replace(`      - when: "/Invoice/Currency = 'CZK'"\n        set: domestic\n`, ''));
  });

  it('add a field with its first row when it is not set', () => {
    expect(addRow(['steps', 4], route[4]!, 'routes', { when: '', set: '' })).toEqual([{ op: 'add', path: ['steps', 4, 'routes'], value: [{ when: '', set: '' }] }]);
    expect(addEntry(['steps', 4], route[4]!, 'endpoints', '')).toEqual([{ op: 'add', path: ['steps', 4, 'endpoints'], value: { value: '' } }]);
  });

  it('add an entry under a free key, rename its key in place and remove it', () => {
    const at = ['steps', 2, 'endpoints'];
    expect(addEntry(['steps', 2], route[2]!, 'endpoints', 'partner-domestic')).toEqual([{ op: 'add', path: [...at, 'value'], value: 'partner-domestic' }]);
    const renamed = edit(text, ...renameEntry(at, 'spare', 'other'));
    expect(renamed).toBe(text.replace('      spare: partner-foreign\n', '      other: partner-foreign\n'));
    expect(edit(text, ...removeRow(at, 'spare'))).toBe(text.replace('      spare: partner-foreign\n', ''));
    expect(renameEntry(at, 'spare', 'spare')).toEqual([]);
  });
});

describe('a call of another flow', () => {
  it('can call the flows that serve a direct address, but the flow itself', () => {
    expect(callable(context, 'demo/flows/processes.yaml').map((f) => f.id)).toEqual(['order-partner', 'invoice']);
  });

  it('goes through the direct out endpoint of the package that the flow called serves', () => {
    expect(callOf(context, 'demo/flows/processes.yaml', { id: 'order-partner', address: '/orders/partner' })).toEqual({ endpoint: 'order-partner-call' });
  });

  it('goes through a new endpoint file beside the out endpoints when there is none', () => {
    expect(callOf(context, 'demo/flows/processes.yaml', { id: 'invoice', address: '/invoices' })).toEqual({
      endpoint: 'call-invoice',
      create: {
        path: 'demo/endpoints/call-invoice.yaml',
        text: 'schemaVersion: 1\nkind: endpoint\nid: call-invoice\nscheme: direct\ndirection: out\nconfig:\n  address: /invoices\n',
      },
    });
    const taken = { endpoints: [...context.endpoints, { id: 'call-invoice', path: 'demo/endpoints/x.yaml', scheme: 'http', direction: 'out' as const }] };
    expect(callOf(taken, 'demo/flows/processes.yaml', { id: 'invoice', address: '/invoices' }).create?.path).toBe('demo/endpoints/call-invoice-2.yaml');
    expect(callOf({ endpoints: [] }, 'demo/flows/processes.yaml', { id: 'invoice', address: '/invoices' }).create?.path).toBe('demo/flows/call-invoice.yaml');
  });
});
