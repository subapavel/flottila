import {
  ActionGroup,
  Alert,
  Button,
  EmptyState,
  EmptyStateBody,
  Form,
  FormGroup,
  FormHelperText,
  FormSelect,
  FormSelectOption,
  HelperText,
  HelperTextItem,
  PageSection,
  Spinner,
  Stack,
  StackItem,
  TextInput,
  Title,
  ToggleGroup,
  ToggleGroupItem,
} from '@patternfly/react-core';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useRef, useState, type FormEvent } from 'react';
import type { AdminApi, Page } from './admin';

export interface MessageSearchProps {
  api: AdminApi;
  /** Opens the message with this id in a tab of its own. */
  onOpen: (id: string) => void;
}

type By = 'key' | 'step' | 'id';

/**
 * What the last search found: the ids, the cursor of the next page and how to read the page after a cursor (null for
 * a search by id, which has one page), and what to say when it found nothing.
 */
interface Found {
  ids: string[];
  next: string | null;
  after: ((cursor: string) => Promise<Page<string>>) | null;
  empty: string;
}

/** The deployed flows a search by step picks from: not asked for yet, being read, read, or refused. */
type Flows = { kind: 'unasked' } | { kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'loaded'; ids: string[] };

type Result = { kind: 'idle' } | { kind: 'searching' } | { kind: 'failed'; message: string } | { kind: 'found'; found: Found; more: boolean };

const reason = (failed: unknown) => (failed instanceof Error ? failed.message : String(failed));

const listed = (page: Page<string>, after: Found['after'], empty: string): Result => ({
  kind: 'found',
  found: { ids: page.items, next: page.next, after, empty },
  more: false,
});

/** What an empty answer to a search by key or by id says. */
const NO_MATCH = 'The node recorded no message that matches. Check the key or the id and search again.';

/**
 * Finds messages on the node, by a business key (its name and value), by a deployed flow and the name of a step its
 * messages passed, or by a message id, and lists the ids found, a page at a time. The flows are read from the node the
 * first time the search by step is chosen. Choosing an id opens that message in a tab of its own.
 */
