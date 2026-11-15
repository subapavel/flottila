import { describe, expect, test } from 'vitest';
import type { Diagnostic, StoredMapping } from '../../api/client';
import type { MappingModel, StructureRef } from '../../generated/mapping-model';
import { createWorkingCopyStore, selectUnsaved } from './working-copy';

const order: StructureRef = {
  bundle: ['../structures/skeleton/order.xsd'],
  fingerprint: 'sha256:order',
  root: { element: 'Q{urn:flottila:corpus:skeleton}Order' },
};

function store(model: MappingModel) {
  const opened: StoredMapping = { model, etag: '"sha256:base"' };
  const never = () => new Promise<never>(() => {});
  return createWorkingCopyStore(opened, { newId: () => 'id', save: never, load: never, compile: () => new Promise<Diagnostic[]>(() => {}) });
}

describe('the Overview and Signature edits of the working-copy store', () => {
  test('setDescription changes the working copy as one undo step; the same text is no step', () => {
    const s = store({ formatVersion: 1 });

    s.getState().setDescription('Copies the order id.');
    s.getState().setDescription('Copies the order id.');

    expect(s.getState().workingCopy).toEqual({ formatVersion: 1, description: 'Copies the order id.' });
    expect(s.getState().past).toHaveLength(1);
    expect(selectUnsaved(s.getState())).toBe(true);
    s.getState().undo();
    expect(s.getState().workingCopy).toBe(s.getState().baseline);
  });

  test('setStructure sets a side as one undo step; the same structure is no step', () => {
    const s = store({ formatVersion: 1 });

    s.getState().setStructure('target', order);
    s.getState().setStructure('target', order);

    expect(s.getState().workingCopy).toEqual({ formatVersion: 1, target: order });
    expect(s.getState().past).toHaveLength(1);
    expect(s.getState().refusal).toBeNull();
  });
});
