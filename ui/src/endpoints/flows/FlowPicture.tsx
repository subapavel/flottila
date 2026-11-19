import {
  Alert,
  Bullseye,
  Button,
  CodeBlock,
  CodeBlockCode,
  Drawer,
  DrawerActions,
  DrawerCloseButton,
  DrawerContent,
  DrawerContentBody,
  DrawerHead,
  DrawerPanelBody,
  DrawerPanelContent,
  ExpandableSection,
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
import { ReactFlowProvider } from '@xyflow/react';
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { FlowsApi, StoredFlow } from './api';
import { FlowCanvas, selectionOf } from './FlowCanvas';
import { readFlow } from './flowYaml';
import { FlowPanel, placeOf } from './FlowPanel';
import type { FlowNode, ServedSteps } from './types';
import { view } from './view';

export interface FlowPictureProps {
  api: FlowsApi;
  /** The flow file's path in the working folder. */
  path: string;
  /** Shows the endpoint file at this path in the component's form, read-only. */
  endpointForm: (path: string) => ReactNode;
  openEndpoint: (path: string) => void;
  openFlow: (path: string) => void;
}

type Result = { kind: 'reading' } | { kind: 'failed'; message: string } | { kind: 'read'; stored: StoredFlow; steps: ServedSteps };

/**
 * A flow file as a picture: its steps on a canvas, and beside it a drawer with the settings of the node chosen, or the
 * flow's own when none is. It opens read-only. A file that cannot be drawn shows its text with its problems.
 */
export function FlowPicture({ api, path, endpointForm, openEndpoint, openFlow }: FlowPictureProps) {
  const [result, setResult] = useState<Result>({ kind: 'reading' });
  const [asked, setAsked] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const refresh = useCallback(() => setAsked((n) => n + 1), []);

  useEffect(() => {
    let current = true;
    Promise.all([api.read(path), api.steps()]).then(
      ([stored, steps]) => current && setResult({ kind: 'read', stored, steps }),
      (error: unknown) => current && setResult({ kind: 'failed', message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      current = false;
    };
  }, [api, path, asked]);

  const drawn = useMemo(() => {
    if (result.kind !== 'read') return null;
    const { file } = result.stored;
    const read = readFlow(file.text);
    return { view: view(file.text, { ...file.context, steps: result.steps }), value: read.kind === 'flow' ? read.value : {} };
  }, [result]);

  if (result.kind === 'reading') {
    return (
      <PageSection>
        <Bullseye>
          <Spinner aria-label="Reading the flow file" />
        </Bullseye>
      </PageSection>
    );
  }
  if (result.kind === 'failed' || drawn === null) {
    return (
      <PageSection>
        <Alert variant="danger" isInline title="The flow file could not be read">
          {result.kind === 'failed' ? result.message : ''}
        </Alert>
      </PageSection>
    );
  }

  const { view: picture, value } = drawn;
  const errors = picture.problems.filter((p) => p.warning !== true).length;
  const warnings = picture.problems.length - errors;
  const node: FlowNode | null = selected === null ? null : (picture.nodes.find((n) => n.id === selected) ?? null);
  const calls = node === null ? undefined : picture.nodes.find((n) => n.id === `${node.id}#calls`);

  const header = (
    <Stack hasGutter>
      <StackItem>
        <Flex alignItems={{ default: 'alignItemsCenter' }}>
          <FlexItem>
            <Title headingLevel="h1">{typeof value.id === 'string' ? value.id : path}</Title>
          </FlexItem>
          <FlexItem>
            <Label variant="outline">Read-only</Label>
          </FlexItem>
          {errors > 0 && (
            <FlexItem>
              <Label status="danger">{errors === 1 ? '1 problem' : `${errors} problems`}</Label>
            </FlexItem>
          )}
          {warnings > 0 && (
            <FlexItem>
              <Label status="warning">{warnings === 1 ? '1 warning' : `${warnings} warnings`}</Label>
            </FlexItem>
          )}
          <FlexItem align={{ default: 'alignRight' }}>
            <Button variant="secondary" size="sm" icon={<SyncAltIcon />} onClick={refresh}>
              Refresh
            </Button>
          </FlexItem>
        </Flex>
      </StackItem>
      <StackItem>
        <code>{path}</code>
      </StackItem>
      {picture.problems.length > 0 && (
        <StackItem>
          <ExpandableSection toggleTextCollapsed="Show the problems" toggleTextExpanded="Hide the problems" isExpanded={!picture.readable || undefined}>
            <HelperText>
              {picture.problems.map((p, i) => (
                <HelperTextItem key={i} variant={p.warning === true ? 'warning' : 'error'}>
                  {placeOf(p.path)}: {p.message}
                </HelperTextItem>
              ))}
            </HelperText>
          </ExpandableSection>
        </StackItem>
      )}
    </Stack>
  );

  if (!picture.readable) {
    return (
      <PageSection>
        <Stack hasGutter>
          <StackItem>{header}</StackItem>
          <StackItem>
            <Alert variant="warning" isInline title="The flow cannot be drawn">
              The file is shown as it is, with what keeps it from being drawn.
            </Alert>
          </StackItem>
          <StackItem>
            <CodeBlock>
              <CodeBlockCode>{result.stored.file.text}</CodeBlockCode>
            </CodeBlock>
          </StackItem>
        </Stack>
      </PageSection>
    );
  }

  const panel = (
    <DrawerPanelContent isResizable defaultSize="28rem" minSize="20rem">
      <DrawerHead>
        <span />
        {node !== null && (
          <DrawerActions>
            <DrawerCloseButton aria-label="Show the flow's settings" onClick={() => setSelected(null)} />
          </DrawerActions>
        )}
      </DrawerHead>
      <DrawerPanelBody>
        <FlowPanel
          flow={value}
          node={node}
          {...(calls === undefined ? {} : { calls })}
          problems={picture.problems}
          endpointForm={endpointForm}
          openEndpoint={openEndpoint}
          openFlow={openFlow}
        />
      </DrawerPanelBody>
    </DrawerPanelContent>
  );

  return (
    <PageSection padding={{ default: 'noPadding' }}>
      <Stack>
        <StackItem>
          <PageSection>{header}</PageSection>
        </StackItem>
        <StackItem isFilled>
          <Drawer isExpanded isInline position="end">
            <DrawerContent panelContent={panel}>
              <DrawerContentBody>
                <div style={{ height: '70vh', minHeight: '24rem' }} data-testid="flow-canvas">
                  <ReactFlowProvider>
                    <FlowCanvas view={picture} selected={selected} onSelect={(n) => setSelected(selectionOf(n))} />
                  </ReactFlowProvider>
                </div>
              </DrawerContentBody>
            </DrawerContent>
          </Drawer>
        </StackItem>
      </Stack>
    </PageSection>
  );
}
