import { createStore } from 'zustand/vanilla';
import {
  ApiProblem,
  describeError,
  isSaveConflict,
  type Diagnostic,
  type FunctionCatalogue,
  type RefreshResponse,
  type StoredMapping,
  type TestResult,
  type TraceEntry,
} from '../../api/client';
import type { AuthoringId, Box, BoxPosition, FieldPath, Layout, MappingModel, ParamValue, StructureRef } from '../../generated/mapping-model';
import { applied, refusalOf, type CommandResult, type FieldRefusal, type Refusal } from '../../model/command';
import { deleteBoxes } from '../../model/delete-box';
import { deleteTargetFieldMapping } from '../../model/delete-target-field-mapping';
import { insertBox } from '../../model/insert-box';
import { link } from '../../model/link';
import { paste } from '../../model/paste';
import { remap } from '../../model/remap';
import { disconnectBox, rewire, type WireEnd } from '../../model/rewire';
import { sameModel } from '../../model/same-model';
import { setContext } from '../../model/set-context';
import { setDescription } from '../../model/set-description';
import { setInput } from '../../model/set-input';
import { setParam, setParams } from '../../model/set-param';
import { setPositions } from '../../model/set-positions';
import { setStructure } from '../../model/set-structure';
import { setResult, unwire, wire } from '../../model/wire';

/** How many edits undo keeps (spec #104, story 51: at least 100); an older one is dropped. */
export const UNDO_DEPTH = 100;

/** How long after the last edit the working copy is compiled (ADR-0013). */
export const COMPILE_DELAY_MS = 500;

/** The code of a field path the structure no longer has (GLOSSARY: Orphaned reference). */
const ORPHANED_REFERENCE = 'orphaned-reference';

/** The Mapping studio's tabs, in their order on the tab bar (ADR-0028); the active one is the URL hash. */
export const STUDIO_TABS = ['overview', 'definition', 'test', 'signature', 'functions'] as const;
export type StudioTab = (typeof STUDIO_TABS)[number];

/** A dialog of the canvas: a node's parameters, an orphaned box's remap or a node's Display Queue, by the node's id; or Add node. */
export type CanvasDialog = { kind: 'parameters' | 'remap' | 'queue'; node: string } | { kind: 'add' };

/** The ports of one open mapping's store. */
export interface WorkingCopyPorts {
  /** Draws a fresh authoring id. */
  newId: () => AuthoringId;
  /** Saves a model on condition of the ETag and resolves to what the service wrote (ADR-0013, ADR-0027). */
  save: (model: MappingModel, etag: string) => Promise<StoredMapping>;
  /** Reads the mapping file from disk. */
  load: () => Promise<StoredMapping>;
  /** Compiles a model and resolves to its diagnostics (ADR-0015, ADR-0027); `signal` aborts the call. */
  compile: (model: MappingModel, signal: AbortSignal) => Promise<Diagnostic[]>;
}

