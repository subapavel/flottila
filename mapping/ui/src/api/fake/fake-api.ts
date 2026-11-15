import caseOutput from '../../../../corpus/cases/link/direct-copy/001/expected.xml?raw';
import caseInput from '../../../../corpus/cases/link/direct-copy/001/input.xml?raw';
import orderIdCopyText from '../../../../corpus/mappings/order-id-copy.mapping.json?raw';
import catalogueText from '../../../../contract/function-catalogue.json?raw';
import type { MappingModel, StructureRef } from '../../generated/mapping-model';
import {
  ApiProblem,
  type ApiClient,
  type Diagnostic,
  type FunctionCatalogue,
  type MappingSummary,
  type StoredMapping,
  type StructureLoaded,
  type StructureResponse,
  type TestResult,
} from '../client';
import { TREE_BUNDLE, TREE_FIELDS, TREE_FILE, TREE_MAPPING, TREE_SEED, generatedMapping, generatedStructure } from './generated-tree';
import { orderStructure } from './order-structure';
import { sameStepStructure } from './same-step-structure';

const PROBLEM = 'urn:flottila:mapping:problem:';

// The corpus file is a valid model: the engine's corpus case loads it.
const orderIdCopy = JSON.parse(orderIdCopyText) as MappingModel;

/** The generated tree of the `ui/tree-20128/scroll` scenario, built on first use. */
let tree: { structure: StructureLoaded; mapping: MappingModel } | undefined;
function generatedTree() {
  if (!tree) {
    const structure = generatedStructure(TREE_SEED, TREE_FIELDS);
    tree = { structure, mapping: generatedMapping(structure, TREE_BUNDLE) };
  }
  return tree;
}

/**
 * The corpus mappings, the same mapping without its target field mappings (the thin path's start), and the generated
 * tree's mapping with every leaf field linked.
 */
function corpusMappings(): Record<string, MappingModel> {
  const unmapped = { ...orderIdCopy };
  delete unmapped.targetFieldMappings;
  return { 'mappings/order-id-copy': orderIdCopy, 'mappings/order-unmapped': unmapped, [TREE_MAPPING]: generatedTree().mapping };
}

/** The structures the fake knows, by workspace path of the bundle's one file. */
const structures: Record<string, () => StructureResponse> = {
  'structures/skeleton/order.xsd': () => orderStructure,
  'structures/refused/same-step.xsd': () => sameStepStructure,
  [TREE_FILE]: () => generatedTree().structure,
};

/** The structure of a bundle resolved against the directory `base`, or the refusal of a bundle the fake does not know. */
function structureOf(base: string, bundle: string[]): StructureResponse {
  const files = bundle.map((file) => resolve(base, file)).join(', ');
  return (
    structures[files]?.() ?? {
      kind: 'refused',
      diagnostics: [
        {
          severity: 'error',
          code: 'schema-unreadable',
          message: `the fake API has no structure for ${files}`,
          location: { kind: 'file-position', file: bundle.join(', '), line: 1, column: 1 },
        },
      ],
    }
  );
}

// A mapping id as the service's workspace accepts it (ADR-0024): 1 to 16 segments, the last one not reserved.
const SEGMENT = '[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}';
const WORKSPACE_ID = new RegExp(`^${SEGMENT}(/${SEGMENT}){0,15}$`);
const RESERVED = new Set(['test', 'compile', 'refresh', 'udfs']);

/** The directory of a mapping id, against which its bundle references resolve. */
function directoryOf(id: string): string {
  return id.split('/').slice(0, -1).join('/');
}

/** The test runs the fake knows: the corpus case's mapping, input and output (`link/direct-copy/001`). */
const cases = [{ links: links(orderIdCopy), input: caseInput, output: caseOutput }];

/** The fixtures of one scenario: what the fake answers where it cannot compute the answer. */
export interface FakeScenario {
  /** The diagnostics a compile answers, by mapping id; a mapping without an entry has none. */
  compile?: Record<string, Diagnostic[]>;
  /** The diagnostics a refresh answers when every structure it names loads, by mapping id; a mapping without an entry has none. */
  refresh?: Record<string, Diagnostic[]>;
  /** What a test run answers, by mapping id, whatever the working copy and input; a mapping without one runs the corpus cases. */
  test?: Record<string, TestResult>;
}

