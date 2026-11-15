import { describe, expect, test } from 'vitest';
import type { Diagnostic, RefreshResponse, StoredMapping } from '../../api/client';
import type { MappingModel } from '../../generated/mapping-model';
import { createWorkingCopyStore, selectUnsaved } from './working-copy';

const NS = 'Q{urn:flottila:corpus:skeleton}';
const ORDER = `/${NS}Order`;
const ORDER_ID = `${ORDER}/OrderId`;
const ITEM = `${ORDER}/${NS}Item`;
const CODE = `${ITEM}/${NS}Code`;
const SKU = `${ITEM}/${NS}Sku`;
const GONE = `${ORDER}/Gone`;

const structure = {
  bundle: ['../structures/skeleton/order.xsd'] as [string],
  fingerprint: 'sha256:old',
  root: { element: `${NS}Order` },
};

/** OrderId: a1 reads Item/Code in the Order's context, into toUpperCase f1, the result. Gone: t2 with g1. */
const opened: MappingModel = {
  formatVersion: 1,
  source: structure,
  target: structure,
  targetFieldMappings: {
    [ORDER_ID]: {
      id: 't1',
      result: 'f1',
      boxes: { a1: { kind: 'source', field: CODE, context: ORDER }, f1: { kind: 'function', fn: 'toUpperCase', inputs: ['a1'] } },
    },
    [GONE]: { id: 't2', result: 'g1', boxes: { g1: { kind: 'source', field: SKU } } },
  },
};

const orphaned = (authoringId: string): Diagnostic => ({
  severity: 'error',
  code: 'orphaned-reference',
  message: `orphaned at ${authoringId}`,
  location: { kind: 'authoring-id', authoringId },
});
const loadError: Diagnostic = {
  severity: 'error',
  code: 'schema-unreadable',
  message: 'cannot read',
  location: { kind: 'file-position', file: 'order.xsd', line: 3, column: 7 },
};

/** A refresh's answer for `model`: the fingerprints given per side, the others kept, in a copy as the wire gives it. */
function answer(model: MappingModel, fingerprints: { source?: string; target?: string }, diagnostics: Diagnostic[]): RefreshResponse {
  // the test model's two sides are one object, which structuredClone keeps shared: each side gets a copy of its own
  const copy = structuredClone(model);
  return {
    model: {
      ...copy,
      source: { ...copy.source!, fingerprint: fingerprints.source ?? copy.source!.fingerprint },
      target: { ...copy.target!, fingerprint: fingerprints.target ?? copy.target!.fingerprint },
    },
    diagnostics,
  };
}

function store() {
  const stored: StoredMapping = { model: opened, etag: '"sha256:base"' };
  const never = () => new Promise<never>(() => {});
  return createWorkingCopyStore(stored, {
    newId: () => 'unused',
    save: never,
    load: never,
    compile: () => new Promise<Diagnostic[]>(() => {}),
  });
}