export interface WorkingCopyState {
  /** The last canonical model the service returned. */
  baseline: MappingModel;
  /** The baseline's ETag, sent as `If-Match` on save. */
  etag: string;
  /** The model as the author is editing it. */
  workingCopy: MappingModel;
  /** The working copies before each edit, oldest first, at most `UNDO_DEPTH`. */
  past: readonly MappingModel[];
  /** The working copies undo stepped back from, the next redo first. */
  future: readonly MappingModel[];
  /**
   * What the canvas has selected (ADR-0028, UI slice), by id, the last selected last: its boxes, preview nodes, target
   * and wires; several boxes are selected together for copy. Selecting is no undo step.
   */
  selected: readonly AuthoringId[];
  /** The dialog the canvas has open (ADR-0028, UI slice), or none. */
  canvasDialog: CanvasDialog | null;
  /** The boxes copy took, by the ids they had then, and the target field whose mapping they came from. */
  clipboard: Clipboard | null;
  /** Why the last command was refused; `null` once a command applies. */
  refusal: Refusal | null;
  /** A save answered that the file changed on disk: the author chooses Reload or Keep editing (ADR-0027). */
  conflict: boolean;
  /**
   * The latest diagnostics of the working copy: the last compile's, or a refresh's, a refused save's or a test run's
   * until the next compile.
   */
  diagnostics: Diagnostic[];
  /** The title of the transport error the last compile failed with; `null` once a compile answers. */
  compileFailure: string | null;
  /** How many compiles answered, with diagnostics or a failure: a change tells that the latest one answered. */
  compiles: number;
  /** What the last test run answered: output or a runtime error with its trace, or compile's refusal; `null` before a run. */
  lastRun: TestResult | null;
  /** The working copy the last test run ran; its trace is a stale trace once the working copy differs from it. */
  lastRunModel: MappingModel | null;
  /** The studio's active tab (ADR-0028, UI slice). */
  tab: StudioTab;
  /** Whether the studio is in edit mode; a mapping opens in view mode (GLOSSARY: View mode). */
  editing: boolean;
  /** The target field whose mapping the studio shows, or none. */
  selectedTarget: FieldPath | null;
  /**
   * The preview features (ADR-0028, `studio/mock`) that hold a draft of this edit session, by a name each picks. A draft
   * is unsaved like a change of the working copy, but never part of it: its owner keeps it in the browser.
   */
  previewDrafts: readonly string[];
  /** How many saves succeeded: a change tells a preview's owner to keep its draft as the saved state. */
  commits: number;
  /** How many times the working copy's changes were dropped (discard, reload): a change tells a preview to drop its draft. */
  reverts: number;
  setTab: (tab: StudioTab) => void;
  /** Switches the studio to edit mode. */
  startEditing: () => void;
  /** Selects the target field whose mapping the studio shows; another one drops the canvas selection and dialog. */
  selectTarget: (field: FieldPath | null) => void;
  /** Opens a dialog of the canvas, the one open before closing, or closes it with `null`. */
  openCanvasDialog: (dialog: CanvasDialog | null) => void;
  /** Records whether the preview `name` holds a draft. */
  setPreviewDraft: (name: string, draft: boolean) => void;
  /**
   * Ends edit mode and drops the working copy's changes: the baseline becomes the working copy again, undo and redo are
   * cleared, and so are the selection, the last refusal and the preview drafts (`reverts` counts it).
   */
  discard: () => void;
  /**
   * Links a source field to a target field (the link command in `model/`); `refusals` are the refusals the two fields
   * carry in their trees, which refuse the link (ADR-0027).
   */
  link: (sourceField: FieldPath, targetField: FieldPath, refusals?: { source?: FieldRefusal; target?: FieldRefusal }) => void;
  /**
   * Inserts a box of standard function `fn` into the target field's mapping, with fresh ids. Given a position `at`, the
   * box is placed there and the positions `shown` are written with it (ADR-0029), so that boxes the studio laid out in
   * memory stay where they are; all of it one undo step.
   */
  insertBox: (targetField: FieldPath, fn: string, at?: BoxPosition, shown?: Layout) => void;
  /** Writes positions into the target field mapping's layout, rounded (ADR-0029): a drag or Lay Out Mapping. */
  setPositions: (targetField: FieldPath, layout: Layout) => void;
  /** Moves the end `from` of the wire from box `source` to `to` (the rewire command in `model/`). */
  rewire: (targetField: FieldPath, source: AuthoringId, from: WireEnd, to: WireEnd) => void;
  /** Removes every wire of a box. */
  disconnectBox: (targetField: FieldPath, box: AuthoringId) => void;
  /** Wires box `source`'s output into input slot `slot` of box `target`. */
  wire: (targetField: FieldPath, source: AuthoringId, target: AuthoringId, slot: number) => void;
  /** Removes the wire into input slot `slot` of box `target`. */
  unwire: (targetField: FieldPath, target: AuthoringId, slot: number) => void;
  /** Connects a box to the target field's sink, or disconnects it with `null`. */
  setResult: (targetField: FieldPath, box: AuthoringId | null) => void;
  /**
   * Deletes these boxes as one undo step, or none of them when an id names no box; each selected one is unselected.
   */
  deleteBoxes: (boxes: readonly AuthoringId[]) => void;
  /** Sets box parameter `name` of a standard function box, checked against `catalogue`; the default is left out. */
  setParam: (targetField: FieldPath, box: AuthoringId, name: string, value: ParamValue, catalogue: FunctionCatalogue) => void;
  /** Sets several box parameters of a standard function box as one undo step (the parameter dialog's Apply). */
  setParams: (targetField: FieldPath, box: AuthoringId, params: Record<string, ParamValue>, catalogue: FunctionCatalogue) => void;
  /** Sets a source-field box's context to a proper ancestor of its field; the parent is left out. */
  setContext: (targetField: FieldPath, box: AuthoringId, context: FieldPath) => void;
  /** Wires input slot `slot` of box `target` from box `source`, or leaves it unconnected with `null`. */
  setInput: (targetField: FieldPath, target: AuthoringId, slot: number, source: AuthoringId | null) => void;
  /**
   * Points source-field box `box` at the source field `field`, keeping its id and wires (GLOSSARY: Remap);
   * `sourceRefusal`, the refusal `field` carries in the source tree, refuses the remap (ADR-0027).
   */
  remap: (targetField: FieldPath, box: AuthoringId, field: FieldPath, sourceRefusal?: FieldRefusal) => void;
  /** Removes the target field mapping of `targetField` with all its boxes, such as an orphaned one. */
  deleteTargetFieldMapping: (targetField: FieldPath) => void;
  /** Sets the mapping description (GLOSSARY); an empty one removes it (ADR-0029). */
  setDescription: (description: string) => void;
  /** Sets the structure of one side of the signature (GLOSSARY: Signature), such as a new mapping's first message. */
  setStructure: (side: 'source' | 'target', structure: StructureRef) => void;
  /**
   * Takes the answer of a refresh of the working copy `sent` (ADR-0027); its diagnostics become the set. When one of
   * its fingerprints differs from the working copy's, its model replaces the working copy as one undo step, or, when
   * the working copy changed since `sent`, only its source and target references replace the working copy's, so that
   * the edit stays. Returns the number of `orphaned-reference` diagnostics then, and `null` when no fingerprint changed.
   */
  applyRefresh: (sent: MappingModel, answer: RefreshResponse) => number | null;
  undo: () => void;
  redo: () => void;
  /** Selects one id, or nothing with `null`. */
  select: (id: AuthoringId | null) => void;
  /** Selects these ids together. */
  selectBoxes: (ids: readonly AuthoringId[]) => void;
  /**
   * Puts boxes of the target field's mapping into the clipboard, the selected ones unless `ids` names them, with the
   * `positions` they show at; with none of them in the mapping, nothing.
   */
  copy: (targetField: FieldPath, ids?: readonly AuthoringId[], positions?: Readonly<Record<AuthoringId, BoxPosition>>) => void;
  /**
   * Pastes the clipboard into the target field's mapping with fresh ids (the paste command in `model/`) and selects the
   * pasted boxes; an empty clipboard, or one copied from another target field, pastes nothing. Copied positions are
   * written 24 px further right and down, a further 24 px each time a copy would land on a position `shown` holds (the
   * prototype's duplicate), together with `shown`, as one undo step.
   */
  paste: (targetField: FieldPath, shown?: Layout) => void;
  /**
   * Sends the working copy with the baseline's ETag; what the service wrote becomes the baseline, and the history
   * stays, and the preview drafts are saved (`commits` counts it). A save conflict opens `conflict` and any other error
   * rejects, both keeping the drafts; a refusal with diagnostics (422 `model-refused`) replaces the diagnostics first.
   */
  save: () => Promise<void>;
  /**
   * Drops the working copy, its history and the preview drafts (`reverts` counts it) and loads the file from disk as the
   * baseline. Rejects with the port's error, and then drops nothing.
   */
  reload: () => Promise<void>;
  /** Closes a save conflict and keeps the working copy. */
  keepEditing: () => void;
  /**
   * Compiles the working copy `COMPILE_DELAY_MS` from now, cancelling a compile already scheduled or under way. Every
   * change of the working copy calls it: an edit, undo, redo, a save's canonical model and a reload.
   */
  scheduleCompile: () => void;
  /** Cancels a compile scheduled or under way; its answer is dropped. */
  cancelCompile: () => void;
  /**
   * Keeps what a test run of `sent` answered. Its diagnostics become the latest unless the working copy changed while it
   * ran; the next compile then brings the newer ones.
   */
  recordRun: (sent: MappingModel, result: TestResult) => void;
}