/**
 * An in-memory `ApiClient` over synthetic fixtures from `corpus/` and the catalogue of `contract/` (ADR-0013,
 * ADR-0027), for component tests, the e2e suite
 * and `?api=fake`. It runs no engine: a test run answers the scenario's fixture, else only a corpus case, and refuses
 * an unconnected result as the engine does; a compile answers the scenario's fixture whatever the working copy, and so
 * does a refresh for its diagnostics. `seed` replaces the corpus mappings.
 */
export function fakeApiClient(seed: Record<string, MappingModel> = corpusMappings(), scenario: FakeScenario = {}): ApiClient {
  // Each write gets a tag of its own, as a changed file gets a new sha256 (ADR-0024); never the same tag twice.
  let writes = 0;
  const written = (model: MappingModel): StoredMapping => ({ model: structuredClone(model), etag: `"fake:${++writes}"` });
  const mappings = new Map(Object.entries(seed).map(([id, model]) => [id, written(model)]));

  const stored = (id: string) => {
    const mapping = mappings.get(id);
    if (!mapping) throw notFound(id);
    return mapping;
  };

  return {
    listMappings: async () => [...mappings].sort(([a], [b]) => (a < b ? -1 : 1)).map(([id, { model }]) => summaryOf(id, model)),

    createMapping: async (request) => {
      const { id, description } = request;
      if (!WORKSPACE_ID.test(id) || RESERVED.has(id.slice(id.lastIndexOf('/') + 1))) {
        throw new ApiProblem({
          type: `${PROBLEM}invalid-workspace-path`,
          title: 'Not a workspace path',
          status: 400,
          detail: `not a workspace path: "${id}"`,
        });
      }
      if (mappings.has(id)) {
        throw new ApiProblem({ type: `${PROBLEM}mapping-exists`, title: 'Mapping exists', status: 409, detail: 'a mapping with this id exists' });
      }
      // as the service creates (ADR-0029): each side the request names, none it does not; an empty description is none
      const base = directoryOf(id);
      const model: MappingModel = { formatVersion: 1, ...(description ? { description } : {}) };
      const diagnostics: Diagnostic[] = [];
      for (const side of ['source', 'target'] as const) {
        const choice = request[side];
        if (!choice) continue;
        const structure = structureOf(base, choice.bundle);
        if (structure.kind === 'refused') diagnostics.push(...structure.diagnostics);
        else model[side] = { bundle: choice.bundle as [string, ...string[]], fingerprint: structure.fingerprint, root: choice.root };
      }
      if (diagnostics.length > 0) {
        throw new ApiProblem({
          type: `${PROBLEM}model-refused`,
          title: 'Model refused',
          status: 422,
          detail: 'a structure of the new mapping cannot be loaded',
          diagnostics,
        });
      }
      mappings.set(id, written(sortedKeys(model)));
      return structuredClone(stored(id));
    },

    getMapping: async (id) => structuredClone(stored(id)),

    saveMapping: async (id, model, etag) => {
      if (stored(id).etag !== etag) {
        throw new ApiProblem({
          type: `${PROBLEM}mapping-changed`,
          title: 'Mapping changed',
          status: 409,
          detail: 'the mapping changed on disk since the If-Match tag was read; nothing was saved',
        });
      }
      mappings.set(id, written(sortedKeys(model)));
      return structuredClone(stored(id));
    },

    loadStructure: async ({ base, bundle }) => structureOf(base, bundle),

    runTest: async (id, { model, input }): Promise<TestResult> => {
      const fixture = scenario.test?.[id];
      if (fixture) return structuredClone(fixture);
      const known = cases.find((c) => c.input === input && c.links === links(model));
      // a test run's output carries its trace (ADR-0015, #53); the fake records none
      if (known) return { kind: 'output', output: known.output, diagnostics: [], trace: {} };
      const unconnected = Object.values(model.targetFieldMappings ?? {}).filter((m) => m.result === null);
      if (unconnected.length > 0) {
        return {
          kind: 'refused',
          diagnostics: unconnected.map((m) => ({
            severity: 'error',
            code: 'unconnected-target-field',
            message: "the target field mapping's result is not connected",
            location: { kind: 'authoring-id', authoringId: m.id },
          })),
        };
      }
      throw new ApiProblem({
        type: `${PROBLEM}invalid-request`,
        title: 'Invalid request',
        status: 400,
        detail: 'the fake API has no corpus case for this working copy and input',
      });
    },

    // The committed packed catalogue (ADR-0027): the bytes GET /api/catalogue serves.
    getCatalogue: async () => JSON.parse(catalogueText) as FunctionCatalogue,

    compile: async (id) => {
      stored(id);
      return { diagnostics: structuredClone(scenario.compile?.[id] ?? []) };
    },

    // as the engine refreshes (#99): the fingerprints are the fake's structures', only the stage-3 diagnostics a fixture;
    // a side the model does not name stays absent (ADR-0029)
    refresh: async (id, model) => {
      stored(id);
      const base = directoryOf(id);
      const refreshed: MappingModel = { ...model };
      const loadErrors: Diagnostic[] = [];
      for (const side of ['source', 'target'] as const) {
        const ref = model[side];
        if (!ref) continue;
        const structure = structureOf(base, ref.bundle);
        if (structure.kind === 'loaded') refreshed[side] = { ...ref, fingerprint: structure.fingerprint };
        else loadErrors.push(...structure.diagnostics);
      }
      return {
        model: sortedKeys(refreshed),
        diagnostics: loadErrors.length > 0 ? loadErrors : structuredClone(scenario.refresh?.[id] ?? []),
      };
    },
  };
}

