import {
  Alert,
  AlertActionLink,
  Button,
  CodeBlock,
  CodeBlockCode,
  Content,
  DescriptionList,
  DescriptionListDescription,
  DescriptionListGroup,
  DescriptionListTerm,
  EmptyState,
  EmptyStateBody,
  Flex,
  FlexItem,
  Label,
  Modal,
  ModalBody,
  ModalHeader,
  PageSection,
  Spinner,
  Stack,
  StackItem,
  Title,
} from '@patternfly/react-core';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useEffect, useId, useState } from 'react';
import { PAYLOAD_SHOWN, type AdminApi, type Outcome, type Payload, type StepRecord, type Timeline } from './admin';
import { byAttempt, duration, latestState } from './progress';

export interface MessageViewProps {
  api: AdminApi;
  /** The message's id. */
  id: string;
}

type Load = { kind: 'loading' } | { kind: 'missing' } | { kind: 'failed'; message: string } | { kind: 'loaded'; timeline: Timeline };

type Restart = { kind: 'idle' } | { kind: 'running' } | { kind: 'done'; attempt: number } | { kind: 'failed'; message: string };

const reason = (failed: unknown) => (failed instanceof Error ? failed.message : String(failed));

const outcomeLabels: Record<Outcome, { text: string; status?: 'success' | 'danger' }> = {
  OK: { text: 'OK', status: 'success' },
  FILTERED: { text: 'Filtered' },
  FAILED: { text: 'Failed', status: 'danger' },
};

function OutcomeLabel({ outcome }: { outcome: Outcome }) {
  const { text, status } = outcomeLabels[outcome];
  return status === undefined ? <Label color="grey">{text}</Label> : <Label status={status}>{text}</Label>;
}

/**
 * What happened to one message: its flow, the state of its latest attempt and, for every attempt, each recorded step
 * with its outcome, start, wall and CPU time, payload size and error. The payload after a step opens in a dialog when
 * its bytes were kept. When the latest attempt failed, its error is shown with a Restart action, which runs the message
 * again from its arrival; Refresh reads the steps again.
 */
export function MessageView({ api, id }: MessageViewProps) {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [reads, setReads] = useState(0);
  const [restart, setRestart] = useState<Restart>({ kind: 'idle' });
  const [shown, setShown] = useState<StepRecord | null>(null);

  useEffect(() => {
    let current = true;
    api.timeline(id).then(
      (timeline) => {
        if (current) setLoad(timeline === null ? { kind: 'missing' } : { kind: 'loaded', timeline });
      },
      (failed: unknown) => {
        if (current) setLoad({ kind: 'failed', message: reason(failed) });
      },
    );
    return () => {
      current = false;
    };
  }, [api, id, reads]);

  const refresh = () => {
    setLoad({ kind: 'loading' });
    setRestart({ kind: 'idle' });
    setReads((n) => n + 1);
  };

  const runAgain = () => {
    setRestart({ kind: 'running' });
    api.restart(id).then(
      (restarted) => setRestart({ kind: 'done', attempt: restarted.attempt }),
      (failed: unknown) => setRestart({ kind: 'failed', message: reason(failed) }),
    );
  };

  return (
    <PageSection>
      <Stack hasGutter>
        <StackItem>
          <Flex justifyContent={{ default: 'justifyContentSpaceBetween' }} alignItems={{ default: 'alignItemsCenter' }}>
            <FlexItem>
              <Title headingLevel="h1">Message {id}</Title>
            </FlexItem>
            <FlexItem>
              <Button variant="secondary" onClick={refresh} isDisabled={load.kind === 'loading'}>
                Refresh
              </Button>
            </FlexItem>
          </Flex>
        </StackItem>
        {load.kind === 'loading' && (
          <StackItem>
            <Spinner size="lg" aria-label="Loading the message" />
          </StackItem>
        )}
        {load.kind === 'missing' && (
          <StackItem>
            <EmptyState titleText="No such message" headingLevel="h2">
              <EmptyStateBody>The node recorded no message with the id {id}.</EmptyStateBody>
            </EmptyState>
          </StackItem>
        )}
        {load.kind === 'failed' && (
          <StackItem>
            <Alert variant="danger" isInline title="The message could not be read">
              {load.message}
            </Alert>
          </StackItem>
        )}
        {load.kind === 'loaded' && <Progress timeline={load.timeline} restart={restart} onRestart={runAgain} onShow={setShown} />}
      </Stack>
      {shown !== null && <PayloadDialog api={api} id={id} step={shown} onClose={() => setShown(null)} />}
    </PageSection>
  );
}

interface ProgressProps {
  timeline: Timeline;
  restart: Restart;
  onRestart: () => void;
  onShow: (step: StepRecord) => void;
}

