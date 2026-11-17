import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Screen } from '../screens';
import type { ApiClient } from './api/client';
import { MappingList } from './list/MappingList';
import { Studio } from './studio/Studio';
import type { CloseGuard } from './studio/StudioFrame';

export interface MappingTabOptions {
  /** Opens the mapping in edit mode, as Create does. */
  editing?: boolean;
  /** Told after each save of the mapping. */
  onSaved?: () => void;
}

/**
 * The tab of one mapping in the studio: its id is `mapping:` and the mapping's id, its title the mapping's name.
 * Closing it while the mapping has unsaved changes asks first.
 */
export function mappingTab(api: ApiClient, id: string, options: MappingTabOptions = {}): Screen {
  let guard: CloseGuard | null = null;
  const guardClose = (next: CloseGuard | null) => {
    guard = next;
  };
  return {
    id: `mapping:${id}`,
    title: id.slice(id.lastIndexOf('/') + 1),
    render: () => (
      <Studio
        mappingId={id}
        api={api}
        startEditing={options.editing}
        onSaved={options.onSaved}
        guardClose={guardClose}
      />
    ),
    beforeClose: (close) => (guard === null ? close() : guard(close)),
  };
}

/**
 * The Mappings screen over `api`: the workspace's mappings, each one opening in a tab of its own, and a mapping just
 * created opening in edit mode. A save in any mapping's tab brings the table up to date.
 */
export function mappingsScreen(api: ApiClient): Screen {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onSaved = () => void queryClient.invalidateQueries({ queryKey: ['mappings'] });
  return {
    id: 'mappings',
    title: 'Mappings',
    render: (open) => (
      <QueryClientProvider client={queryClient}>
        <MappingList
          api={api}
          onOpen={(id) => open(mappingTab(api, id, { onSaved }))}
          onCreated={(id) => open(mappingTab(api, id, { editing: true, onSaved }))}
        />
      </QueryClientProvider>
    ),
  };
}
