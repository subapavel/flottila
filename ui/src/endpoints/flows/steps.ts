import { fieldProblem, type SettingsField, type SettingsRule } from '../settings';
import type { FlowProblem, Path, StepField, StepSettings, ValueField } from './types';

/**
 * A built-in step type as the UI describes it: the fields of its form, its rules, whether it takes no key but its fields
 * and those of the "Step" tab (`closed`), which paths inside it hold lists of steps (`stepLists`, where `*` stands for any
 * key or index), which key its node shows as its detail, and a check of what its fields alone cannot say.
 */
export interface StepType {
  fields: StepField[];
  rules?: SettingsRule[];
  closed?: boolean;
  stepLists?: readonly (readonly string[])[];
  detail?: string;
  check?: (values: Record<string, unknown>) => { key: string; message: string }[];
}

/** The keys of a step that are the platform's, on the "Step" tab: never the step's own settings. */
export const STEP_KEYS = new Set(['type', 'name', 'retryable', 'recording']);

/** A key or prop name, as the flow schema writes it: never one that starts with `rail8.`. */
export const KEY_NAME = '(?!rail8\\.)[A-Za-z][A-Za-z0-9_.-]*';
/** A step's name, as the flow schema writes it. */
export const STEP_NAME = '[a-z][a-zA-Z0-9_-]*';

type Extra = Partial<Omit<ValueField, 'name' | 'type'>>;

const field = (name: string, type: ValueField['type'], help: string, extra: Extra = {}): ValueField => ({
  name,
  type,
  help,
  tab: 'general',
  required: false,
  directions: ['in', 'out'],
  ...extra,
});

const language = field('language', 'text', 'The language of the expressions; the node knows xpath and the languages of its plugins.', { default: 'xpath' });

/** The built-in steps, described in the UI. The choices of `endpoint` and `process` are filled from the flow and its context. */
export const BUILT_IN: Readonly<Record<string, StepType>> = {
  to: {
    fields: [
      field('endpoint', 'choice', 'The out endpoint every message is sent to.', { choices: [], reference: 'endpoint' }),
      field('byProp', 'text', 'The prop whose value picks the endpoint, set by a route before.', { pattern: KEY_NAME }),
      {
        ...field('endpoints', 'text', "The endpoint for each value of byProp."),
        type: 'map',
        key: {},
        value: field('endpoint', 'choice', 'The out endpoint for this value.', { required: true, choices: [], reference: 'endpoint' }),
      },
    ],
    rules: [{ kind: 'exactlyOne', fields: ['endpoint', 'byProp'] }],
    check: (values) =>
      values.byProp !== undefined && values.byProp !== null && (values.endpoints === undefined || values.endpoints === null)
        ? [{ key: 'endpoints', message: 'is required when byProp is set' }]
        : [],
  },
  route: {
    fields: [
      field('prop', 'text', 'The prop the route sets.', { required: true, pattern: KEY_NAME }),
      language,
      {
        ...field('routes', 'text', 'The rules, in order: the first whose condition holds sets the prop.', { required: true }),
        type: 'list',
        item: [
          field('when', 'text', 'The condition.', { required: true }),
          field('set', 'text', 'The value the prop is set to.', { required: true, emptyAllowed: true }),
        ],
      },
      field('otherwise', 'text', 'The value when no rule holds; without it such a message fails here.', { emptyAllowed: true }),
    ],
    detail: 'prop',
  },
  filter: {
    fields: [field('when', 'text', 'The condition a message goes on under; any other ends here.', { required: true }), language],
    detail: 'when',
  },
  extractKey: {
    fields: [
      field('key', 'text', 'The business key the message is found by.', { required: true, pattern: KEY_NAME }),
      field('xpath', 'text', 'The expression whose value is the key.', { required: true }),
      language,
    ],
    detail: 'key',
  },
  validate: {
    fields: [field('required', 'text', 'The condition every message must meet, else it fails here.', { required: true }), language],
    detail: 'required',
  },
  split: {
    fields: [field('xpath', 'text', 'Selects the parts; each one goes through the rest of the list on its own.', { required: true }), language],
    detail: 'xpath',
  },
  process: {
    fields: [field('process', 'choice', 'The process of this flow whose steps run here.', { required: true, choices: [], reference: 'process' })],
    closed: true,
    detail: 'process',
  },
};

/** The fields of the "Step" tab: a process call takes only the name. */
export function stepTabFields(type: string | undefined, defaultName: string): StepField[] {
  const name = field('name', 'text', `The step's name in a message's history; without one it is ${defaultName}.`, { pattern: STEP_NAME });
  if (type === 'process') return [name];
  return [
    name,
    field('retryable', 'boolean', "Whether the step is tried again under the flow's retry policy when it fails."),
    field('recording.level', 'choice', 'What the step records, instead of the flow’s level.', { choices: ['all', 'ends', 'errors', 'off'] }),
    field('recording.payload', 'choice', 'Whether the step keeps its payload, instead of the flow’s choice.', { choices: ['keep', 'metadata-only'] }),
  ];
}

/** A settings description of `fields` and `rules`, on one tab. */
export function settingsOf(fields: StepField[], rules: SettingsRule[] = []): StepSettings {
  return { format: 1, described: true, tabs: [{ id: 'general', label: 'Settings', common: false }], fields, rules };
}

/** A description of no settings: the step's keys go in one raw editor. */
export const UNDESCRIBED: StepSettings = { format: 1, described: false, tabs: [], fields: [], rules: [] };

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);

/**
 * The form of a step of a module or plugin from its `configSchema`: each top-level property but the platform's keys
 * becomes a field (a string text with its pattern, or a choice when it has an `enum`; an integer a whole number with its
 * bounds; a boolean a switch; anything else a raw field), with its `description`, `default` and `required`. Null when the
 * schema has no top-level properties. `closed` is true when the schema refuses other keys.
 */
