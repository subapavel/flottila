import {
  Alert,
  Bullseye,
  Button,
  Content,
  EmptyState,
  EmptyStateBody,
  Flex,
  FlexItem,
  Form,
  FormGroup,
  FormHelperText,
  HelperText,
  HelperTextItem,
  Modal,
  ModalBody,
  ModalFooter,
  ModalHeader,
  PageSection,
  Spinner,
  Stack,
  StackItem,
  TextInput,
  Title,
} from '@patternfly/react-core';
import { PlusCircleIcon, SyncAltIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useCallback, useEffect, useId, useState } from 'react';
import { FlowsError, type FlowList as Listed, type FlowsApi } from './api';
import { edit } from './edit';

export interface FlowListProps {
  api: FlowsApi;
  /** Opens the flow file at this path, whose id is `id`, in a tab of its own. */
  onOpen: (path: string, id: string) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; list: Listed };

/** What the New flow dialog holds: the file's path, whether it was typed rather than made from the id, and the flow's first keys. */
interface Draft {
  path: string;
  pathTyped: boolean;
  id: string;
  from: string;
  to: string;
}

const ID = /^[a-z][a-z0-9-]*$/;

/** Why each value of `draft` cannot make a flow, by its name. */
function wrongOf(draft: Draft): Partial<Record<keyof Draft, string>> {
  const wrong: Partial<Record<keyof Draft, string>> = {};
  if (!/^[^/].*\.ya?ml$/.test(draft.path) || draft.path.split('/').includes('..')) wrong.path = 'a path inside the working folder that ends in .yaml or .yml';
  for (const k of ['id', 'from', 'to'] as const) if (!ID.test(draft[k])) wrong[k] = 'lower-case letters, digits and hyphens, a letter first';
  return wrong;
}

/**
 * The text of a new flow: the least the schema takes, a synchronous flow arriving at the endpoint `from` with one step
 * that sends to the endpoint `to`.
 */
export const newFlowText = (id: string, from: string, to: string) =>
  edit('', {
    op: 'add',
    path: [],
    value: { schemaVersion: 1, kind: 'flow', id, version: '0.1.0', from: { endpoint: from }, delivery: { mode: 'sync' }, limits: { maxBodyBytes: 1048576 }, steps: [{ type: 'to', endpoint: to }] },
  });

/**
 * The flow files of the working folder: each one's id and path, the id opening its picture in a tab of its own; a file
 * that cannot be read shows why, and opens with its text. Without a working folder it says how to set one. New flow
 * writes a flow file that does not exist yet, and opens it.
 */
export function FlowList({ api, onOpen }: FlowListProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [creating, setCreating] = useState(false);
  const [createFailure, setCreateFailure] = useState<string | null>(null);
  const ids = useId();

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

  // a new flow's file goes beside the first flow listed, else at the top of the working folder
  const first = result.kind === 'read' ? result.list.items[0]?.path : undefined;
  const folder = first === undefined ? '' : first.slice(0, first.lastIndexOf('/') + 1);
  const wrong = draft === null ? {} : wrongOf(draft);
  const create = async () => {
    if (draft === null || Object.keys(wrong).length > 0) return;
    setCreating(true);
    setCreateFailure(null);
    try {
      await api.save(draft.path, newFlowText(draft.id, draft.from, draft.to), null);
      setDraft(null);
      refresh();
      onOpen(draft.path, draft.id);
    } catch (error) {
      setCreateFailure(error instanceof FlowsError && error.status === 412 ? `${draft.path} exists already; nothing was written.` : error instanceof Error ? error.message : String(error));
    } finally {
      setCreating(false);
    }
  };
  const field = (name: 'path' | 'id' | 'from' | 'to', label: string, help: string) => (
    <FormGroup label={label} isRequired fieldId={`${ids}-${name}`}>
      <TextInput
        id={`${ids}-${name}`}
        isRequired
        value={draft?.[name] ?? ''}
        validated={draft !== null && draft[name] !== '' && wrong[name] !== undefined ? 'error' : 'default'}
        onChange={(_, text) =>
          setDraft((d) =>
            d === null ? d : name === 'id' && !d.pathTyped ? { ...d, id: text, path: `${folder}${text}.yaml` } : { ...d, [name]: text, ...(name === 'path' ? { pathTyped: true } : {}) },
          )
        }
      />
      <FormHelperText>
        <HelperText>
          <HelperTextItem variant={draft !== null && draft[name] !== '' && wrong[name] !== undefined ? 'error' : 'default'}>
            {draft !== null && draft[name] !== '' && wrong[name] !== undefined ? wrong[name] : help}
          </HelperTextItem>
        </HelperText>
      </FormHelperText>
    </FormGroup>
  );

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
              </Button>{' '}
              {result.kind === 'read' && result.list.configured && (
                <Button
                  variant="primary"
                  size="sm"
                  icon={<PlusCircleIcon />}
                  onClick={() => {
                    setCreateFailure(null);
                    setDraft({ path: folder, pathTyped: false, id: '', from: '', to: '' });
                  }}
                >
                  New flow
                </Button>
              )}
            </FlexItem>
          </Flex>
          <Content component="p">The flow files of the working folder. Each one opens in a tab of its own as a picture of its steps.</Content>
        </StackItem>
        <StackItem>{body}</StackItem>
      </Stack>
      <Modal variant="small" isOpen={draft !== null} onClose={() => !creating && setDraft(null)} aria-labelledby={`${ids}-new`}>
        <ModalHeader title="New flow" labelId={`${ids}-new`} description="A synchronous flow with one step; the rest is added in its picture." />
        <ModalBody>
          <Form
            id={`${ids}-form`}
            onSubmit={(event) => {
              event.preventDefault();
              void create();
            }}
          >
            {field('id', 'Id', 'The flow’s id.')}
            {field('path', 'File', 'Its path in the working folder; nothing is written over an existing file.')}
            {field('from', 'Arrives at', 'The id of the inbound endpoint the flow serves.')}
            {field('to', 'Sends to', 'The id of the outbound endpoint its first step sends to.')}
            {createFailure !== null && (
              <Alert variant="danger" isInline title="The flow was not created">
                {createFailure}
              </Alert>
            )}
          </Form>
        </ModalBody>
        <ModalFooter>
          <Button variant="primary" type="submit" form={`${ids}-form`} isLoading={creating} isDisabled={creating || draft === null || Object.keys(wrong).length > 0}>
            Create
          </Button>
          <Button variant="link" isDisabled={creating} onClick={() => setDraft(null)}>
            Cancel
          </Button>
        </ModalFooter>
      </Modal>
    </PageSection>
  );
}
