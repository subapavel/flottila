import { mutationOptions } from '@tanstack/react-query';
import type { ApiClient, RefreshResponse } from '../../api/client';
import type { MappingModel } from '../../generated/mapping-model';
import type { WorkingCopyStore } from '../store/working-copy';

/** A refresh of the working copy as it is now: what was sent, and the answer, for `applyRefresh`. */
export const refreshMutation = (api: ApiClient, mappingId: string, store: WorkingCopyStore) =>
  mutationOptions({
    mutationFn: async (): Promise<{ sent: MappingModel; answer: RefreshResponse }> => {
      const sent = store.getState().workingCopy;
      return { sent, answer: await api.refresh(mappingId, sent) };
    },
  });