export function MessageSearch({ api, onOpen }: MessageSearchProps) {
  const [by, setBy] = useState<By>('key');
  const [keyName, setKeyName] = useState('');
  const [keyValue, setKeyValue] = useState('');
  const [flow, setFlow] = useState('');
  const [stepName, setStepName] = useState('');
  const [flows, setFlows] = useState<Flows>({ kind: 'unasked' });
  const [id, setId] = useState('');
  const [result, setResult] = useState<Result>({ kind: 'idle' });
  // only the answer to the latest search is shown
  const latest = useRef(0);

  const ready = by === 'key' ? keyName.trim() !== '' : by === 'step' ? flow !== '' && stepName.trim() !== '' : id.trim() !== '';

  const choose = (chosen: By) => {
    setBy(chosen);
    if (chosen !== 'step' || flows.kind !== 'unasked') return;
    setFlows({ kind: 'loading' });
    api.flows().then(
      (ids) => setFlows({ kind: 'loaded', ids }),
      (failed: unknown) => setFlows({ kind: 'failed', message: reason(failed) }),
    );
  };

  const search = (event: FormEvent) => {
    event.preventDefault();
    if (!ready) return;
    const asked = ++latest.current;
    const show = (next: Result) => {
      if (asked === latest.current) setResult(next);
    };
    setResult({ kind: 'searching' });
    let answer: Promise<Result>;
    if (by === 'key') {
      const name = keyName.trim();
      const value = keyValue;
      answer = api.findByKey(name, value).then((page) => listed(page, (cursor) => api.findByKey(name, value, cursor), NO_MATCH));
    } else if (by === 'step') {
      const step = stepName.trim();
      const empty =
        `No recorded row of the step ${step} of the flow ${flow} was found. A step that the flow's or the step's ` +
        'recording level does not record (ends, errors or off) leaves no row.';
      answer = api.findByStep(flow, step).then((page) => listed(page, (cursor) => api.findByStep(flow, step, cursor), empty));
    } else {
      answer = api.timeline(id.trim()).then((timeline) => listed({ items: timeline === null ? [] : [timeline.id], next: null }, null, NO_MATCH));
    }
    answer.then(show, (failed: unknown) => show({ kind: 'failed', message: reason(failed) }));
  };

  const loadMore = (found: Found) => {
    if (found.after === null || found.next === null) return;
    const asked = ++latest.current;
    setResult({ kind: 'found', found, more: true });
    found.after(found.next).then(
      (page) => {
        if (asked === latest.current) setResult({ kind: 'found', found: { ...found, ids: [...found.ids, ...page.items], next: page.next }, more: false });
      },
      (failed: unknown) => {
        if (asked === latest.current) setResult({ kind: 'failed', message: reason(failed) });
      },
    );
  };

  return (
    <PageSection>
      <Stack hasGutter>
        <StackItem>
          <Title headingLevel="h1">Messages</Title>
        </StackItem>
        <StackItem>
          <Form onSubmit={search}>
            <FormGroup label="Search by" role="group" fieldId="message-search-by">
              <ToggleGroup aria-label="Search by">
                <ToggleGroupItem text="Business key" isSelected={by === 'key'} onChange={() => choose('key')} />
                <ToggleGroupItem text="Flow and step" isSelected={by === 'step'} onChange={() => choose('step')} />
                <ToggleGroupItem text="Message id" isSelected={by === 'id'} onChange={() => choose('id')} />
              </ToggleGroup>
            </FormGroup>
            {by === 'step' && flows.kind === 'failed' && (
              <Alert variant="danger" isInline title="The deployed flows could not be listed">
                {flows.message}
              </Alert>
            )}
            {by === 'key' ? (
              <>
                <FormGroup label="Key name" isRequired fieldId="message-search-key-name">
                  <TextInput id="message-search-key-name" isRequired value={keyName} onChange={(_event, value) => setKeyName(value)} />
                </FormGroup>
                <FormGroup label="Key value" fieldId="message-search-key-value">
                  <TextInput id="message-search-key-value" value={keyValue} onChange={(_event, value) => setKeyValue(value)} />
                </FormGroup>
              </>
            ) : by === 'step' ? (
              <>
                <FormGroup label="Flow" isRequired fieldId="message-search-flow">
                  <FormSelect
                    id="message-search-flow"
                    isRequired
                    isDisabled={flows.kind !== 'loaded' || flows.ids.length === 0}
                    value={flow}
                    onChange={(_event, value) => setFlow(value)}
                  >
                    <FormSelectOption isPlaceholder value="" label={flowPlaceholder(flows)} />
                    {flows.kind === 'loaded' && flows.ids.map((deployed) => <FormSelectOption key={deployed} value={deployed} label={deployed} />)}
                  </FormSelect>
                </FormGroup>
                <FormGroup label="Step name" isRequired fieldId="message-search-step">
                  <TextInput id="message-search-step" isRequired value={stepName} onChange={(_event, value) => setStepName(value)} />
                  <FormHelperText>
                    <HelperText>
                      <HelperTextItem>The arrival is ingress, and a step of a local process is named call/step.</HelperTextItem>
                    </HelperText>
                  </FormHelperText>
                </FormGroup>
              </>
            ) : (
              <FormGroup label="Message id" isRequired fieldId="message-search-id">
                <TextInput id="message-search-id" isRequired value={id} onChange={(_event, value) => setId(value)} />
              </FormGroup>
            )}
            <ActionGroup>
              <Button type="submit" variant="primary" isDisabled={!ready || result.kind === 'searching'}>
                Search
              </Button>
            </ActionGroup>
          </Form>
        </StackItem>
        <StackItem>
          <Results result={result} onOpen={onOpen} onMore={loadMore} />
        </StackItem>
      </Stack>
    </PageSection>
  );
}

/** What the flow list shows before a flow is chosen. */
function flowPlaceholder(flows: Flows): string {
  switch (flows.kind) {
    case 'unasked':
    case 'loading':
      return 'Reading the deployed flows…';
    case 'failed':
      return 'No flows to choose from';
    case 'loaded':
      return flows.ids.length === 0 ? 'No flow is deployed' : 'Choose a flow';
  }
}

function Results({ result, onOpen, onMore }: { result: Result; onOpen: (id: string) => void; onMore: (found: Found) => void }) {
  switch (result.kind) {
    case 'idle':
      return null;
    case 'searching':
      return <Spinner size="lg" aria-label="Searching" />;
    case 'failed':
      return (
        <Alert variant="danger" isInline title="The search failed">
          {result.message}
        </Alert>
      );
    case 'found': {
      const { found, more } = result;
      if (found.ids.length === 0) {
        return (
          <EmptyState titleText="No messages found" headingLevel="h2">
            <EmptyStateBody>{found.empty}</EmptyStateBody>
          </EmptyState>
        );
      }
      return (
        <Stack hasGutter>
          <StackItem>
            <Table aria-label="Messages found" variant="compact">
              <Thead>
                <Tr>
                  <Th>Message id</Th>
                </Tr>
              </Thead>
              <Tbody>
                {found.ids.map((id) => (
                  <Tr key={id}>
                    <Td dataLabel="Message id">
                      <Button variant="link" isInline onClick={() => onOpen(id)}>
                        {id}
                      </Button>
                    </Td>
                  </Tr>
                ))}
              </Tbody>
            </Table>
          </StackItem>
          {found.next !== null && (
            <StackItem>
              <Button variant="secondary" isLoading={more} isDisabled={more} onClick={() => onMore(found)}>
                Load more
              </Button>
            </StackItem>
          )}
        </Stack>
      );
    }
  }
}
