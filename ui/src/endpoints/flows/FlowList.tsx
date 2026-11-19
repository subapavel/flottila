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
  PageSection,
  Spinner,
  Stack,
  StackItem,
  Title,
} from '@patternfly/react-core';
import { SyncAltIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useCallback, useEffect, useState } from 'react';
import type { FlowList as Listed, FlowsApi } from './api';

export interface FlowListProps {
  api: FlowsApi;
  /** Opens the flow file at this path, whose id is `id`, in a tab of its own. */
  onOpen: (path: string, id: string) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed };

/**
 * The flow files of the working folder: each one's id and path, the id opening its picture in a tab of its own; a file
 * that cannot be read shows why, and opens with its text. Without a working folder it says how to set one.
 */
export function FlowList({ api, onOpen }: FlowListProps) {
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
      <EmptyState titleText="No flow files" headingLevel="h2">
        <EmptyStateBody>The working folder holds no YAML file of the kind flow.</EmptyStateBody>
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
          <Table aria-label="Flow files" variant="compact">
            <Thead>
              <Tr>
                <Th>Flow</Th>
                <Th>File</Th>
              </Tr>
            </Thead>
            <Tbody>
              {result.list.items.map((item) => (
                <Tr key={item.path}>
                  <Td dataLabel="Flow">
                    <Stack>
                      <StackItem>
                        <Button variant="link" isInline onClick={() => onOpen(item.path, 'id' in item ? item.id : (item.path.split('/').at(-1) ?? item.path))}>
                          {'id' in item ? item.id : item.path.split('/').at(-1)}
                        </Button>
                      </StackItem>
                      {'problem' in item && (
                        <StackItem>
                          <HelperText>
                            <HelperTextItem variant="error">{item.problem}</HelperTextItem>
                          </HelperText>
                        </StackItem>
                      )}
                    </Stack>
                  </Td>
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
              <Title headingLevel="h1">Flows</Title>
            </FlexItem>
            <FlexItem align={{ default: 'alignRight' }}>
              <Button variant="secondary" size="sm" icon={<SyncAltIcon />} onClick={refresh}>
                Refresh
              </Button>
            </FlexItem>
          </Flex>
          <Content component="p">The flow files of the working folder. Each one opens in a tab of its own as a picture of its steps.</Content>
        </StackItem>
        <StackItem>{body}</StackItem>
      </Stack>
    </PageSection>
  );
}
