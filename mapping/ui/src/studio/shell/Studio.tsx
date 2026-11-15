import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import type { ApiClient, StoredMapping } from '../../api/client';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { RefusedMapping } from './RefusedMapping';
import { createWorkingCopyStore } from '../store/working-copy';
import { newAuthoringId } from '../../model/authoring-id';
import { Masthead } from './Masthead';
import { lastTabKey, openingTab, StudioShell } from './StudioShell';

export interface StudioProps {
  /** The mapping's id: its workspace path without the file suffix (ADR-0014). */
  mappingId: string;
  /** The port to the service. */
  api: ApiClient;
  /** The port for viewer preferences. */
  browserState: BrowserStateStore;
  /** Where the masthead's brand leads: the start page. */
  listHref: string;
  /** Opens the mapping in edit mode, as Create does (ADR-0028); otherwise it opens in view mode. */
  startEditing?: boolean;
  /** Told whether the open mapping has unsaved changes, at open and at each change. */
  onUnsavedChange?: (unsaved: boolean) => void;
}

/** The store of an open mapping (ADR-0013), its ports wired to `api`. */
export function studioStore(api: ApiClient, mappingId: string, opened: StoredMapping) {
  return createWorkingCopyStore(opened, {
    newId: () => newAuthoringId(),
    save: (model, etag) => api.saveMapping(mappingId, model, etag),
    load: () => api.getMapping(mappingId),
    compile: (model, signal) => api.compile(mappingId, model, signal).then((compiled) => compiled.diagnostics),
  });
}

/**
 * The Mapping studio of one mapping (ADR-0028), router-free over injected ports with its own query client (ADR-0013):
 * it loads the mapping, shows a refusal with Retry, and opens the loaded mapping on the tab `openingTab` names.
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
  const { mappingId, api, listHref } = props;
  const mapping = useQuery({ queryKey: ['mapping', mappingId], queryFn: () => api.getMapping(mappingId) });
  if (mapping.isSuccess) return <Opened {...props} opened={mapping.data} />;
  return (
    <>
      <Masthead homeHref={listHref} />
      <main className="main">
        {mapping.isPending ? (
          <p>Opening {mappingId}…</p>
        ) : (
          <RefusedMapping error={mapping.error} retrying={mapping.isFetching} onRetry={() => void mapping.refetch()} />
        )}
      </main>
    </>
  );
}

function Opened({ mappingId, api, browserState, listHref, startEditing, onUnsavedChange, opened }: StudioProps & { opened: StoredMapping }) {
  const [store] = useState(() => {
    const created = studioStore(api, mappingId, opened);
    created.getState().setTab(openingTab(window.location.hash, browserState.read(lastTabKey(mappingId))));
    if (startEditing) created.getState().startEditing();
    return created;
  });
  return <StudioShell mappingId={mappingId} api={api} store={store} browserState={browserState} listHref={listHref} onUnsavedChange={onUnsavedChange} />;
}
