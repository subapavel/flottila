import catalogueText from '../../../../../contract/function-catalogue.json?raw';
import { describe, expect, test } from 'vitest';
import type { FunctionCatalogue } from '../../../api/client';
import type { TargetFieldMapping } from '../../../generated/mapping-model';
import { expressionGraph, inspectExpression, nodeHeight, TARGET, upstream, wireEnd } from './expression-graph';
import { arrange, layoutOf, positionsOf, shownPositions } from './expression-layout';
import { drilldown } from './function-groups';

const catalogue = JSON.parse(catalogueText) as FunctionCatalogue;
const ORDER = '/Q{urn:flottila:corpus:skeleton}Order';
const ROW = `${ORDER}/Row`;

/** Row: Sku into removeContexts, the result; a concat with one input wired; a floating constant. */
const mapping: TargetFieldMapping = {
  id: 't1',
  result: 'fn',
  boxes: {
    sku: { kind: 'source', field: `${ORDER}/Line/Sku` },
    fn: { kind: 'function', fn: 'removeContexts', inputs: ['sku'] },
    cc: { kind: 'function', fn: 'concat', inputs: ['sku', null] },
    k: { kind: 'function', fn: 'constant', params: { value: 'x' } },
    u: { kind: 'unresolved', name: 'po:lookup', origin: 'po', inputs: ['zz', 'sku'] },
  },
};

describe('the expression graph (the prototype’s graph of a target field)', () => {
  test('a node per box in id order, then the target; a wire per connected slot to a box of the mapping, then the result', () => {
    const graph = expressionGraph(ROW, mapping, catalogue);

    expect(graph.nodes.map((n) => [n.id, n.kind, n.name])).toEqual([
      ['cc', 'function', 'concat'],
      ['fn', 'function', 'removeContexts'],
      ['k', 'function', 'constant'],
      ['sku', 'source', 'Sku'],
      ['u', 'function', 'po:lookup'],
      [TARGET, 'target', 'Row'],
    ]);
    expect(graph.nodes[0]!.inputs.map((p) => p.label)).toEqual(['a', 'b']);
    expect(graph.nodes[4]!.inputs.map((p) => p.label)).toEqual(['1', '2']);
    expect(graph.nodes[2]!.params).toEqual({ value: 'x' });
    expect(graph.edges.map((e) => e.id)).toEqual(['sku->cc#0', 'sku->fn#0', 'sku->u#1', 'fn->:target']);
  });

  test('a target field without a mapping shows its target alone', () => {
    expect(expressionGraph(ROW, undefined, catalogue)).toEqual({
      nodes: [{ id: TARGET, kind: 'target', name: 'Row', path: ROW, inputs: [{ id: 'in0', label: 'Row', slot: 0 }], output: false }],
      edges: [],
    });
  });

  test('a UDF box takes its input names from its declaration, and a node is one 48 px row per three inputs', () => {
    const graph = expressionGraph(ROW, { id: 't', result: null, boxes: { x: { kind: 'udf', udf: 'f', inputs: [null, null, null, null] } } }, catalogue, {
      f: { executionKind: 'single', inputs: ['p', 'q'], source: 'A.java#f' },
    });

    expect(graph.nodes[0]!.inputs.map((p) => p.label)).toEqual(['p', 'q', '3', '4']);
    expect(nodeHeight(graph.nodes[0]!)).toBe(96);
    expect(nodeHeight(graph.nodes[1]!)).toBe(48);
  });

  test('states: complete on a full path, incomplete with an open input, detached off the path', () => {
    const { states, floating } = inspectExpression(expressionGraph(ROW, { ...mapping, result: 'cc' }, catalogue));

    expect(states.get(TARGET)).toMatchObject({ kind: 'incomplete', description: 'Incomplete: Complete the input from concat.' });
    expect(states.get('cc')).toEqual({ kind: 'incomplete', description: 'Incomplete: Connect b.', missing: ['in1'] });
    expect(states.get('sku')).toMatchObject({ kind: 'complete' });
    expect(states.get('fn')).toMatchObject({ kind: 'detached', description: 'Detached: this branch has no path to the target.' });
    expect(states.get('k')).toMatchObject({ kind: 'detached', description: 'Detached: this object has no connections.' });
    expect(floating).toEqual(['fn', 'k', 'u']);
  });

  test('a cycle of a hand-written model reads as incomplete', () => {
    const cycle: TargetFieldMapping = { id: 't', result: 'a', boxes: { a: { kind: 'function', fn: 'trim', inputs: ['b'] }, b: { kind: 'function', fn: 'trim', inputs: ['a'] } } };

    expect(inspectExpression(expressionGraph(ROW, cycle, catalogue)).states.get('a')?.kind).toBe('incomplete');
  });

  test('upstream holds a box and every box it reads', () => {
    expect([...upstream(expressionGraph(ROW, mapping, catalogue), 'u')].sort()).toEqual(['sku', 'u']);
  });

  test('a wire end is the target, or the slot of a handle', () => {
    expect(wireEnd(TARGET, 'in0')).toBe('target');
    expect(wireEnd('cc', 'in1')).toEqual({ box: 'cc', slot: 1 });
    expect(wireEnd('cc', null)).toEqual({ box: 'cc', slot: 0 });
  });
});

