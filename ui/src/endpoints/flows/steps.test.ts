import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BUILT_IN, checkFields, checkStep, checkStepTab, describeSchema, FLOW_FIELDS, FLOW_PARTS, KEY_NAME, settingsOf, STEP_NAME } from './steps';
import type { StepField } from './types';

const platform = join(__dirname, '..', '..', '..', '..', 'platform');
const schema = JSON.parse(readFileSync(join(platform, 'schemas', 'src', 'main', 'resources', 'schemas', 'flow.schema.json'), 'utf8')) as {
  required: string[];
  properties: Record<string, Record<string, unknown> & { required?: string[]; properties?: Record<string, Record<string, unknown>> }>;
  $defs: Record<string, { pattern?: string; properties?: Record<string, unknown>; allOf?: unknown[] }>;
};

type Then = { required?: string[]; properties?: Record<string, Record<string, unknown>>; anyOf?: Then[]; propertyNames?: { enum: string[] } };

/** What the flow schema says of each step type it knows: its keys and those it requires. */
const typed = new Map(
  (schema.$defs.step!.allOf as { if: { properties: { type: { const: string } } }; then: Then }[]).map(({ if: when, then }) => {
    const parts = [then, ...(then.anyOf ?? [])];
    return [
      when.properties.type.const,
      {
        keys: new Set(parts.flatMap((p) => Object.keys(p.properties ?? {}))),
        required: new Set(then.required ?? []),
        properties: Object.assign({}, ...parts.map((p) => p.properties ?? {})) as Record<string, Record<string, unknown>>,
        propertyNames: then.propertyNames?.enum,
      },
    ];
  }),
);

/** The kinds of field that hold what a schema property takes: text or a choice for a string, a list for an array, a map for an object. */
const kindOf = (property: Record<string, unknown> | undefined): string[] => {
  const ref = property?.$ref;
  const type = typeof ref === 'string' ? (schema.$defs[ref.replace('#/$defs/', '')] as { type?: unknown }).type : property?.type;
  return type === 'array' ? ['list'] : type === 'object' ? ['map'] : type === 'boolean' ? ['boolean'] : ['text', 'choice'];
};

const patternOf = (property: Record<string, unknown> | undefined): string | undefined => {
  const ref = property?.$ref;
  if (typeof ref === 'string') return schema.$defs[ref.replace('#/$defs/', '')]!.pattern;
  return typeof property?.pattern === 'string' ? property.pattern : undefined;
};

describe('the built-in steps the UI describes', () => {
  it('are the node’s built-in steps', () => {
    const builder = readFileSync(join(platform, 'core', 'src', 'main', 'java', 'dev', 'rail8', 'core', 'flow', 'FlowBuilder.java'), 'utf8');
    const line = /BUILT_INS = Set\.of\(([^)]*)\)/.exec(builder)![1]!;
    const types = [...line.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
    if (line.includes('FlowDefinition.PROCESS')) types.push('process');
    expect(Object.keys(BUILT_IN).sort()).toEqual(types.sort());
  });

  it.each(Object.keys(BUILT_IN).filter((t) => typed.has(t)))('describe %s as the flow schema does', (type) => {
    const known = typed.get(type)!;
    const fields = new Map(BUILT_IN[type]!.fields.map((f) => [f.name, f]));
    // every key the schema knows has a field, and every field the schema knows is required as it says
    for (const key of known.keys) expect(fields.has(key), `${type}.${key}`).toBe(true);
    for (const [name, f] of fields) {
      if (!known.keys.has(name)) continue;
      if (BUILT_IN[type]!.rules === undefined) expect(f.required, `${type}.${name} required`).toBe(known.required.has(name));
      expect(kindOf(known.properties[name]), `${type}.${name} type`).toContain(f.type);
      const pattern = patternOf(known.properties[name]);
      if (pattern !== undefined && f.type === 'text') expect(`^${f.pattern}$`, `${type}.${name} pattern`).toBe(pattern);
    }
  });

  it('let a process call take the keys the flow schema lets it take, and no other', () => {
    const keys = typed.get('process')!.propertyNames!;
    expect(['type', 'name', ...BUILT_IN.process!.fields.map((f) => f.name)].sort()).toEqual([...keys].sort());
  });

  it('write key and step names as the flow schema does', () => {
    expect(`^${KEY_NAME}$`).toBe(schema.$defs.keyName!.pattern);
    expect(`^${STEP_NAME}$`).toBe(schema.$defs.stepName!.pattern);
  });

  it('take empty text where the node does', () => {
    const route = BUILT_IN.route!.fields;
    const set = (route.find((f) => f.name === 'routes') as Extract<StepField, { type: 'list' }>).item.find((f) => f.name === 'set')!;
    expect(set).toMatchObject({ emptyAllowed: true });
    expect(route.find((f) => f.name === 'otherwise')).toMatchObject({ emptyAllowed: true });
  });
});