export interface Clipboard {
  targetField: FieldPath;
  boxes: Readonly<Record<AuthoringId, Box>>;
  /** The positions the copied boxes showed at, by the ids they had then; none when copy was given none. */
  positions?: Readonly<Record<AuthoringId, BoxPosition>>;
}

/** How far a pasted copy moves from its original, right and down, each time (the prototype's `duplicateBranch`). */
const PASTE_OFFSET = 24;

export type WorkingCopyStore = ReturnType<typeof createWorkingCopyStore>;

/** Whether the edit session has unsaved changes: the working copy has changes the baseline lacks, or a preview a draft. */
export const selectUnsaved = (state: WorkingCopyState): boolean => state.previewDrafts.length > 0 || !sameModel(state.workingCopy, state.baseline);

/** The trace of the last test run by authoring id; none before a run or when compile refused it (ADR-0015). */
export const selectTrace = (state: WorkingCopyState): Record<string, TraceEntry> | undefined =>
  state.lastRun === null || state.lastRun.kind === 'refused' ? undefined : state.lastRun.trace;

/** Whether the last run's trace is a stale trace: there is one, and the working copy has changed since that run (GLOSSARY). */
export const selectTraceStale = (state: WorkingCopyState): boolean =>
  selectTrace(state) !== undefined && !sameModel(state.workingCopy, state.lastRunModel!);

