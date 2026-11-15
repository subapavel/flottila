import { strFromU8, strToU8, unzipSync, zipSync } from 'fflate';
import { createStore, type StoreApi } from 'zustand/vanilla';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { WorkingCopyStore } from '../store/working-copy';
import { perWorkingCopy, previewDraft, type PreviewDraftStore } from './preview-draft';

/** The follow-up issue of the test instance library (spec #152, preview features). */
export const TEST_INSTANCES_ISSUE = 170;

/** A test document's format, by the root of the source message's structure. */
export type InstanceFormat = 'XML' | 'JSON';

/** A named test input of a mapping (the prototype's test instance): its source document and parameter values. */
export interface TestInstance {
  id: string;
  name: string;
  format: InstanceFormat;
  text: string;
  /** The parameter values of a test run, by `param:<id>` or `header:<name>`. */
  values: Record<string, string>;
  /** The order of use: the highest is the current instance. */
  lastUsed: number;
  /** How many times it was saved over. */
  revision?: number;
}

/** What an instance is made of and saved from: the source document and the parameter values as they are now. */
export interface InstanceResource {
  text: string;
  values: Record<string, string>;
}

/** The key of a mapping's test instances in the browser state (ADR-0028: mock state per mapping id). */
export const testInstancesKey = (mappingId: string) => `mapping:${mappingId}:preview:test-instances`;

/** The name of the library's draft among the working copy's preview drafts. */
export const TEST_INSTANCES_PREVIEW = 'test-instances';

const isStrings = (value: unknown): value is Record<string, string> =>
  typeof value === 'object' && value !== null && Object.values(value).every((v) => typeof v === 'string');

function isInstance(value: unknown): value is TestInstance {
  const i = value as Partial<TestInstance> | null;
  return (
    typeof i === 'object' &&
    i !== null &&
    typeof i.id === 'string' &&
    typeof i.name === 'string' &&
    (i.format === 'XML' || i.format === 'JSON') &&
    typeof i.text === 'string' &&
    isStrings(i.values) &&
    typeof i.lastUsed === 'number'
  );
}

/** The instances of one format, in the order they were made. */
export const instancesOf = (list: readonly TestInstance[], format: InstanceFormat) => list.filter((i) => i.format === format);

/** The current instance of a format: the last used, the later one on a tie; none in an empty library. */
export function currentInstance(list: readonly TestInstance[], format: InstanceFormat): TestInstance | undefined {
  return instancesOf(list, format).reduce<TestInstance | undefined>((latest, i) => (!latest || i.lastUsed >= latest.lastUsed ? i : latest), undefined);
}

const nextUse = (list: readonly TestInstance[]) => list.reduce((order, i) => Math.max(order, i.lastUsed), 0) + 1;

/** The library with a new instance `name` of `resource` as the current one; throws for a name the format has already. */
export function createInstance(list: readonly TestInstance[], format: InstanceFormat, name: string, resource: InstanceResource, id: string): TestInstance[] {
  if (instancesOf(list, format).some((i) => i.name === name)) throw new Error('This name already exists.');
  return [...list, { id, name, format, text: resource.text, values: resource.values, lastUsed: nextUse(list) }];
}

/** The library with instance `id` of the format as the current one. */
export function selectInstance(list: readonly TestInstance[], format: InstanceFormat, id: string): TestInstance[] {
  const lastUsed = nextUse(list);
  return list.map((i) => (i.id === id && i.format === format ? { ...i, lastUsed } : i));
}

/** The library with `resource` saved over the current instance as a new revision, unless it holds that already. */
export function saveInstance(list: readonly TestInstance[], format: InstanceFormat, resource: InstanceResource): TestInstance[] {
  const current = currentInstance(list, format);
  return list.map((i) =>
    i !== current || (i.text === resource.text && JSON.stringify(i.values) === JSON.stringify(resource.values))
      ? i
      : { ...i, text: resource.text, values: resource.values, revision: (i.revision ?? 0) + 1 },
  );
}

