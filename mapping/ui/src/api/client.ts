import type { components } from '../generated/api';
import type { FunctionCatalogue } from '../generated/function-catalogue';
import type { MappingModel } from '../generated/mapping-model';

type Schemas = components['schemas'];

export type Diagnostic = Schemas['DiagnosticDto'];
export type Problem = Schemas['Problem'] | Schemas['RefusedProblem'];
export type StructureRequest = Schemas['StructureRequest'];
export type StructureResponse = Schemas['StructureResponse'];
export type StructureLoaded = Schemas['StructureLoaded'];
export type FieldDto = Schemas['FieldDto'];
export type TestResult = Schemas['TestResult'];
/** One entry of a test run's trace, by its `kind`: a queue, an atomic value or a target field's occurrences (ADR-0015). */
export type TraceEntry = Schemas['TraceEntryDto'];
export type MappingSummary = Schemas['MappingSummary'];
export type CreateMappingRequest = Schemas['CreateMappingRequest'];
export type RootSelector = Schemas['rootSelector'];
export type CompileResponse = Schemas['CompileResponse'];

/** The answer of `GET /api/catalogue`, typed by json-schema-to-typescript from the contract's catalogue schema (ADR-0026). */
export type { FunctionCatalogue };

/**
 * The test request with the model typed by json-schema-to-typescript, as ADR-0015 asks of `ApiClient`.
 * openapi-typescript's own `mapping-model.schema` type marks the schema's `$defs` as a required member, so a
 * `MappingModel` is not assignable to it.
 */
export type TestRequest = Omit<Schemas['TestRequest'], 'model'> & { model: MappingModel };

/** The answer of a refresh (ADR-0027), with the model typed as `TestRequest`'s is. */
export type RefreshResponse = Omit<Schemas['RefreshResponse'], 'model'> & { model: MappingModel };

/** A mapping as the service stored it: the canonical model and its strong ETag (ADR-0024). */
export interface StoredMapping {
  model: MappingModel;
  etag: string;
}

/** A transport error of the service: an RFC 9457 problem (ADR-0015, ADR-0024). */
export class ApiProblem extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ? `${problem.title}: ${problem.detail}` : problem.title);
    this.name = 'ApiProblem';
    this.problem = problem;
  }
}

/** Whether a failed save is a save conflict: 409 `mapping-changed`, the file changed on disk (ADR-0027). */
export function isSaveConflict(error: unknown): boolean {
  return error instanceof ApiProblem && error.problem.type === 'urn:flottila:mapping:problem:mapping-changed';
}

/** What the editor shows for a failed call: an `ApiProblem`'s title, detail and diagnostics, or another error's message. */
export function describeError(error: unknown): { title: string; detail: string; diagnostics: Diagnostic[] } {
  if (error instanceof ApiProblem) {
    const { problem } = error;
    return { title: problem.title, detail: problem.detail, diagnostics: 'diagnostics' in problem ? problem.diagnostics : [] };
  }
  return { title: error instanceof Error ? error.message : String(error), detail: '', diagnostics: [] };
}

/**
 * The editor's port to the service (ADR-0013). Every method rejects with an `ApiProblem` on a transport error.
 * Diagnostics are a result, never a rejection, except for a refused save (422 `model-refused`).
 */
export interface ApiClient {
  /**
   * The workspace's mappings, sorted by id, each with its package, description and root names (ADR-0029); a stored
   * file the service refuses has its id, name and package alone.
   */
  listMappings(): Promise<MappingSummary[]>;
  /**
   * Creates a mapping with its description, the fingerprint of each structure the request names and no target field
   * mapping (ADR-0029), and resolves to it as stored; a side it does not name is absent until the Signature tab
   * chooses it. An existing id rejects with a 409 `mapping-exists` problem, an id that is not a workspace path with
   * a 400.
   */
  createMapping(request: CreateMappingRequest): Promise<StoredMapping>;
  getMapping(id: string): Promise<StoredMapping>;
  /**
   * Saves on condition that the stored file still has `etag` (`If-Match`, ADR-0027), and resolves to the canonical
   * model the service wrote. A file changed on disk rejects with a 409 `mapping-changed` problem.
   */
  saveMapping(id: string, model: MappingModel, etag: string): Promise<StoredMapping>;
  loadStructure(request: StructureRequest): Promise<StructureResponse>;
  /** Runs the working copy in the request on its input message. */
  runTest(id: string, request: TestRequest): Promise<TestResult>;
  /** Compiles the working copy (ADR-0015, ADR-0027); `signal` cancels the call. */
  compile(id: string, model: MappingModel, signal?: AbortSignal): Promise<CompileResponse>;
  /**
   * Refreshes the working copy's structures (ADR-0010, ADR-0027): each side whose bundle loads gets the fingerprint of
   * the structure just read, a side whose bundle fails keeps its own and gives its load errors. Writes nothing.
   */
  refresh(id: string, model: MappingModel): Promise<RefreshResponse>;
  /** The packed function catalogue, keyed by function id (ADR-0026, ADR-0027). */
  getCatalogue(): Promise<FunctionCatalogue>;
}