/** One store per open mapping (ADR-0013). Its actions only call the pure commands of `model/`. */
export function createWorkingCopyStore(opened: StoredMapping, ports: WorkingCopyPorts) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let running: AbortController | undefined;
  /** Counts the compiles scheduled and cancelled: only the answer of the current one is kept. */
  let generation = 0;

  const cancel = () => {
    generation++;
    clearTimeout(timer);
    running?.abort();
  };

  const store = createStore<WorkingCopyState>()((set, get) => {
    /**
     * A command's result: an applied command is one undo step and redo is gone, unless it changed nothing; a refused
     * one leaves the working copy and records why. Returns whether it applied.
     */
    const apply = (result: CommandResult): boolean => {
      if (result.kind === 'refused') {
        set({ refusal: refusalOf(result) });
        return false;
      }
      set((state) =>
        result.model === state.workingCopy
          ? { refusal: null }
          : {
              workingCopy: result.model,
              refusal: null,
              past: [...state.past, state.workingCopy].slice(-UNDO_DEPTH),
              future: [],
            },
      );
      return true;
    };

    return {
      baseline: opened.model,
      etag: opened.etag,
      workingCopy: opened.model,
      past: [],
      future: [],
      selected: [],
      canvasDialog: null,
      clipboard: null,
      refusal: null,
      conflict: false,
      diagnostics: [],
      compileFailure: null,
      compiles: 0,
      lastRun: null,
      lastRunModel: null,
      tab: 'overview',
      editing: false,
      selectedTarget: null,
      previewDrafts: [],
      commits: 0,
      reverts: 0,

      setTab: (tab) => set({ tab }),

      startEditing: () => set({ editing: true }),

      selectTarget: (selectedTarget) =>
        set((state) => (selectedTarget === state.selectedTarget ? {} : { selectedTarget, selected: [], canvasDialog: null })),

      openCanvasDialog: (canvasDialog) => set({ canvasDialog }),

      setPreviewDraft: (name, draft) =>
        set((state) => {
          const others = state.previewDrafts.filter((n) => n !== name);
          return { previewDrafts: draft ? [...others, name] : others };
        }),

      discard: () =>
        set((state) => ({
          workingCopy: state.baseline,
          past: [],
          future: [],
          selected: [],
          refusal: null,
          editing: false,
          previewDrafts: [],
          reverts: state.reverts + 1,
        })),

      link: (sourceField, targetField, refusals) =>
        void apply(
          link(get().workingCopy, {
            sourceField,
            targetField,
            boxId: ports.newId(),
            mappingId: ports.newId(),
            sourceRefusal: refusals?.source,
            targetRefusal: refusals?.target,
          }),
        ),

      insertBox: (targetField, fn, at, shown) => {
        const boxId = ports.newId();
        const inserted = insertBox(get().workingCopy, { targetField, box: { kind: 'function', fn }, boxId, mappingId: ports.newId() });
        if (at === undefined || inserted.kind === 'refused') return void apply(inserted);
        apply(setPositions(inserted.model, { targetField, layout: { ...shown, boxes: { ...shown?.boxes, [boxId]: at } } }));
      },

      setPositions: (targetField, layout) => void apply(setPositions(get().workingCopy, { targetField, layout })),

      rewire: (targetField, source, from, to) => void apply(rewire(get().workingCopy, { targetField, source, from, to })),

      disconnectBox: (targetField, box) => void apply(disconnectBox(get().workingCopy, { targetField, box })),

      wire: (targetField, source, target, slot) => void apply(wire(get().workingCopy, { targetField, source, target, slot })),

      unwire: (targetField, target, slot) => void apply(unwire(get().workingCopy, { targetField, target, slot })),

      setResult: (targetField, box) => void apply(setResult(get().workingCopy, { targetField, box })),

      deleteBoxes: (boxes) => {
        if (apply(deleteBoxes(get().workingCopy, { boxes }))) get().selectBoxes(get().selected.filter((id) => !boxes.includes(id)));
      },

      setParam: (targetField, box, name, value, catalogue) =>
        void apply(setParam(get().workingCopy, { targetField, box, name, value }, catalogue)),

      setParams: (targetField, box, params, catalogue) => void apply(setParams(get().workingCopy, { targetField, box, params }, catalogue)),

      setContext: (targetField, box, context) => void apply(setContext(get().workingCopy, { targetField, box, context })),

      setInput: (targetField, target, slot, source) => void apply(setInput(get().workingCopy, { targetField, target, slot, source })),

      remap: (targetField, box, field, sourceRefusal) => void apply(remap(get().workingCopy, { targetField, box, field, sourceRefusal })),

      deleteTargetFieldMapping: (targetField) => void apply(deleteTargetFieldMapping(get().workingCopy, { targetField })),

      setDescription: (description) => void apply(setDescription(get().workingCopy, { description })),

      setStructure: (side, structure) => void apply(setStructure(get().workingCopy, { side, structure })),

      applyRefresh: (sent, answer) => {
        const current = get().workingCopy;
        set({ diagnostics: answer.diagnostics });
        const { source, target } = answer.model;
        // a side not chosen yet is absent on both (ADR-0029): absent equals absent
        if (source?.fingerprint === current.source?.fingerprint && target?.fingerprint === current.target?.fingerprint) return null;
        apply(applied(current === sent ? answer.model : { ...current, source, target }));
        return answer.diagnostics.filter((d) => d.code === ORPHANED_REFERENCE).length;
      },

      undo: () =>
        set((state) =>
          state.past.length === 0
            ? {}
            : { workingCopy: state.past.at(-1), past: state.past.slice(0, -1), future: [state.workingCopy, ...state.future] },
        ),

      redo: () =>
        set((state) =>
          state.future.length === 0
            ? {}
            : { workingCopy: state.future[0], future: state.future.slice(1), past: [...state.past, state.workingCopy] },
        ),

      select: (id) => set({ selected: id === null ? [] : [id] }),

      selectBoxes: (ids) => set({ selected: ids }),

      copy: (targetField, ids, positions) => {
        const { workingCopy, selected } = get();
        const boxes = workingCopy.targetFieldMappings?.[targetField]?.boxes ?? {};
        const copied = (ids ?? selected).filter((id) => Object.hasOwn(boxes, id));
        if (copied.length > 0) {
          set({ clipboard: { targetField, boxes: Object.fromEntries(copied.map((id) => [id, boxes[id]!])), ...(positions && { positions }) } });
        }
      },

      paste: (targetField, shown) => {
        const { clipboard } = get();
        if (clipboard?.targetField !== targetField) return;
        const fresh = new Map<AuthoringId, AuthoringId>();
        const freshId = (copied: AuthoringId) => {
          const id = ports.newId();
          fresh.set(copied, id);
          return id;
        };
        const pasted = paste(get().workingCopy, { targetField, boxes: clipboard.boxes, freshId, mappingId: ports.newId() });
        if (pasted.kind === 'applied' && clipboard.positions) {
          const copies = Object.entries(clipboard.positions).filter(([id]) => fresh.has(id));
          const taken = new Set(Object.values(shown?.boxes ?? {}).map((p) => `${p.x},${p.y}`));
          let offset = PASTE_OFFSET;
          while (copies.some(([, p]) => taken.has(`${p.x + offset},${p.y + offset}`))) offset += PASTE_OFFSET;
          const boxes = Object.fromEntries(copies.map(([id, p]) => [fresh.get(id)!, { x: p.x + offset, y: p.y + offset }]));
          // every position names a pasted box, so the positions always apply
          apply(setPositions(pasted.model, { targetField, layout: { ...shown, boxes: { ...shown?.boxes, ...boxes } } }));
        } else if (!apply(pasted)) return;
        get().selectBoxes([...fresh.values()]);
      },

      save: async () => {
        const sent = get().workingCopy;
        let stored: StoredMapping;
        try {
          stored = await ports.save(sent, get().etag);
        } catch (error) {
          if (isSaveConflict(error)) {
            set({ conflict: true });
            return;
          }
          if (error instanceof ApiProblem && 'diagnostics' in error.problem) set({ diagnostics: error.problem.diagnostics });
          throw error;
        }
        // An edit made while the save was under way stays in the working copy.
        set((state) => ({
          baseline: stored.model,
          etag: stored.etag,
          workingCopy: state.workingCopy === sent ? stored.model : state.workingCopy,
          previewDrafts: [],
          commits: state.commits + 1,
        }));
      },

      reload: async () => {
        const stored = await ports.load();
        set((state) => ({
          baseline: stored.model,
          etag: stored.etag,
          workingCopy: stored.model,
          past: [],
          future: [],
          selected: [],
          conflict: false,
          previewDrafts: [],
          reverts: state.reverts + 1,
        }));
      },

      keepEditing: () => set({ conflict: false }),

      scheduleCompile: () => {
        cancel();
        const current = generation;
        timer = setTimeout(() => {
          running = new AbortController();
          ports.compile(get().workingCopy, running.signal).then(
            (diagnostics) => {
              if (current === generation) set((state) => ({ diagnostics, compileFailure: null, compiles: state.compiles + 1 }));
            },
            (error: unknown) => {
              if (current === generation) set((state) => ({ compileFailure: describeError(error).title, compiles: state.compiles + 1 }));
            },
          );
        }, COMPILE_DELAY_MS);
      },

      cancelCompile: cancel,

      recordRun: (sent, result) =>
        set((state) => ({
          lastRun: result,
          lastRunModel: sent,
          ...(sameModel(state.workingCopy, sent) ? { diagnostics: result.diagnostics } : {}),
        })),
    };
  });

  store.subscribe((state, previous) => {
    if (state.workingCopy !== previous.workingCopy) state.scheduleCompile();
  });
  return store;
}

/** Tells `listener` each time the working copy starts or stops differing from the baseline; returns the unsubscribe. */
export function watchUnsaved(store: WorkingCopyStore, listener: (unsaved: boolean) => void): () => void {
  return store.subscribe((state, previous) => {
    const unsaved = selectUnsaved(state);
    if (unsaved !== selectUnsaved(previous)) listener(unsaved);
  });
}