function Progress({ timeline, restart, onRestart, onShow }: ProgressProps) {
  const state = latestState(timeline.steps);
  const attempts = byAttempt(timeline.steps);
  return (
    <>
      <StackItem>
        <DescriptionList isHorizontal isCompact>
          <DescriptionListGroup>
            <DescriptionListTerm>Flow</DescriptionListTerm>
            <DescriptionListDescription>{timeline.flow}</DescriptionListDescription>
          </DescriptionListGroup>
          <DescriptionListGroup>
            <DescriptionListTerm>Attempts</DescriptionListTerm>
            <DescriptionListDescription>{attempts.length}</DescriptionListDescription>
          </DescriptionListGroup>
          <DescriptionListGroup>
            <DescriptionListTerm>State</DescriptionListTerm>
            <DescriptionListDescription>
              <OutcomeLabel outcome={state.outcome} />
            </DescriptionListDescription>
          </DescriptionListGroup>
        </DescriptionList>
      </StackItem>
      {state.failed !== null && (
        <StackItem>
          <Alert
            variant="danger"
            isInline
            title={`Step ${state.failed.step} (${state.failed.name}) of attempt ${state.attempt} failed`}
            actionLinks={
              <AlertActionLink onClick={onRestart} isDisabled={restart.kind === 'running' || restart.kind === 'done'}>
                Restart
              </AlertActionLink>
            }
          >
            {state.failed.error ?? 'The step recorded no error message.'}
          </Alert>
        </StackItem>
      )}
      {restart.kind === 'done' && (
        <StackItem>
          <Alert variant="success" isInline title={`Restarted as attempt ${restart.attempt}`}>
            The message runs again from its arrival. Refresh to see the steps of the new attempt.
          </Alert>
        </StackItem>
      )}
      {restart.kind === 'failed' && (
        <StackItem>
          <Alert variant="danger" isInline title="The restart was refused">
            {restart.message}
          </Alert>
        </StackItem>
      )}
      {attempts.map(({ attempt, steps }) => (
        <StackItem key={attempt}>
          <Title headingLevel="h2">Attempt {attempt}</Title>
          <Table aria-label={`Steps of attempt ${attempt}`} variant="compact">
            <Thead>
              <Tr>
                <Th>Step</Th>
                <Th>Name</Th>
                <Th>Outcome</Th>
                <Th>Started at</Th>
                <Th>Wall time</Th>
                <Th>CPU time</Th>
                <Th>Payload</Th>
                <Th>Error</Th>
              </Tr>
            </Thead>
            <Tbody>
              {steps.map((step) => (
                <Tr key={step.step}>
                  <Td dataLabel="Step">{step.step}</Td>
                  <Td dataLabel="Name">{step.name}</Td>
                  <Td dataLabel="Outcome">
                    <OutcomeLabel outcome={step.outcome} />
                  </Td>
                  <Td dataLabel="Started at">{step.startedAt}</Td>
                  <Td dataLabel="Wall time">{duration(step.wallNanos)}</Td>
                  <Td dataLabel="CPU time">{step.cpuNanos === null ? 'unknown' : duration(step.cpuNanos)}</Td>
                  <Td dataLabel="Payload">
                    {step.body.kept ? (
                      <Button variant="link" isInline onClick={() => onShow(step)} aria-label={`Show the payload after step ${step.step} of attempt ${attempt}`}>
                        {step.body.size} B
                      </Button>
                    ) : (
                      `${step.body.size} B, not kept`
                    )}
                  </Td>
                  <Td dataLabel="Error" modifier="breakWord">
                    {step.error ?? ''}
                  </Td>
                </Tr>
              ))}
            </Tbody>
          </Table>
        </StackItem>
      ))}
    </>
  );
}

type PayloadLoad = { kind: 'loading' } | { kind: 'failed'; message: string } | { kind: 'loaded'; payload: Payload };

interface PayloadDialogProps {
  api: AdminApi;
  id: string;
  step: StepRecord;
  onClose: () => void;
}

/** The payload after one step of one attempt, in a dialog: its content type and its text, the first part only when it is large. */
function PayloadDialog({ api, id, step, onClose }: PayloadDialogProps) {
  const [load, setLoad] = useState<PayloadLoad>({ kind: 'loading' });
  const titleId = useId();

  useEffect(() => {
    let current = true;
    api.payload(id, step.step, step.attempt).then(
      (payload) => {
        if (current) setLoad({ kind: 'loaded', payload });
      },
      (failed: unknown) => {
        if (current) setLoad({ kind: 'failed', message: reason(failed) });
      },
    );
    return () => {
      current = false;
    };
  }, [api, id, step]);

  return (
    <Modal isOpen variant="large" onClose={onClose} aria-labelledby={titleId}>
      <ModalHeader title={`Payload after step ${step.step} (${step.name}), attempt ${step.attempt}`} labelId={titleId} />
      <ModalBody>
        {load.kind === 'loading' && <Spinner size="lg" aria-label="Loading the payload" />}
        {load.kind === 'failed' && (
          <Alert variant="danger" isInline title="The payload could not be read">
            {load.message}
          </Alert>
        )}
        {load.kind === 'loaded' && (
          <Stack hasGutter>
            <StackItem>
              <Content component="p">
                {load.payload.contentType}, {step.body.size} B, {step.body.hash}
              </Content>
            </StackItem>
            {!load.payload.complete && (
              <StackItem>
                <Alert variant="info" isInline title={`Showing the first ${PAYLOAD_SHOWN} B of ${step.body.size} B`} />
              </StackItem>
            )}
            <StackItem>
              <CodeBlock>
                <CodeBlockCode>{load.payload.text}</CodeBlockCode>
              </CodeBlock>
            </StackItem>
          </Stack>
        )}
      </ModalBody>
    </Modal>
  );
}
