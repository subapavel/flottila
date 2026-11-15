import { describe, expect, test } from 'vitest';
import { builtIn, declaration, emptyFunctions, metadata, moveRow, nameIssue, newFunction, readFunctions, unusedName, variable, type Variable } from './function-model';

const argument = (name: string): Variable => ({ id: name, kind: 'Argument', name, javaType: 'String', title: '' });
const parameter = (name: string, javaType = 'String'): Variable => ({ id: name, kind: 'Parameter', name, javaType, title: '' });

describe('metadata: the class from the mapping name, the Java package from its mapping package', () => {
  test.each([
    ['mappings/order-id-copy', 'order_id_copy', 'mappings'],
    ['order-id-copy', 'order_id_copy', ''],
    ['a/b-c/3d', '_3d', 'a.b_c'],
    ['new/class', 'class_', 'new_'],
    ['orders/Order$Copy', 'Order$Copy', 'orders'],
  ])('%s is class %s in package "%s"', (id, className, packageName) => {
    expect(metadata(id)).toEqual({ className, packageName });
  });
});

describe('declaration in the method shape of ADR-0020', () => {
  test('a single-value function returns a String and takes its arguments, box parameters and the mapping run', () => {
    expect(declaration({ name: 'f', execution: 'Single value', variables: [argument('a'), parameter('sep'), parameter('n', 'int')] })).toBe(
      'public static String f(String a, @Parameter("sep") String sep, @Parameter("n") int n, MappingRun run) {',
    );
  });

  test.each([
    ['Context', 'public static void f(Context a, @Parameter("p") String p, ContextOut out, MappingRun run) {'],
    ['Queue', 'public static void f(Queue a, @Parameter("p") String p, ContextOut out, MappingRun run) {'],
  ] as const)('a %s function writes to ContextOut', (execution, expected) => {
    expect(declaration({ name: 'f', execution, variables: [argument('a'), parameter('p')] })).toBe(expected);
  });

  test.each(['init', 'cleanUp'])('%s is void over its parameters and the mapping run, whatever its execution kind', (name) => {
    expect(declaration({ name, execution: 'Queue', variables: [parameter('p')] })).toBe(`public static void ${name}(@Parameter("p") String p, MappingRun run) {`);
  });

  test('an area without an execution kind is single value', () => {
    expect(declaration({ name: 'f', variables: [] })).toBe('public static String f(MappingRun run) {');
  });
});

describe('names', () => {
  test('nameIssue refuses what is no Java identifier, a keyword and a name taken', () => {
    expect(nameIssue('9a', [])).toBe('Enter a valid Java identifier.');
    expect(nameIssue('a-b', [])).toBe('Enter a valid Java identifier.');
    expect(nameIssue('class', [])).toBe('Enter a valid Java identifier.');
    expect(nameIssue('concat', ['concat'])).toBe('This name is already in use.');
    expect(nameIssue('$ok_1', ['concat'])).toBe('');
  });

  test('unusedName takes the first free number', () => {
    expect(unusedName('var', [])).toBe('var1');
    expect(unusedName('var', ['var1', 'var3'])).toBe('var2');
  });

  test('a new function is single value with one argument in0, a variable a free name', () => {
    expect(newFunction('calculate1', 'id')).toEqual({
      id: 'id',
      name: 'calculate1',
      title: 'calculate1',
      category: 'User-Defined',
      description: '',
      execution: 'Single value',
      variables: [{ id: 'in0', kind: 'Argument', name: 'var1', javaType: 'String', title: '' }],
      body: '',
    });
    expect(variable('Parameter', [argument('var1')], 'v').name).toBe('var2');
    expect(variable('Argument', []).id).toMatch(/^[0-9a-f-]{36}$/);
  });
});

test('moveRow swaps with the neighbour, and leaves the rows alone out of range', () => {
  const rows = ['a', 'b', 'c'];
  expect(moveRow(rows, 0, 1)).toEqual(['b', 'a', 'c']);
  expect(moveRow(rows, 2, -1)).toEqual(['a', 'c', 'b']);
  for (const [from, delta] of [
    [-1, 1],
    [3, -1],
    [0, -1],
    [2, 1],
  ] as const) {
    expect(moveRow(rows, from, delta)).toBe(rows);
  }
});

test('builtIn is an area as edited, or empty', () => {
  const state = { ...emptyFunctions(), javaAreas: { init: { body: 'x', description: 'd', variables: [] } } };
  expect(builtIn(state, 'init')).toEqual({ name: 'init', body: 'x', description: 'd', variables: [] });
  expect(builtIn(state, 'cleanUp')).toEqual({ name: 'cleanUp', body: '', description: '', variables: [] });
});

describe('readFunctions: what the browser kept', () => {
  const kept = {
    imports: ['java.util.*'],
    archives: [{ name: 'JAVA_Audit', namespace: 'urn:example:common:functions' }],
    javaAreas: { init: { body: 'b', description: '', variables: [parameter('p')] } },
    functions: [{ ...newFunction('f', 'id'), variables: [{ ...argument('a'), description: 'd' }] }],
  };

  test('reads a state of the right shape', () => {
    expect(readFunctions(kept)).toEqual(kept);
  });

  test.each<[string, unknown]>([
    ['nothing', undefined],
    ['an array', []],
    ['imports not strings', { ...kept, imports: [1] }],
    ['an archive without a namespace', { ...kept, archives: [{ name: 'x' }] }],
    ['an unknown area', { ...kept, javaAreas: { main: kept.javaAreas.init } }],
    ['an area without a body', { ...kept, javaAreas: { init: { description: '', variables: [] } } }],
    ['no areas', { ...kept, javaAreas: null }],
    ['an unknown execution kind', { ...kept, functions: [{ ...kept.functions[0], execution: 'All Values of Queue' }] }],
    ['a function without a body', { ...kept, functions: [{ ...kept.functions[0], body: undefined }] }],
    ['a variable of an unknown kind', { ...kept, functions: [{ ...kept.functions[0], variables: [{ ...argument('a'), kind: 'Result' }] }] }],
    ['a variable description not a string', { ...kept, functions: [{ ...kept.functions[0], variables: [{ ...argument('a'), description: 1 }] }] }],
    ['a variable not an object', { ...kept, functions: [{ ...kept.functions[0], variables: ['a'] }] }],
    ...(['id', 'name', 'javaType', 'title'] as const).map((key): [string, unknown] => [`a variable without ${key}`, { ...kept, functions: [{ ...kept.functions[0], variables: [{ ...argument('a'), [key]: 1 }] }] }]),
    ...(['id', 'name', 'title', 'category', 'description'] as const).map((key): [string, unknown] => [`a function without ${key}`, { ...kept, functions: [{ ...kept.functions[0], [key]: null }] }]),
    ['functions without variables', { ...kept, functions: [{ ...kept.functions[0], variables: undefined }] }],
    ['an area of a variable not one', { ...kept, javaAreas: { init: { ...kept.javaAreas.init, variables: [{}] } } }],
    ['an area description not a string', { ...kept, javaAreas: { init: { ...kept.javaAreas.init, description: 0 } } }],
    ['an archive name not a string', { ...kept, archives: [{ name: 1, namespace: 'x' }] }],
    ['functions not a list', { ...kept, functions: {} }],
  ])('reads %s as no functions', (_, value) => {
    expect(readFunctions(value)).toEqual(emptyFunctions());
  });
});