describe('the flow’s own settings', () => {
  /** The schema's property of a dotted name, and whether the schema requires it. */
  const property = (name: string) => {
    const [top, inner] = name.split('.') as [string, string | undefined];
    const outer = schema.properties[top]!;
    const resolved = typeof outer.$ref === 'string' ? (schema.$defs[outer.$ref.replace('#/$defs/', '')] as typeof outer) : outer;
    if (inner === undefined) return { at: outer, required: schema.required.includes(top) };
    return { at: resolved.properties![inner]!, required: schema.required.includes(top) && (resolved.required ?? []).includes(inner) };
  };

  it('describe every key of the flow schema but its parts', () => {
    const tops = new Set(FLOW_FIELDS.map((f) => f.name.split('.')[0]));
    expect([...tops, ...FLOW_PARTS].sort()).toEqual(Object.keys(schema.properties).sort());
  });

  it.each(FLOW_FIELDS.map((f) => [f.name, f] as const))('describe %s as the flow schema does', (name, f) => {
    const { at, required } = property(name);
    expect(at, name).toBeDefined();
    expect(f.required, `${name} required`).toBe(required);
    const pattern = patternOf(at);
    if (pattern !== undefined && f.type === 'text') expect(`^${f.pattern}$`, `${name} pattern`).toBe(pattern);
    if (Array.isArray(at.enum)) expect(f).toMatchObject({ type: 'choice', choices: at.enum });
    if (at.type === 'integer') expect(f).toMatchObject({ type: 'integer', min: at.minimum });
  });

  it('are judged key inside key, a required one left out at its own path', () => {
    expect(checkFields(FLOW_FIELDS, { id: 'a', version: '1.0.0', from: { endpoint: 'in' }, delivery: { mode: 'sync', timeout: '30 s' }, limits: {} }, [])).toEqual([
      { path: ['delivery', 'timeout'], message: expect.stringContaining('must match') },
      { path: ['limits', 'maxBodyBytes'], message: 'is required' },
    ]);
  });
});

