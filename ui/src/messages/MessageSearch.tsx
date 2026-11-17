import {
  ActionGroup,
  Alert,
  Button,
  EmptyState,
  EmptyStateBody,
  Form,
  FormGroup,
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
import type { AdminApi } from './admin';

export interface MessageSearchProps {
  api: AdminApi;
  /** Opens the message with this id in a tab of its own. */
  onOpen: (id: string) => void;
}

type By = 'key' | 'id';

/** What the last search found: the ids, and for a key search the key and the cursor of the next page. */
interface Found {
  ids: string[];
  key: { name: string; value: string } | null;
  next: string | null;
}

type Result = { kind: 'idle' } | { kind: 'searching' } | { kind: 'failed'; message: string } | { kind: 'found'; found: Found; more: boolean };

const reason = (failed: unknown) => (failed instanceof Error ? failed.message : String(failed));

/**
 * Finds messages on the node, by a business key (its name and value) or by a message id, and lists the ids found, a
 * page at a time. Choosing an id opens that message in a tab of its own.
 */
export function MessageSearch({ api, onOpen }: MessageSearchProps) {
  const [by, setBy] = useState<By>('key');
  const [keyName, setKeyName] = useState('');
  const [keyValue, setKeyValue] = useState('');
  const [id, setId] = useState('');
  const [result, setResult] = useState<Result>({ kind: 'idle' });
  // only the answer to the latest search is shown
  const latest = useRef(0);

  const ready = by === 'key' ? keyName.trim() !== '' : id.trim() !== '';

  const search = (event: FormEvent) => {
    event.preventDefault();
    if (!ready) return;
    const asked = ++latest.current;
    const show = (next: Result) => {
      if (asked === latest.current) setResult(next);
    };
    setResult({ kind: 'searching' });
    if (by === 'key') {
      const key = { name: keyName.trim(), value: keyValue };
      api.findByKey(key.name, key.value).then(
        (page) => show({ kind: 'found', found: { ids: page.items, key, next: page.next }, more: false }),
        (failed: unknown) => show({ kind: 'failed', message: reason(failed) }),
      );
    } else {
      const wanted = id.trim();
      api.timeline(wanted).then(
        (timeline) => show({ kind: 'found', found: { ids: timeline === null ? [] : [timeline.id], key: null, next: null }, more: false }),
        (failed: unknown) => show({ kind: 'failed', message: reason(failed) }),
      );
    }
  };

  const loadMore = (found: Found) => {
    if (found.key === null || found.next === null) return;
    const asked = ++latest.current;
    setResult({ kind: 'found', found, more: true });
    api.findByKey(found.key.name, found.key.value, found.next).then(
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
                <ToggleGroupItem text="Business key" isSelected={by === 'key'} onChange={() => setBy('key')} />
                <ToggleGroupItem text="Message id" isSelected={by === 'id'} onChange={() => setBy('id')} />
              </ToggleGroup>
            </FormGroup>
            {by === 'key' ? (
              <>
                <FormGroup label="Key name" isRequired fieldId="message-search-key-name">
                  <TextInput id="message-search-key-name" isRequired value={keyName} onChange={(_event, value) => setKeyName(value)} />
                </FormGroup>
                <FormGroup label="Key value" fieldId="message-search-key-value">
                  <TextInput id="message-search-key-value" value={keyValue} onChange={(_event, value) => setKeyValue(value)} />
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
            <EmptyStateBody>The node recorded no message that matches. Check the key or the id and search again.</EmptyStateBody>
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