export function describeSchema(schema: unknown): { settings: StepSettings; closed: boolean } | null {
  if (!isRecord(schema) || !isRecord(schema.properties)) return null;
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((r): r is string => typeof r === 'string') : []);
  const fields: StepField[] = [];
  for (const [name, property] of Object.entries(schema.properties)) {
    if (STEP_KEYS.has(name)) continue;
    const p = isRecord(property) ? property : {};
    const extra: Extra = { required: required.has(name) };
    if (typeof p.description === 'string') extra.help = p.description;
    if (p.default !== undefined) extra.default = p.default;
    let type: ValueField['type'] = 'raw';
    if (p.type === 'string' && Array.isArray(p.enum) && p.enum.every((c) => typeof c === 'string')) {
      type = 'choice';
      extra.choices = p.enum as string[];
    } else if (p.type === 'string') {
      type = 'text';
      if (typeof p.pattern === 'string') extra.pattern = p.pattern;
    } else if (p.type === 'integer') {
      type = 'integer';
      if (typeof p.minimum === 'number') extra.min = p.minimum;
      if (typeof p.maximum === 'number') extra.max = p.maximum;
    } else if (p.type === 'boolean') type = 'boolean';
    const { help, ...rest } = extra;
    fields.push(field(name, type, help ?? '', rest));
  }
  return { settings: settingsOf(fields), closed: schema.additionalProperties === false };
}

const present = (value: unknown) => value !== null && value !== undefined;

/** What is wrong with `value` for `field`, by path below the field's own, or nothing. */
function judge(field: StepField, value: unknown, at: Path): FlowProblem[] {
  if (field.type === 'list') {
    if (!Array.isArray(value)) return [{ path: at, message: 'must be a list' }];
    return value.flatMap((item, i) =>
      isRecord(item) ? checkFields(field.item, item, [...at, i]) : [{ path: [...at, i], message: 'must be a mapping' }],
    );
  }
  if (field.type === 'map') {
    if (!isRecord(value)) return [{ path: at, message: 'must be a mapping' }];
    return Object.entries(value).flatMap(([key, v]) => {
      const found: FlowProblem[] = [];
      if (field.key.pattern !== undefined && !new RegExp(`^(?:${field.key.pattern})$`).test(key)) {
        found.push({ path: [...at, key], message: `the key must match ${field.key.pattern}` });
      }
      return [...found, ...judge(field.value, v, [...at, key])];
    });
  }
  if ('emptyAllowed' in field && field.emptyAllowed === true && value === '') return [];
  // what a reference names is judged against the flow and its context, which knows when the working folder was read in part
  if ('reference' in field && field.reference !== undefined) return typeof value === 'string' ? [] : [{ path: at, message: 'must be text' }];
  const problem = fieldProblem(field as SettingsField, value);
  return problem === null ? [] : [{ path: at, message: problem }];
}

/** What is wrong with `values` for `fields`, each at its path below `at`: a value that does not fit, or a required one left out. */
export function checkFields(fields: readonly StepField[], values: Record<string, unknown>, at: Path): FlowProblem[] {
  const problems: FlowProblem[] = [];
  for (const f of fields) {
    const value = values[f.name];
    if (present(value)) problems.push(...judge(f, value, [...at, f.name]));
    else if (f.required) problems.push({ path: [...at, f.name], message: 'is required' });
  }
  return problems;
}

/**
 * What is wrong with the step `values` at `at` for its description: its fields, then the rules, then its type's own
 * check, then, when `closed`, each key that no field describes. The keys of the "Step" tab are judged by
 * {@link checkStepTab}.
 */
export function checkStep(settings: StepSettings, values: Record<string, unknown>, at: Path, closed: boolean, own?: StepType['check']): FlowProblem[] {
  if (!settings.described) return [];
  const problems = checkFields(settings.fields, values, at);
  for (const rule of settings.rules) {
    if (rule.kind === 'exactlyOne' && rule.fields.filter((name) => present(values[name])).length !== 1) {
      problems.push({ path: at, message: `set exactly one of ${rule.fields.join(', ')}` });
    }
  }
  for (const { key, message } of own?.(values) ?? []) problems.push({ path: [...at, key], message });
  if (closed) {
    const described = new Set(settings.fields.map((f) => f.name));
    for (const key of Object.keys(values)) {
      if (!described.has(key) && !STEP_KEYS.has(key)) problems.push({ path: [...at, key], message: 'no such setting' });
    }
  }
  return problems;
}

/** What is wrong with the keys of the "Step" tab of the step `values` at `at`; a process call's are judged by the rules of calls. */
export function checkStepTab(values: Record<string, unknown>, at: Path): FlowProblem[] {
  if (values.type === 'process') {
    return present(values.name) ? judge(stepTabFields('process', '')[0]!, values.name, [...at, 'name']) : [];
  }
  const [name, retryable, level, payload] = stepTabFields(undefined, '');
  const problems = checkFields([name!, retryable!], values, at);
  const recording = values.recording;
  if (present(recording)) {
    if (!isRecord(recording)) problems.push({ path: [...at, 'recording'], message: 'must be a mapping of level and payload' });
    else {
      for (const [key, f] of [['level', level!], ['payload', payload!]] as const) {
        if (present(recording[key])) problems.push(...judge(f, recording[key], [...at, 'recording', key]));
      }
      for (const key of Object.keys(recording)) {
        if (key !== 'level' && key !== 'payload') problems.push({ path: [...at, 'recording', key], message: 'no such setting' });
      }
    }
  }
  return problems;
}