describe('checkStep', () => {
  const to = BUILT_IN.to!;
  const settings = settingsOf(to.fields, to.rules);

  it('asks for exactly one of endpoint and byProp, and for endpoints with byProp', () => {
    expect(checkStep(settings, { type: 'to', endpoint: 'a' }, ['steps', 0], false, to.check)).toEqual([]);
    expect(checkStep(settings, { type: 'to' }, ['steps', 0], false, to.check)).toEqual([{ path: ['steps', 0], message: 'set exactly one of endpoint, byProp' }]);
    expect(checkStep(settings, { type: 'to', byProp: 'P' }, ['steps', 0], false, to.check)).toEqual([{ path: ['steps', 0, 'endpoints'], message: 'is required when byProp is set' }]);
  });

  it('judges a list field item by item and a map field entry by entry', () => {
    const route = settingsOf(BUILT_IN.route!.fields);
    expect(checkStep(route, { type: 'route', prop: 'P', routes: [{ when: 'x', set: '' }, { set: 'b' }, 'c'] }, ['steps', 1], false)).toEqual([
      { path: ['steps', 1, 'routes', 1, 'when'], message: 'is required' },
      { path: ['steps', 1, 'routes', 2], message: 'must be a mapping' },
    ]);
    expect(checkStep(settings, { type: 'to', byProp: 'P', endpoints: { a: 1 } }, ['steps', 0], false, to.check)).toEqual([
      { path: ['steps', 0, 'endpoints', 'a'], message: 'must be text' },
    ]);
  });

  it('names a key no field describes only when the step is closed', () => {
    const route = settingsOf(BUILT_IN.route!.fields);
    const values = { type: 'route', prop: 'P', routes: [{ when: 'x', set: 'a' }], colour: 'red', name: 'r' };
    expect(checkStep(route, values, ['steps', 0], false)).toEqual([]);
    expect(checkStep(route, values, ['steps', 0], true)).toEqual([{ path: ['steps', 0, 'colour'], message: 'no such setting' }]);
  });
});

describe('checkStepTab', () => {
  it('judges the name, retryable and recording', () => {
    expect(checkStepTab({ type: 'xsd', name: 'Bad name', retryable: 'yes', recording: { level: 'loud', extra: 1 } }, ['steps', 0])).toEqual([
      { path: ['steps', 0, 'name'], message: expect.any(String) },
      { path: ['steps', 0, 'retryable'], message: expect.any(String) },
      { path: ['steps', 0, 'recording', 'level'], message: expect.any(String) },
      { path: ['steps', 0, 'recording', 'extra'], message: 'no such setting' },
    ]);
    expect(checkStepTab({ type: 'xsd', name: 'ok', retryable: true, recording: { level: 'ends', payload: 'keep' } }, ['steps', 0])).toEqual([]);
  });
});

describe('describeSchema', () => {
  it('makes a field of each top-level property but the platform’s keys', () => {
    const described = describeSchema({
      type: 'object',
      required: ['filter'],
      additionalProperties: false,
      properties: {
        name: { type: 'string' },
        filter: { type: 'string', pattern: '^\\.', description: 'The jq filter.' },
        depth: { type: 'integer', minimum: 1, maximum: 9, default: 4 },
        strict: { type: 'boolean' },
        mode: { type: 'string', enum: ['a', 'b'] },
        shape: { type: 'object' },
      },
    });
    expect(described?.closed).toBe(true);
    expect(described?.settings.fields.map((f) => [f.name, f.type, f.required])).toEqual([
      ['filter', 'text', true],
      ['depth', 'integer', false],
      ['strict', 'boolean', false],
      ['mode', 'choice', false],
      ['shape', 'raw', false],
    ]);
    expect(described?.settings.fields[0]).toMatchObject({ help: 'The jq filter.' });
    expect(described?.settings.fields[1]).toMatchObject({ min: 1, max: 9, default: 4 });
    expect(described?.settings.fields[3]).toMatchObject({ choices: ['a', 'b'] });
  });

  it('takes a value that a part of matches the schema’s pattern, as JSON Schema does', () => {
    const [code] = describeSchema({ type: 'object', properties: { code: { type: 'string', pattern: '^[A-Z]' } } })!.settings.fields;
    const settings = settingsOf([code!]);
    expect(checkStep(settings, { type: 'x', code: 'ABC' }, ['steps', 0], false)).toEqual([]);
    expect(checkStep(settings, { type: 'x', code: 'abc' }, ['steps', 0], false)).toHaveLength(1);
  });

  it('describes nothing of a schema without properties', () => {
    expect(describeSchema({ type: 'object' })).toBeNull();
    expect(describeSchema(null)).toBeNull();
  });
});
