import { mutationOptions, queryOptions, skipToken, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ApiClient, RefreshResponse, StructureRequest } from '../../api/client';
import type { MappingModel, StructureRef } from '../../generated/mapping-model';
import type { WorkingCopyStore } from '../store/working-copy';

/** What a structure is loaded from: its bundle and root, against the mapping's folder, the package. */
const structureRequest = (mappingId: string, { bundle, root }: Pick<StructureRef, 'bundle' | 'root'>): StructureRequest => ({
  base: mappingId.split('/').slice(0, -1).join('/'),
  bundle,
  root,
});

const structureQuery = (api: ApiClient, request: StructureRequest) => queryOptions({ queryKey: ['structure', request], queryFn: () => api.loadStructure(request) });

/**
 * The structure of one side as the service loads it; the trees and the rest of the studio share its query. A side not
 * chosen yet (ADR-0029) loads nothing, and its query keeps no data.
 */
export function useStructure(mappingId: string, api: ApiClient, ref: StructureRef | undefined) {
  const request = ref && structureRequest(mappingId, ref);
  return useQuery({ queryKey: ['structure', request ?? null], queryFn: request ? () => api.loadStructure(request) : skipToken });
}

/**
 * Loads a structure being chosen, through the query `useStructure` reads, so that the trees show it without a second
 * load. It always loads, never the cache's answer: a structure the service refused may have been fixed on disk since.
 */
export function useLoadStructure(mappingId: string, api: ApiClient) {
  const queryClient = useQueryClient();
  return (choice: Pick<StructureRef, 'bundle' | 'root'>) => queryClient.fetchQuery({ ...structureQuery(api, structureRequest(mappingId, choice)), staleTime: 0 });
}

/** A refresh of the working copy as it is now (ADR-0027): what was sent, and the answer, for `applyRefresh`. */
export const refreshMutation = (api: ApiClient, mappingId: string, store: WorkingCopyStore) =>
  mutationOptions({
    mutationFn: async (): Promise<{ sent: MappingModel; answer: RefreshResponse }> => {
      const sent = store.getState().workingCopy;
      return { sent, answer: await api.refresh(mappingId, sent) };
    },
  });
