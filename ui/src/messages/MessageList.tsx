import {
  Alert,
  Button,
  EmptyState,
  EmptyStateBody,
  Form,
  FormSelect,
  FormSelectOption,
  Label,
  PageSection,
  Spinner,
  Stack,
  StackItem,
  TextInput,
  Title,
  Toolbar,
  ToolbarContent,
  ToolbarItem,
} from '@patternfly/react-core';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import type { AdminApi, Listed, ListQuery, Status } from './admin';
import { duration } from './progress';

export interface MessageListProps {
  api: AdminApi;
  /** Opens the message with this id in a tab of its own. */
  onOpen: (id: string) => void;
  /** The time the list's range ends at; the browser's clock by default. */
  now?: () => Date;
}

/** The time ranges to choose from, each ending now. */
const RANGES = [
  { label: 'Last 15 minutes', minutes: 15 },
  { label: 'Last hour', minutes: 60 },
  { label: 'Last 24 hours', minutes: 24 * 60 },
  { label: 'Last 7 days', minutes: 7 * 24 * 60 },
] as const;

const STATUSES: { value: Status; label: string; status?: 'success' | 'danger' | 'info' }[] = [
  { value: 'completed', label: 'Completed', status: 'success' },
  { value: 'failed', label: 'Failed', status: 'danger' },
  { value: 'running', label: 'Running', status: 'info' },
  { value: 'cancelled', label: 'Cancelled' },
];

/** What the filters are set to; an empty text is no filter. */
interface Filters {
  minutes: number;
  flow: string;
  status: Status | '';
  keyName: string;
  keyValue: string;
  step: string;
}

const DEFAULTS: Filters = { minutes: 60, flow: '', status: '', keyName: '', keyValue: '', step: '' };

/** The deployed flows the flow filter picks from: being read, read, or refused. */
type Flows = { kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'loaded'; ids: string[] };

/** What the list read last: the query, the messages, the cursor of the next page, and whether that page is being read. */
interface Shown {
  query: ListQuery;
  items: Listed[];
  next: string | null;
  more: boolean;
}

type Result = { kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'shown'; shown: Shown };

const BROWSER_CLOCK = () => new Date();

const reason = (failed: unknown) => (failed instanceof Error ? failed.message : String(failed));

/** The query the filters name, over the range that ends at `now`. */
function queryOf(filters: Filters, now: Date): ListQuery {
  const query: ListQuery = { from: new Date(now.getTime() - filters.minutes * 60_000).toISOString(), to: now.toISOString() };
  if (filters.flow !== '') query.flow = filters.flow;
  if (filters.status !== '') query.status = filters.status;
  if (filters.keyName.trim() !== '') query.key = { name: filters.keyName.trim(), value: filters.keyValue };
  if (filters.step.trim() !== '') query.step = filters.step.trim();
  return query;
}

/**
 * The recent messages on the node, newest first, a page at a time: each with its start, flow, status, duration and
 * business keys. It opens on the last hour of every flow; the time range, the flow and the status apply when chosen, the
 * business key and the step name with Search, which also reads the list again up to now. Choosing a message's id opens
 * it in a tab of its own, as does Open with an id typed in.
 */