/** The library without the current instance of the format. */
export function removeInstance(list: readonly TestInstance[], format: InstanceFormat): TestInstance[] {
  const current = currentInstance(list, format);
  return list.filter((i) => i !== current);
}

export type TestInstancesStore = PreviewDraftStore<TestInstance[]>;

/**
 * The test instance library of the open mapping (#170, the prototype's useTestInstances): a draft of the edit session
 * whose saved state the browser keeps under the mapping's id. It never reaches the working copy or the `ApiClient`.
 */
export function testInstances(workingCopy: WorkingCopyStore, browserState: BrowserStateStore, mappingId: string): TestInstancesStore {
  return previewDraft(workingCopy, browserState, {
    name: TEST_INSTANCES_PREVIEW,
    key: testInstancesKey(mappingId),
    read: (kept): TestInstance[] => (Array.isArray(kept) && kept.every(isInstance) ? kept : []),
  });
}

/** The library's edits for one format, each a change of its draft; `create` throws as `createInstance` does. */
export function instanceActions(library: TestInstancesStore, format: InstanceFormat, newId: () => string = () => crypto.randomUUID()) {
  const edit = (change: (list: TestInstance[]) => TestInstance[]) => library.getState().edit(change);
  return {
    create: (name: string, resource: InstanceResource) => edit((list) => createInstance(list, format, name, resource, newId())),
    select: (id: string) => edit((list) => selectInstance(list, format, id)),
    save: (resource: InstanceResource) => edit((list) => saveInstance(list, format, resource)),
    remove: () => edit((list) => removeInstance(list, format)),
  };
}

export interface InstanceChoiceState {
  /** The instance last chosen in read mode, by id; `null` before a choice. */
  id: string | null;
  choose: (id: string) => void;
}

const choices = new WeakMap<TestInstancesStore, StoreApi<InstanceChoiceState>>();

/**
 * The instance chosen in read mode (#177), one per library: the test session loads it into the source for a run, and the
 * library, its current instance and the browser state stay as they are. Edit mode selects in the library instead.
 */
export function instanceChoice(library: TestInstancesStore): StoreApi<InstanceChoiceState> {
  if (!choices.has(library)) choices.set(library, createStore<InstanceChoiceState>()((set) => ({ id: null, choose: (id) => set({ id }) })));
  return choices.get(library)!;
}

export interface TestInputState {
  /** The document loaded last; `null` before one. */
  text: string | null;
  /** How many documents were loaded: a change tells the test session to take `text` as its source document. */
  loads: number;
  load: (text: string) => void;
}

/** Where the instance tools of any tab load a document for the open mapping's test session, one per working copy. */
export function testInput(workingCopy: WorkingCopyStore) {
  return perWorkingCopy(workingCopy, 'test-input', () =>
    createStore<TestInputState>()((set) => ({ text: null, loads: 0, load: (text) => set((state) => ({ text, loads: state.loads + 1 })) })),
  );
}

/** The largest instance an archive may hold (the prototype's readInstance). */
const LARGEST = 16 * 1024 * 1024;

const BYTE_ORDER_MARK = new RegExp('^\\uFEFF');

const entryName = (format: InstanceFormat) => `instance.${format.toLowerCase()}`;

/** A document as the prototype's Save Document Source exports it: `instance.<format>` in a zip, stored. */
export const zipInstance = (text: string, format: InstanceFormat) => zipSync({ [entryName(format)]: strToU8(text) }, { level: 0 });

/**
 * The text of a test instance file (the prototype's readInstance): a zip's `instance.<format>`, without a byte order
 * mark, else the file as text. Throws for a zip without that entry, or with one of 16 MB or more.
 */
export async function readInstance(file: Blob, format: InstanceFormat): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  if (bytes[0] !== 0x50 || bytes[1] !== 0x4b) return new TextDecoder().decode(bytes);
  const name = entryName(format);
  const archive = unzipSync(bytes, { filter: (entry) => entry.name === name && entry.originalSize < LARGEST });
  if (!archive[name]) throw new Error(`The archive needs a ${name} file under 16 MB.`);
  return strFromU8(archive[name]).replace(BYTE_ORDER_MARK, '');
}
