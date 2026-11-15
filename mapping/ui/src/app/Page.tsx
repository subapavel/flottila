import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { BrowserStateStore } from '../studio/browser-state/browser-state';
import { MappingList } from '../studio/list/MappingList';
import { DiscardDialog } from '../studio/shell/DiscardDialog';
import { Studio } from '../studio/shell/Studio';
import { listHref, mappingOf, studioHref } from './routes';

export interface PageProps {
  api: ApiClient;
  browserState: BrowserStateStore;
}

/** Which page shows: the start page, or a mapping in the studio, in edit mode when Create just made it. */
interface Route {
  mapping: string | null;
  editing: boolean;
}

/** The state the page gives each history entry of its document: the entry's place, counted from the one it opened on. */
interface EntryState {
  index: number;
}

const indexOf = (state: unknown): number | undefined => {
  const index = (state as Partial<EntryState> | null)?.index;
  return typeof index === 'number' ? index : undefined;
};

/**
 * The page (ADR-0028), router-free: the start page, or the studio of `?mapping=<id>`. Open in studio and the brand are
 * plain links to another query, another document, so leaving a studio with unsaved changes by them meets its
 * `beforeunload` question. Create opens the new mapping in the same document, in edit mode on Signature, with a history
 * entry; Back from it returns to the start page, but with unsaved changes it first returns to the studio's entry and
 * asks "Discard changes?", as `beforeunload` does not ask within a document.
 */
export function Page({ api, browserState }: PageProps) {
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }));
  const [route, setRoute] = useState<Route>(() => ({ mapping: mappingOf(window.location.search), editing: false }));
  const here = useRef(0);
  const unsaved = useRef(false);
  const reportUnsaved = useCallback((value: boolean) => {
    unsaved.current = value;
  }, []);
  /** Where Back or Forward went while the open mapping had unsaved changes, and that mapping's name, while it asks. */
  const [leaving, setLeaving] = useState<{ to: number; name: string } | null>(null);

  useEffect(() => history.replaceState({ index: 0 } satisfies EntryState, ''), []);
  useEffect(() => {
    const moved = (event: PopStateEvent) => {
      const stamped = indexOf(event.state);
      // an entry without the page's state is a fragment navigation, a typed hash, which adds it right after this one
      const index = stamped ?? here.current + 1;
      if (stamped === undefined) history.replaceState({ index } satisfies EntryState, '');
      const mapping = mappingOf(window.location.search);
      if (route.mapping !== null && mapping !== route.mapping && unsaved.current) {
        history.go(here.current - index);
        setLeaving({ to: index, name: route.mapping.slice(route.mapping.lastIndexOf('/') + 1) });
        return;
      }
      here.current = index;
      setRoute({ mapping, editing: false });
    };
    window.addEventListener('popstate', moved);
    return () => window.removeEventListener('popstate', moved);
  }, [route.mapping]);

  const search = window.location.search;
  const created = (id: string) => {
    here.current += 1;
    history.pushState({ index: here.current } satisfies EntryState, '', studioHref(search, id, 'signature'));
    setRoute({ mapping: id, editing: true });
  };
  const leave = (to: number) => {
    unsaved.current = false;
    setLeaving(null);
    history.go(to - here.current);
  };

  return (
    <QueryClientProvider client={queryClient}>
      {route.mapping === null ? (
        <MappingList api={api} studioHref={(id) => studioHref(search, id, 'definition')} listHref={listHref(search)} onCreated={created} />
      ) : (
        <Studio
          key={route.mapping}
          mappingId={route.mapping}
          api={api}
          browserState={browserState}
          listHref={listHref(search)}
          startEditing={route.editing}
          onUnsavedChange={reportUnsaved}
        />
      )}
      {leaving !== null && (
        <DiscardDialog isOpen name={leaving.name} then="leave the studio" onDiscard={() => leave(leaving.to)} onKeepEditing={() => setLeaving(null)} />
      )}
    </QueryClientProvider>
  );
}