/** A mapping's summary as the service lists it (ADR-0029): an absent description or side is left out. */
function summaryOf(id: string, model: MappingModel): MappingSummary {
  const slash = id.lastIndexOf('/');
  return {
    id,
    name: id.slice(slash + 1),
    package: id.slice(0, Math.max(slash, 0)),
    ...(model.description ? { description: model.description } : {}),
    ...(model.source ? { source: rootName(model.source) } : {}),
    ...(model.target ? { target: rootName(model.target) } : {}),
  };
}

/** A root's name: an element's local name, a WSDL root's operation, the last segment of a JSON root's first file. */
function rootName({ bundle, root }: StructureRef): string {
  if ('element' in root) return root.element.slice(root.element.indexOf('}') + 1);
  if ('wsdl' in root) return root.wsdl.operation;
  return bundle[0].slice(bundle[0].lastIndexOf('/') + 1);
}

function notFound(id: string) {
  return new ApiProblem({ type: `${PROBLEM}not-found`, title: 'Not found', status: 404, detail: `no mapping ${id}` });
}

/** The field links of a model, as text: each target field with the source field its result box reads. */
function links(model: MappingModel): string {
  return Object.entries(model.targetFieldMappings ?? {})
    .map(([target, m]) => {
      const box = m.result === null ? undefined : m.boxes[m.result];
      return `${target}<-${box?.kind === 'source' ? box.field : '?'}`;
    })
    .sort()
    .join('\n');
}

/** A workspace path: `bundle` resolved against the directory `base`. */
function resolve(base: string, bundle: string): string {
  const parts = base === '' ? [] : base.split('/');
  for (const segment of bundle.split('/')) {
    if (segment === '..') parts.pop();
    else parts.push(segment);
  }
  return parts.join('/');
}

/** Sorts object keys at every level by UTF-16 code unit, as the canonical writer does (ADR-0010). */
function sortedKeys<T>(value: T): T {
  if (Array.isArray(value)) return value.map(sortedKeys) as T;
  if (typeof value !== 'object' || value === null) return value;
  const record = value as Record<string, unknown>;
  return Object.fromEntries(
    Object.keys(record)
      .sort()
      .map((key) => [key, sortedKeys(record[key])]),
  ) as T;
}