export function MessageList({ api, onOpen, now = BROWSER_CLOCK }: MessageListProps) {
  const [filters, setFilters] = useState<Filters>(DEFAULTS);
  const [flows, setFlows] = useState<Flows>({ kind: 'loading' });
  const [result, setResult] = useState<Result>({ kind: 'loading' });
  const [id, setId] = useState('');
  // only the answer to the latest read is shown
  const latest = useRef(0);

  // reads the first page of what `chosen` names and shows it, unless a later read began meanwhile
  const read = useCallback(
    (chosen: Filters) => {
      const asked = ++latest.current;
      const query = queryOf(chosen, now());
      api.list(query).then(
        (page) => {
          if (asked === latest.current) setResult({ kind: 'shown', shown: { query, items: page.items, next: page.next, more: false } });
        },
        (failed: unknown) => {
          if (asked === latest.current) setResult({ kind: 'failed', message: reason(failed) });
        },
      );
    },
    [api, now],
  );

  useEffect(() => {
    read(DEFAULTS);
  }, [read]);

  useEffect(() => {
    api.flows().then(
      (ids) => setFlows({ kind: 'loaded', ids }),
      (failed: unknown) => setFlows({ kind: 'failed', message: reason(failed) }),
    );
  }, [api]);

  const readAgain = (chosen: Filters) => {
    setResult({ kind: 'loading' });
    read(chosen);
  };

  const choose = (changed: Partial<Filters>) => {
    const next = { ...filters, ...changed };
    setFilters(next);
    readAgain(next);
  };

  const search = (event: FormEvent) => {
    event.preventDefault();
    readAgain(filters);
  };

  const loadMore = (shown: Shown) => {
    if (shown.next === null) return;
    const asked = ++latest.current;
    setResult({ kind: 'shown', shown: { ...shown, more: true } });
    api.list(shown.query, shown.next).then(
      (page) => {
        if (asked === latest.current) setResult({ kind: 'shown', shown: { ...shown, items: [...shown.items, ...page.items], next: page.next, more: false } });
      },
      (failed: unknown) => {
        if (asked === latest.current) setResult({ kind: 'failed', message: reason(failed) });
      },
    );
  };

  const open = (event: FormEvent) => {
    event.preventDefault();
    if (id.trim() !== '') onOpen(id.trim());
  };

  return (
    <PageSection>
      <Stack hasGutter>
        <StackItem>
          <Title headingLevel="h1">Messages</Title>
        </StackItem>
        {flows.kind === 'failed' && (
          <StackItem>
            <Alert variant="danger" isInline title="The deployed flows could not be listed">
              {flows.message}
            </Alert>
          </StackItem>
        )}
        <StackItem>
          <Form onSubmit={search} aria-label="Filters">
            <Toolbar>
              <ToolbarContent>
                <ToolbarItem>
                  <FormSelect aria-label="Time range" value={filters.minutes} onChange={(_event, value) => choose({ minutes: Number(value) })}>
                    {RANGES.map((range) => (
                      <FormSelectOption key={range.minutes} value={range.minutes} label={range.label} />
                    ))}
                  </FormSelect>
                </ToolbarItem>
                <ToolbarItem>
                  <FormSelect aria-label="Flow" value={filters.flow} isDisabled={flows.kind !== 'loaded'} onChange={(_event, value) => choose({ flow: value })}>
                    <FormSelectOption value="" label="All flows" />
                    {flows.kind === 'loaded' && flows.ids.map((deployed) => <FormSelectOption key={deployed} value={deployed} label={deployed} />)}
                  </FormSelect>
                </ToolbarItem>
                <ToolbarItem>
                  <FormSelect aria-label="Status" value={filters.status} onChange={(_event, value) => choose({ status: value as Status | '' })}>
                    <FormSelectOption value="" label="All statuses" />
                    {STATUSES.map((status) => (
                      <FormSelectOption key={status.value} value={status.value} label={status.label} />
                    ))}
                  </FormSelect>
                </ToolbarItem>
                <ToolbarItem>
                  <TextInput aria-label="Key name" placeholder="Key name" value={filters.keyName} onChange={(_event, value) => setFilters({ ...filters, keyName: value })} />
                </ToolbarItem>
                <ToolbarItem>
                  <TextInput aria-label="Key value" placeholder="Key value" value={filters.keyValue} onChange={(_event, value) => setFilters({ ...filters, keyValue: value })} />
                </ToolbarItem>
                <ToolbarItem>
                  <TextInput aria-label="Step name" placeholder="Step name" value={filters.step} onChange={(_event, value) => setFilters({ ...filters, step: value })} />
                </ToolbarItem>
                <ToolbarItem>
                  <Button type="submit" variant="primary">
                    Search
                  </Button>
                </ToolbarItem>
              </ToolbarContent>
            </Toolbar>
          </Form>
        </StackItem>
        <StackItem>
          <Form onSubmit={open} aria-label="Open a message">
            <Toolbar>
              <ToolbarContent>
                <ToolbarItem>
                  <TextInput aria-label="Message id" placeholder="Message id" value={id} onChange={(_event, value) => setId(value)} />
                </ToolbarItem>
                <ToolbarItem>
                  <Button type="submit" variant="secondary" isDisabled={id.trim() === ''}>
                    Open
                  </Button>
                </ToolbarItem>
              </ToolbarContent>
            </Toolbar>
          </Form>
        </StackItem>
        <StackItem>
          <Results result={result} onOpen={onOpen} onMore={loadMore} />
        </StackItem>
      </Stack>
    </PageSection>
  );
}

function StatusLabel({ status }: { status: Status }) {
  const shown = STATUSES.find((s) => s.value === status) ?? { value: status, label: status };
  return shown.status === undefined ? <Label color="grey">{shown.label}</Label> : <Label status={shown.status}>{shown.label}</Label>;
}

function Results({ result, onOpen, onMore }: { result: Result; onOpen: (id: string) => void; onMore: (shown: Shown) => void }) {
  switch (result.kind) {
    case 'loading':
      return <Spinner size="lg" aria-label="Reading the messages" />;
    case 'failed':
      return (
        <Alert variant="danger" isInline title="The messages could not be listed">
          {result.message}
        </Alert>
      );
    case 'shown': {
      const { shown } = result;
      if (shown.items.length === 0) {
        return (
          <EmptyState titleText="No messages found" headingLevel="h2">
            <EmptyStateBody>
              No message that matches the filters arrived in the time range. A message whose arrival its flow does not record
              (recording level errors or off) is not listed.
            </EmptyStateBody>
          </EmptyState>
        );
      }
      return (
        <Stack hasGutter>
          <StackItem>
            <Table aria-label="Messages" variant="compact">
              <Thead>
                <Tr>
                  <Th>Started at</Th>
                  <Th>Flow</Th>
                  <Th>Status</Th>
                  <Th>Duration</Th>
                  <Th>Business keys</Th>
                  <Th>Message id</Th>
                </Tr>
              </Thead>
              <Tbody>
                {shown.items.map((message) => (
                  <Tr key={message.id}>
                    <Td dataLabel="Started at">{message.startedAt}</Td>
                    <Td dataLabel="Flow">{message.flow}</Td>
                    <Td dataLabel="Status">
                      <StatusLabel status={message.status} />
                    </Td>
                    <Td dataLabel="Duration">{duration(message.durationNanos)}</Td>
                    <Td dataLabel="Business keys" modifier="breakWord">
                      {Object.entries(message.keys)
                        .map(([name, value]) => `${name}=${value}`)
                        .join(', ')}
                    </Td>
                    <Td dataLabel="Message id">
                      <Button variant="link" isInline onClick={() => onOpen(message.id)}>
                        {message.id}
                      </Button>
                    </Td>
                  </Tr>
                ))}
              </Tbody>
            </Table>
          </StackItem>
          {shown.next !== null && (
            <StackItem>
              <Button variant="secondary" isLoading={shown.more} isDisabled={shown.more} onClick={() => onMore(shown)}>
                Load more
              </Button>
            </StackItem>
          )}
        </Stack>
      );
    }
  }
}
