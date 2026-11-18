import {
  Alert,
  Bullseye,
  Button,
  Content,
  EmptyState,
  EmptyStateBody,
  Flex,
  FlexItem,
  HelperText,
  HelperTextItem,
  Label,
  PageSection,
  Spinner,
  Stack,
  StackItem,
  Title,
} from '@patternfly/react-core';
import { SyncAltIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useCallback, useEffect, useState } from 'react';
import type { EndpointList as Listed, EndpointsApi } from './api';

export interface EndpointListProps {
  api: EndpointsApi;
  /** Opens the endpoint file at this path, whose id is `id`, in a tab of its own. */
  onOpen: (path: string, id: string) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed };

/**
 * The endpoint files of the working folder: each one's id, scheme, direction and path, the id opening its settings form
 * in a tab of its own; a file that cannot be read shows why. Without a working folder it says how to set one.
 */
export function EndpointList({ api, onOpen }: EndpointListProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);

  useEffect(() => {
    let current = true;
    api.list().then(
      (list) => current && setResult({ kind: 'read', list }),
      (error: unknown) => current && setResult({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, asked]);

  let body;
  if (result.kind === 'reading') {
    body = (
      <Bullseye>
        <Spinner aria-label="Reading the working folder" />
      </Bullseye>
    );
  } else if (result.kind === 'failed') {
    body = (
      <Alert variant="danger" isInline title="The working folder could not be read">
        {result.message}
      </Alert>
    );
  } else if (!result.list.configured) {
    body = (
      <EmptyState titleText="No working folder" headingLevel="h2">
        <EmptyStateBody>
          Start the UI with <code>RAIL8_WORKSPACE</code> set to the folder that holds your packages, such as{' '}
          <code>RAIL8_WORKSPACE=$HOME/rail8-workspace npm run dev</code>.
        </EmptyStateBody>
      </EmptyState>
    );
  } else if (result.list.items.length === 0) {
    body = (
      <EmptyState titleText="No endpoint files" headingLevel="h2">
        <EmptyStateBody>The working folder holds no YAML file of the kind endpoint.</EmptyStateBody>
      </EmptyState>
    );
  } else {
    body = (
      <Stack hasGutter>
        {result.list.truncated && (
          <StackItem>
            <Alert variant="warning" isInline title="Not every file of the working folder was looked at">
              The working folder holds more YAML files than are read; only the first ones are listed.
            </Alert>
          </StackItem>
        )}
        <StackItem>
          <Table aria-label="Endpoint files" variant="compact">
            <Thead>
              <Tr>
                <Th>Endpoint</Th>
                <Th>Scheme</Th>
                <Th>Direction</Th>
                <Th>File</Th>
              </Tr>
            </Thead>
            <Tbody>
              {result.list.items.map((item) => (
                <Tr key={item.path}>
                  {'problem' in item ? (
                    <>
                      <Td dataLabel="Endpoint">
                        <HelperText>
                          <HelperTextItem variant="error">{item.problem}</HelperTextItem>
                        </HelperText>
                      </Td>
                      <Td dataLabel="Scheme" />
                      <Td dataLabel="Direction" />
                    </>
                  ) : (
                    <>
                      <Td dataLabel="Endpoint">
                        <Button variant="link" isInline onClick={() => onOpen(item.path, item.id)}>
                          {item.id}
                        </Button>
                      </Td>
                      <Td dataLabel="Scheme">{item.scheme}</Td>
                      <Td dataLabel="Direction">{item.direction}</Td>
                    </>
                  )}
                  <Td dataLabel="File">{item.path}</Td>
                </Tr>
              ))}
            </Tbody>
          </Table>
        </StackItem>
      </Stack>
    );
  }

  return (
    <PageSection>
      <Stack hasGutter>
        <StackItem>
          <Flex alignItems={{ default: 'alignItemsCenter' }}>
            <FlexItem>
              <Title headingLevel="h1">Endpoints</Title>
            </FlexItem>
            {result.kind === 'read' && result.list.configured && !result.list.editable && (
              <FlexItem>
                <Label variant="outline">Read-only</Label>
              </FlexItem>
            )}
            <FlexItem align={{ default: 'alignRight' }}>
              <Button variant="secondary" size="sm" icon={<SyncAltIcon />} onClick={refresh}>
                Refresh
              </Button>
            </FlexItem>
          </Flex>
          <Content component="p">The endpoint files of the working folder. Each one opens in a tab of its own, read-only until you choose Edit.</Content>
        </StackItem>
        <StackItem>{body}</StackItem>
      </Stack>
    </PageSection>
  );
}