describe('the expression layout (ADR-0029)', () => {
  const graph = expressionGraph(ROW, { id: 't1', result: 'fn', boxes: { sku: mapping.boxes.sku!, fn: mapping.boxes.fn! } }, catalogue);

  test('arrange lays out left to right into the target, integer positions, the same each time', () => {
    const positions = arrange(graph);

    expect(positions.sku!.x).toBeLessThan(positions.fn!.x);
    expect(positions.fn!.x).toBeLessThan(positions[TARGET]!.x);
    expect(Object.values(positions).every((p) => Number.isInteger(p.x) && Number.isInteger(p.y))).toBe(true);
    expect(arrange(graph)).toEqual(positions);
  });

  test('a mapping without a layout shows the dagre layout', () => {
    expect(shownPositions(graph, undefined)).toEqual(arrange(graph));
    expect(shownPositions(graph, { boxes: { gone: { x: 1, y: 1 } } })).toEqual(arrange(graph));
  });

  test('kept positions stay; a node without one goes left of what it feeds, right of what feeds it, else below', () => {
    expect(shownPositions(graph, { target: { x: 500, y: 100 }, boxes: { sku: { x: 0, y: 100 } } }).fn).toEqual({ x: 272, y: 100 });
    expect(shownPositions(graph, { boxes: { sku: { x: 0, y: 100 } } })[TARGET]).toEqual({ x: 456, y: 100 });

    const loose = expressionGraph(ROW, { id: 't', result: null, boxes: { a: { kind: 'function', fn: 'trim' } } }, catalogue);
    expect(shownPositions(loose, { target: { x: 40, y: 10 } }).a).toEqual({ x: 40, y: 82 });
  });

  test('a spot another node covers moves down below it', () => {
    const shown = shownPositions(graph, { target: { x: 500, y: 100 }, boxes: { sku: { x: 300, y: 110 } } });

    expect(shown.fn).toEqual({ x: 272, y: 182 });
  });

  test('positions and a layout convert both ways', () => {
    const layout = { target: { x: 1, y: 2 }, boxes: { a: { x: 3, y: 4 } } };

    expect(layoutOf(positionsOf(layout))).toEqual(layout);
    expect(layoutOf({ a: { x: 3, y: 4 } })).toEqual({ boxes: { a: { x: 3, y: 4 } } });
    expect(positionsOf(undefined)).toEqual({});
  });
});

describe('the functions drilldown (spec #152)', () => {
  test('lists the 65 catalogue functions under the prototype groups, with the 11 functions the catalogue lacks disabled', () => {
    const groups = drilldown(catalogue);

    expect(groups.map((g) => [g.group, g.functions.length])).toEqual([
      ['Arithmetic', 21],
      ['Boolean', 10],
      ['Text', 16],
      ['Conversions', 4],
      ['Date', 5],
      ['Constants', 5],
      ['Node Functions', 11],
      ['Statistic', 4],
    ]);
    const all = groups.flatMap((g) => g.functions);
    expect(all.filter((f) => f.fn !== undefined)).toHaveLength(65);
    expect(all.filter((f) => f.fn === undefined).map((f) => [f.name, f.issue])).toEqual([
      ['ifS', 172],
      ['ifSWithoutElse', 172],
      ['indexOf', 172],
      ['lastIndexOf', 172],
      ['startsWith', 172],
      ['Value mapping', 166],
      ['JDBC Lookup', 165],
      ['RFC Lookup', 165],
      ['sender', 172],
      ['receiver', 172],
      ['xsi:nil', 172],
    ]);
  });

  test('renamed functions carry our name; overloads show their input count; configurable ones say so', () => {
    const arithmetic = drilldown(catalogue)[0]!.functions;

    expect(arithmetic.map((f) => f.name)).toContain('inv');
    expect(arithmetic.find((f) => f.name === 'counter')?.configurable).toBe(true);
    expect(drilldown(catalogue)[2]!.functions.filter((f) => f.name === 'indexOf').map((f) => f.overload)).toEqual([2, 3]);
  });

  test('a catalogue function the prototype does not list goes to its family group, an unknown family to Node Functions', () => {
    const extra: FunctionCatalogue = {
      ...catalogue,
      zeta: { family: 'text', executionKind: 'single', portable: true, fidelity: { level: 'exact' } },
      omega: { family: 'misc', executionKind: 'single', portable: true, fidelity: { level: 'exact' } },
    };

    const groups = drilldown(extra);
    expect(groups[2]!.functions.at(-1)?.name).toBe('zeta');
    expect(groups[6]!.functions.at(-1)?.name).toBe('omega');
  });
});
