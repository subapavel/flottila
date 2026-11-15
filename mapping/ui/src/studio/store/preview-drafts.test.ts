import { describe, expect, test, vi } from 'vitest';
import { ApiProblem, type StoredMapping } from '../../api/client';
import type { MappingModel } from '../../generated/mapping-model';
import { createWorkingCopyStore, selectUnsaved, watchUnsaved, type WorkingCopyPorts } from './working-copy';

const MODEL: MappingModel = { formatVersion: 1 };
const DESCRIBED: MappingModel = { formatVersion: 1, description: 'saved' };

function store(ports: Partial<WorkingCopyPorts> = {}) {
  const opened: StoredMapping = { model: MODEL, etag: '"1"' };
  return createWorkingCopyStore(opened, {
    newId: () => 'id' as never,
    save: async (model) => ({ model, etag: '"2"' }),
    load: async () => ({ model: DESCRIBED, etag: '"3"' }),
    compile: () => new Promise(() => {}),
    ...ports,
  });
}

describe('preview drafts: the edit session of the preview features (ADR-0028)', () => {
  test('a store opens with no draft, no commit and no revert', () => {
    expect(store().getState()).toMatchObject({ previewDrafts: [], commits: 0, reverts: 0 });
  });

  test('a preview draft makes the session unsaved, with an unchanged working copy; each preview marks its own', () => {
    const s = store();
    const heard = vi.fn();
    watchUnsaved(s, heard);

    s.getState().setPreviewDraft('functions', true);
    s.getState().setPreviewDraft('functions', true);
    s.getState().setPreviewDraft('signature', true);
    expect(s.getState().previewDrafts).toEqual(['functions', 'signature']);
    expect(selectUnsaved(s.getState())).toBe(true);

    s.getState().setPreviewDraft('functions', false);
    expect(s.getState().previewDrafts).toEqual(['signature']);
    s.getState().setPreviewDraft('signature', false);
    expect(selectUnsaved(s.getState())).toBe(false);
    expect(heard.mock.calls).toEqual([[true], [false]]);
  });

  test('a successful save clears the drafts and counts a commit', async () => {
    const save = vi.fn(async (model: MappingModel) => ({ model, etag: '"2"' }));
    const s = store({ save });
    s.getState().setPreviewDraft('functions', true);

    await s.getState().save();

    expect(save).toHaveBeenCalledWith(MODEL, '"1"');
    expect(s.getState()).toMatchObject({ previewDrafts: [], commits: 1, reverts: 0, etag: '"2"' });
  });

  test('a refused save and a save conflict keep the drafts and count no commit', async () => {
    const refused = new ApiProblem({ type: 'urn:flottila:mapping:problem:internal', title: 'Internal error', status: 500, detail: 'disk full' });
    const conflict = new ApiProblem({ type: 'urn:flottila:mapping:problem:mapping-changed', title: 'Mapping changed', status: 409, detail: 'changed on disk' });
    const s = store({ save: vi.fn().mockRejectedValueOnce(refused).mockRejectedValueOnce(conflict) });
    s.getState().setPreviewDraft('functions', true);

    await expect(s.getState().save()).rejects.toBe(refused);
    await s.getState().save();

    expect(s.getState()).toMatchObject({ previewDrafts: ['functions'], commits: 0, conflict: true });
  });

  test('discard clears the drafts and counts a revert', () => {
    const s = store();
    s.getState().startEditing();
    s.getState().setPreviewDraft('functions', true);

    s.getState().discard();

    expect(s.getState()).toMatchObject({ previewDrafts: [], reverts: 1, commits: 0, editing: false });
  });

  test('reload clears the drafts and counts a revert; a failed reload keeps them', async () => {
    const s = store();
    s.getState().setPreviewDraft('functions', true);

    await s.getState().reload();

    expect(s.getState()).toMatchObject({ previewDrafts: [], reverts: 1, baseline: DESCRIBED });

    const failing = store({ load: () => Promise.reject(new Error('offline')) });
    failing.getState().setPreviewDraft('functions', true);
    await expect(failing.getState().reload()).rejects.toThrow('offline');
    expect(failing.getState()).toMatchObject({ previewDrafts: ['functions'], reverts: 0 });
  });
});
