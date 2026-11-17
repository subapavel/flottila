import { PageSection, Spinner, Content } from '@patternfly/react-core';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ApiClient, StoredMapping } from '../api/client';
import { newAuthoringId } from '../model/authoring-id';
import type { BrowserStateStore } from './browser-state/browser-state';
import { RefusedMapping } from './RefusedMapping';
import { createWorkingCopyStore } from './store/working-copy';
import { lastTabKey, openingTab, StudioFrame, type CloseGuard } from './StudioFrame';

export interface StudioProps {
  /** The mapping's id: its workspace path without the file suffix. */
  mappingId: string;
  /** The port to the mapping service. */
  api: ApiClient;
  /** The port for viewer preferences. */
  browserState: BrowserStateStore;
  /** Opens the mapping in edit mode, as Create does; otherwise it opens in view mode. */
  startEditing?: boolean;
  /** Told after each save the service accepted. */
  onSaved?: () => void;
  /** Given the question to ask before the mapping's tab closes while it has unsaved changes, and null when it has none. */
  guardClose?: (guard: CloseGuard | null) => void;
}

/** The store of an open mapping, its ports wired to `api`. */
export function studioStore(api: ApiClient, mappingId: string, opened: StoredMapping) {
  return createWorkingCopyStore(opened, {
    newId: () => newAuthoringId(),
    save: (model, etag) => api.saveMapping(mappingId, model, etag),
    load: () => api.getMapping(mappingId),
    compile: (model, signal) => api.compile(mappingId, model, signal).then((compiled) => compiled.diagnostics),
  });
}

/**
 * The Mapping studio of one mapping, over injected ports with its own query client: it loads the mapping, shows a
 * refusal with Retry, and opens the loaded mapping on the tab it was last left on.
 */
export function Studio(props: StudioProps) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        // the loaded mapping is the baseline's source; a refetch must not replace it behind the working copy
        defaultOptions: { queries: { retry: false, staleTime: Infinity, refetchOnWindowFocus: false } },
      }),
  );
  return (
    <QueryClientProvider client={queryClient}>
      <Loader {...props} />
    </QueryClientProvider>
  );
}

function Loader(props: StudioProps) {
  const { mappingId, api } = props;
  const mapping = useQuery({ queryKey: ['mapping', mappingId], queryFn: () => api.getMapping(mappingId) });
  if (mapping.isSuccess) return <Opened {...props} opened={mapping.data} />;
  return (
    <PageSection>
      {mapping.isPending ? (
        <Content component="p">
          <Spinner size="md" aria-hidden /> Opening {mappingId}…
        </Content>
      ) : (
        <RefusedMapping error={mapping.error} retrying={mapping.isFetching} onRetry={() => void mapping.refetch()} />
      )}
    </PageSection>
  );
}

function Opened({ mappingId, api, browserState, startEditing, onSaved, guardClose, opened }: StudioProps & { opened: StoredMapping }) {
  const [store] = useState(() => {
    const created = studioStore(api, mappingId, opened);
    created.getState().setTab(openingTab(browserState.read(lastTabKey(mappingId))));
    if (startEditing) created.getState().startEditing();
    return created;
  });
  return <StudioFrame mappingId={mappingId} api={api} store={store} browserState={browserState} onSaved={onSaved} guardClose={guardClose} />;
}
