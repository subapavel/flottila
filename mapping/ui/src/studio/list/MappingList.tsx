import {
  Button,
  EmptyState,
  EmptyStateActions,
  EmptyStateBody,
  EmptyStateFooter,
  SearchInput,
  Spinner,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import { CubesIcon, ExclamationCircleIcon, SearchIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { describeError, type ApiClient, type MappingSummary } from '../../api/client';
import { Masthead } from '../shell/Masthead';
import './list.css';
import { NewMappingModal } from './NewMappingModal';

const COLUMNS = ['Name', 'Package', 'Source', 'Target', 'Description'] as const;

/** What a cell shows for an absent value: a package of the top folder, a message not chosen, no description. */
const orDash = (value: string | undefined) => (value ? value : '—');

/** Whether a mapping's name, package, messages or description contain `term`, ignoring case. */
function matches(mapping: MappingSummary, term: string): boolean {
  const lower = term.toLowerCase();
  return [mapping.name, mapping.package, mapping.source, mapping.target, mapping.description].some((value) =>
    value?.toLowerCase().includes(lower),
  );
}

export interface MappingListProps {
  api: ApiClient;
  /** The address of a mapping in the studio, which its Open in studio link leads to. */
  studioHref: (id: string) => string;
  /** The start page's own address, which the masthead's brand leads to. */
  listHref: string;
  /** Told the id of a mapping the New mapping modal created. */
  onCreated: (id: string) => void;
}

/**
 * The start page (ADR-0028): under the masthead, a table of the workspace's mappings with a toolbar of a search and
 * New mapping, which opens the New mapping modal. Each row links to the mapping in the studio.
 */
export function MappingList({ api, studioHref, listHref, onCreated }: MappingListProps) {
  const mappings = useQuery({ queryKey: ['mappings'], queryFn: () => api.listMappings() });
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(false);
  const term = search.trim();
  // an empty term is in every name
  const shown = (mappings.data ?? []).filter((m) => matches(m, term));

  /** One row across the table holding an empty state: loading, a refusal, an empty workspace or no match. */
  const stateRow = (state: ReactNode) => (
    <Tr>
      <Td colSpan={COLUMNS.length + 1}>{state}</Td>
    </Tr>
  );

  const body = () => {
    if (mappings.isPending) return stateRow(<EmptyState titleText="Loading the mappings" headingLevel="h2" icon={Spinner} variant="sm" />);
    if (mappings.isError) {
      const failure = describeError(mappings.error);
      return stateRow(
        <EmptyState titleText={failure.title} headingLevel="h2" icon={ExclamationCircleIcon} status="danger" variant="sm">
          <EmptyStateBody>{failure.detail}</EmptyStateBody>
        </EmptyState>,
      );
    }
    if (mappings.data.length === 0) {
      return stateRow(
        <EmptyState titleText="No mappings yet" headingLevel="h2" icon={CubesIcon} variant="sm">
          <EmptyStateBody>Choose New mapping to create one; its messages are chosen in the studio.</EmptyStateBody>
        </EmptyState>,
      );
    }
    if (shown.length === 0) {
      return stateRow(
        <EmptyState titleText="No mappings match" headingLevel="h2" icon={SearchIcon} variant="sm">
          <EmptyStateBody>No mapping's name, package, messages or description contain "{term}".</EmptyStateBody>
          <EmptyStateFooter>
            <EmptyStateActions>
              <Button variant="link" onClick={() => setSearch('')}>
                Clear search
              </Button>
            </EmptyStateActions>
          </EmptyStateFooter>
        </EmptyState>,
      );
    }
    return shown.map((m) => (
      <Tr key={m.id}>
        <Td dataLabel="Name">{m.name}</Td>
        <Td dataLabel="Package">{orDash(m.package)}</Td>
        <Td dataLabel="Source">{orDash(m.source)}</Td>
        <Td dataLabel="Target">{orDash(m.target)}</Td>
        <Td dataLabel="Description" modifier="truncate">
          {orDash(m.description)}
        </Td>
        <Td dataLabel="Actions" modifier="fitContent">
          <a href={studioHref(m.id)}>Open in studio</a>
        </Td>
      </Tr>
    ));
  };

  return (
    <>
      <Masthead homeHref={listHref} />
      <main className="main">
        <header className="tab-line list-line">
          <h1>Mappings</h1>
        </header>
        <div className="panel">
          {/* the inset lines the search up with the table's first column */}
          <Toolbar className="list-toolbar" inset={{ default: 'insetLg' }}>
            <ToolbarContent>
              <ToolbarItem>
                <SearchInput
                  aria-label="Find mappings"
                  placeholder="Find mappings"
                  value={search}
                  onChange={(_, value) => setSearch(value)}
                  onClear={() => setSearch('')}
                />
              </ToolbarItem>
              <ToolbarItem>
                <Button variant="primary" onClick={() => setCreating(true)}>
                  New mapping
                </Button>
              </ToolbarItem>
            </ToolbarContent>
          </Toolbar>
          {/* PatternFly gives a Table the role grid; this one has no grid keyboard navigation: it is a plain table */}
          <Table aria-label="Mappings" role="table" variant="compact">
            <Thead>
              <Tr>
                {COLUMNS.map((column) => (
                  <Th key={column}>{column}</Th>
                ))}
                <Th screenReaderText="Actions" />
              </Tr>
            </Thead>
            <Tbody>{body()}</Tbody>
          </Table>
        </div>
      </main>
      {creating && <NewMappingModal api={api} onClose={() => setCreating(false)} onCreated={onCreated} />}
    </>
  );
}