describe('a refresh taken by the working-copy store (ADR-0027)', () => {
  test('with a changed fingerprint replaces the working copy as one undo step, sets unsaved and counts the orphaned references', () => {
    const s = store();
    const sent = s.getState().workingCopy;
    const refreshed = answer(sent, { source: 'sha256:new' }, [orphaned('a1'), orphaned('t2'), loadError]);

    const count = s.getState().applyRefresh(sent, refreshed);

    expect(count).toBe(2);
    expect(s.getState().workingCopy).toBe(refreshed.model);
    expect(s.getState().past).toEqual([opened]);
    expect(s.getState().future).toEqual([]);
    expect(selectUnsaved(s.getState())).toBe(true);
    expect(s.getState().diagnostics).toEqual(refreshed.diagnostics);
  });

  test('a changed target fingerprint alone counts as a change too', () => {
    const s = store();
    const sent = s.getState().workingCopy;

    expect(s.getState().applyRefresh(sent, answer(sent, { target: 'sha256:new' }, []))).toBe(0);
    expect(s.getState().workingCopy.target!.fingerprint).toBe('sha256:new');
    expect(s.getState().past).toHaveLength(1);
  });

  test('undo restores the old fingerprints, and the working copy is no longer unsaved', () => {
    const s = store();
    const sent = s.getState().workingCopy;
    s.getState().applyRefresh(sent, answer(sent, { source: 'sha256:new', target: 'sha256:new' }, []));

    s.getState().undo();

    expect(s.getState().workingCopy.source!.fingerprint).toBe('sha256:old');
    expect(s.getState().workingCopy.target!.fingerprint).toBe('sha256:old');
    expect(selectUnsaved(s.getState())).toBe(false);
  });

  test('with unchanged fingerprints leaves the working copy identical and adds no undo step, and its diagnostics become the set', () => {
    const s = store();
    const sent = s.getState().workingCopy;

    const count = s.getState().applyRefresh(sent, answer(sent, {}, [loadError]));

    expect(count).toBeNull();
    expect(s.getState().workingCopy).toBe(opened);
    expect(s.getState().past).toEqual([]);
    expect(selectUnsaved(s.getState())).toBe(false);
    expect(s.getState().diagnostics).toEqual([loadError]);
  });

  test('an edit made while the refresh was under way stays, and takes the new structure references as one undo step', () => {
    const s = store();
    const sent = s.getState().workingCopy;
    s.getState().deleteTargetFieldMapping(GONE);
    const edited = s.getState().workingCopy;

    s.getState().applyRefresh(sent, answer(sent, { source: 'sha256:new' }, []));

    expect(s.getState().workingCopy).toEqual({ ...edited, source: { ...structure, fingerprint: 'sha256:new' } });
    expect(s.getState().past).toEqual([opened, edited]);
  });

  test('a second answer with the fingerprints the working copy already has changes nothing', () => {
    const s = store();
    const sent = s.getState().workingCopy;
    const refreshed = answer(sent, { source: 'sha256:new' }, []);
    s.getState().applyRefresh(sent, refreshed);

    expect(s.getState().applyRefresh(sent, answer(sent, { source: 'sha256:new' }, []))).toBeNull();
    expect(s.getState().workingCopy).toBe(refreshed.model);
    expect(s.getState().past).toHaveLength(1);
  });
});

describe('remap and the delete of a target field mapping in the store', () => {
  test('remap runs the remap command as one undo step: the box keeps its id and wire, and its context when it still fits', () => {
    const s = store();

    s.getState().remap(ORDER_ID, 'a1', SKU);

    const mapping = s.getState().workingCopy.targetFieldMappings![ORDER_ID]!;
    expect(mapping.boxes.a1).toEqual({ kind: 'source', field: SKU, context: ORDER });
    expect(mapping.boxes.f1).toEqual({ kind: 'function', fn: 'toUpperCase', inputs: ['a1'] });
    expect(s.getState().past).toEqual([opened]);
  });

  test('a refused remap records why and leaves the working copy', () => {
    const s = store();

    s.getState().remap(ORDER_ID, 'f1', SKU);

    expect(s.getState().refusal).toEqual({ code: 'not-source-field-box', id: 'f1' });
    expect(s.getState().workingCopy).toBe(opened);
  });

  test('a remap to a refused source field is refused with the field’s refusal', () => {
    const s = store();
    const refusal = { code: 'ambiguous-field-path', side: 'both' as const, file: 'order.xsd', line: 4, column: 9 };

    s.getState().remap(ORDER_ID, 'a1', SKU, refusal);

    expect(s.getState().refusal).toEqual({ code: 'refused-field', side: 'source', field: SKU, refusal });
    expect(s.getState().workingCopy).toBe(opened);
  });

  test('delete target field mapping removes it as one undo step, and undo brings it back', () => {
    const s = store();

    s.getState().deleteTargetFieldMapping(GONE);

    expect(Object.keys(s.getState().workingCopy.targetFieldMappings!)).toEqual([ORDER_ID]);
    s.getState().undo();
    expect(s.getState().workingCopy).toBe(opened);
  });
});
