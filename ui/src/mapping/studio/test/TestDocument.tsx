import {
  Alert,
  Button,
  Card,
  CardBody,
  CardFooter,
  CardHeader,
  CardTitle,
  ClipboardCopy,
  CodeBlock,
  CodeBlockCode,
  Content,
  EmptyState,
  EmptyStateBody,
  Flex,
  Icon,
  Label,
  Stack,
  StackItem,
  TextArea,
  TextInput,
  Title,
  ToggleGroup,
  ToggleGroupItem,
  Toolbar,
  ToolbarContent,
  ToolbarGroup,
  ToolbarItem,
} from '@patternfly/react-core';
import { AlignLeftIcon, AngleDoubleDownIcon, AngleDoubleUpIcon, AngleDownIcon, AngleRightIcon, CodeIcon, DownloadIcon, RouteIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useMemo, useState } from 'react';
import { PreviewBadge } from '../mock/PreviewBadge';
import { PARAMETERS_ISSUE } from '../mock/signature-mock';
import { zipInstance } from '../mock/test-instances';
import { CopyTextButton } from '../shared/CopyTextButton';
import { documentRows, prettyText, type DocumentFormat } from '../shared/documents';
import { downloadFile } from '../shared/download';
import { FieldTypeIcon } from '../shared/FieldTypeIcon';
import { Tool } from '../shared/Tool';

/** A pane's view of its document. */
export const DOCUMENT_MODES = ['Tree', 'Text'] as const;
export type DocumentMode = (typeof DOCUMENT_MODES)[number];

const SECTIONS = ['Document', 'Parameters'] as const;
type Section = (typeof SECTIONS)[number];

/** A row of a pane's parameter tables (a preview): its id among the values, its label and its default. */
export interface ParameterRow {
  id: string;
  label: string;
  initial: string;
}

export interface TestDocumentProps {
  side: 'Input' | 'Output';
  format: DocumentFormat;
  /** The document; `null` for a result before a run, or after a failed one. */
  text: string | null;
  /** Whether the last run failed: the result says so instead of asking for a run. */
  failed?: boolean;
  mode: DocumentMode;
  onMode: (mode: DocumentMode) => void;
  /** Changes the source document; the result has none. */
  edit?: (text: string) => void;
  readOnly: boolean;
  /** Whether a stale trace stands behind the result: it shows a mark. */
  stale?: boolean;
  /** The parameter tables of the Parameters section, each with its rows (a preview). */
  parameters: { title: string; rows: ParameterRow[] }[];
  values: Readonly<Record<string, string>>;
  onParameter?: (id: string, value: string) => void;
  /** Whether the parameter values are read-only; as the document, unless told. */
  valuesReadOnly?: boolean;
}

/** A scrolling area that takes the height left in its pane. */
const SCROLL_AREA = { flex: '1 1 0', minHeight: 0, overflow: 'auto' } as const;
const MONO = { fontFamily: 'var(--pf-t--global--font--family--mono)' } as const;

/**
 * A pane of the Test tab, a card: the source document, which the author types, or the result of the last run, each
 * as a tree of fields and values or as text, and a Parameters section, a preview. Pretty Print indents the source, and
 * the result for display only; Save Document Source downloads the document as it shows, as `instance.<format>` in
 * `schema.zip`.
 */
export function TestDocument({
  side,
  format,
  text,
  failed = false,
  mode,
  onMode,
  edit,
  readOnly,
  stale = false,
  parameters,
  values,
  onParameter,
  valuesReadOnly = readOnly,
}: TestDocumentProps) {
  const input = side === 'Input';
  const title = input ? 'Source' : 'Result';
  const [section, setSection] = useState<Section>('Document');
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
  const [selection, select] = useState<string | null>(null);
  const [fullPath, setFullPath] = useState(false);
  const [formatted, setFormatted] = useState<{ original: string; text: string } | null>(null);
  const [error, setError] = useState('');
  const available = text !== null;
  const displayed = !input && formatted?.original === text ? formatted.text : (text ?? '');
  const treeMode = mode === 'Tree';
  const rows = useMemo(() => {
    try {
      return available ? documentRows(displayed, format, input ? 'Source' : 'Result') : [];
    } catch {
      return null;
    }
  }, [available, displayed, format, input]);
  // a closed branch hides the rows below it
  const visible = useMemo(() => {
    const shown = [];
    let hiddenBelow = Infinity;
    for (const row of rows ?? []) {
      if (row.level > hiddenBelow) continue;
      hiddenBelow = closed.has(row.id) ? row.level : Infinity;
      shown.push(row);
    }
    return shown;
  }, [rows, closed]);
  const selectedPath = rows?.find((r) => r.id === selection)?.path ?? rows?.[0]?.path ?? '';

  const switchMode = (next: DocumentMode) => {
    setError('');
    onMode(next);
  };
  const toggleExpand = () => setClosed(closed.size > 0 ? new Set() : new Set((rows ?? []).filter((r) => r.branch).map((r) => r.id)));
  const toggleRow = (id: string) =>
    setClosed((old) => {
      const next = new Set(old);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  const prettyPrint = () => {
    try {
      const value = prettyText(displayed, format);
      if (edit) edit(value);
      else setFormatted({ original: text!, text: value });
      setError('');
    } catch (e) {
      setError(`Cannot process this ${format} document. ${(e as Error).message}`);
    }
  };
  const download = () => downloadFile('schema.zip', new Blob([zipInstance(displayed, format)], { type: 'application/zip' }));
  const empty = (
    <EmptyState variant="xs" icon={CodeIcon}>
      <EmptyStateBody>{failed ? 'The simulation failed.' : `Run the simulation to display the ${format} result.`}</EmptyStateBody>
    </EmptyState>
  );

  let document;
  if (treeMode && !available) document = <CardBody>{empty}</CardBody>;
  else if (treeMode && rows === null)
    document = (
      <CardBody>
        <Content component="p" role="status">
          The {format} cannot be displayed as a tree. Open Text to correct it.
        </Content>
      </CardBody>
    );
  else if (treeMode)
    document = (
      <div style={SCROLL_AREA}>
        <Table variant="compact" gridBreakPoint="" isStickyHeader role="table" aria-label={`${input ? 'Input' : 'Result'} structure and values`}>
          <Thead>
            <Tr>
              <Th>Structure</Th>
              <Th>Value</Th>
            </Tr>
          </Thead>
          <Tbody>
            {visible.map((row) => (
              <Tr key={row.id} isRowSelected={selection === row.id}>
                <Td dataLabel="Structure">
                  <Flex
                    spaceItems={{ default: 'spaceItemsSm' }}
                    alignItems={{ default: 'alignItemsCenter' }}
                    flexWrap={{ default: 'nowrap' }}
                    style={{ paddingInlineStart: `calc(${row.level} * var(--pf-t--global--spacer--md))` }}
                  >
                    {row.branch ? (
                      <Button
                        variant="plain"
                        size="sm"
                        hasNoPadding
                        aria-expanded={!closed.has(row.id)}
                        aria-label={`${closed.has(row.id) ? 'Expand' : 'Collapse'} ${row.name}`}
                        icon={closed.has(row.id) ? <AngleRightIcon /> : <AngleDownIcon />}
                        onClick={() => toggleRow(row.id)}
                      />
                    ) : (
                      <Icon size="md" aria-hidden="true" />
                    )}
                    <Button
                      variant="link"
                      isInline
                      icon={<FieldTypeIcon type={format === 'XML' && row.branch ? 'structure' : row.kind} />}
                      aria-pressed={selection === row.id}
                      onClick={() => select(row.id)}
                    >
                      {row.name}
                    </Button>
                  </Flex>
                </Td>
                <Td dataLabel="Value">{row.value}</Td>
              </Tr>
            ))}
          </Tbody>
        </Table>
      </div>
    );
  else if (input)
    document = (
      // a grid stretches the text area to the height left in the pane, the copy button beside it
      <CardBody style={{ display: 'grid', gridTemplateColumns: '1fr auto', flex: '1 1 0', minHeight: 0 }}>
        <TextArea
          aria-label={`Input ${format}`}
          spellCheck={false}
          resizeOrientation="none"
          readOnlyVariant={readOnly ? 'default' : undefined}
          value={displayed}
          onChange={(_event, value) => edit!(value)}
          style={{ ...MONO, height: '100%' }}
        />
        <div style={{ alignSelf: 'start' }}>
          <CopyTextButton label={`input ${format}`} text={displayed} />
        </div>
      </CardBody>
    );
  else
    document = available ? (
      <CardBody style={SCROLL_AREA}>
        <CodeBlock actions={<CopyTextButton label={`result ${format}`} text={displayed} />}>
          <CodeBlockCode>{displayed}</CodeBlockCode>
        </CodeBlock>
      </CardBody>
    ) : (
      <CardBody>{empty}</CardBody>
    );

  return (
    <Card isFullHeight isCompact component="section" id={input ? 'test-input-pane' : 'test-result-pane'} aria-label={title} style={{ minHeight: 0 }}>
      <CardHeader
        actions={{
          actions: (
            <>
              {stale && (
                <Label isCompact color="orange" title="The working copy changed since this run.">
                  stale
                </Label>
              )}
              <Label isCompact>{format}</Label>
            </>
          ),
          hasNoOffset: true,
        }}
      >
        <CardTitle>
          <Title headingLevel="h2" size="md">
            <CodeIcon /> {title}
          </Title>
        </CardTitle>
      </CardHeader>
      {section === 'Document' ? (
        <>
          <CardBody isFilled={false}>
            <Toolbar hasNoPadding>
              <ToolbarContent>
                <ToolbarItem>
                  <ToggleGroup isCompact aria-label={`${side} document view`}>
                    {DOCUMENT_MODES.map((name) => (
                      <ToggleGroupItem key={name} text={name} isSelected={mode === name} onChange={() => switchMode(name)} />
                    ))}
                  </ToggleGroup>
                </ToolbarItem>
                <ToolbarGroup variant="action-group-plain" role="toolbar" aria-label={`${side} document tools`}>
                  {treeMode ? (
                    <>
                      <Tool
                        label={closed.size > 0 ? 'Expand Tree' : 'Collapse Tree'}
                        icon={closed.size > 0 ? AngleDoubleDownIcon : AngleDoubleUpIcon}
                        disabled={!rows?.length}
                        onClick={toggleExpand}
                      />
                      <Tool label="Show Full Path" icon={RouteIcon} pressed={fullPath} onClick={() => setFullPath((v) => !v)} />
                    </>
                  ) : (
                    <Tool label="Pretty Print" icon={AlignLeftIcon} disabled={!available || readOnly} onClick={prettyPrint} />
                  )}
                  <Tool label="Save Document Source" icon={DownloadIcon} disabled={!available} onClick={download} />
                </ToolbarGroup>
              </ToolbarContent>
            </Toolbar>
            {error && <Alert variant="danger" isInline isPlain role="alert" title={error} />}
          </CardBody>
          {document}
          {treeMode && fullPath && (
            <CardFooter>
              <ClipboardCopy isReadOnly isCode variant="inline-compact" hoverTip={`Copy ${side.toLowerCase()} path`} clickTip="Copied">
                {selectedPath}
              </ClipboardCopy>
            </CardFooter>
          )}
        </>
      ) : (
        <CardBody style={SCROLL_AREA}>
          <Stack hasGutter>
            <StackItem>
              <Content component="p">
                Parameters of the test run <PreviewBadge feature="Mapping parameters and message headers" issue={PARAMETERS_ISSUE} />
              </Content>
            </StackItem>
            {parameters.map(({ title: group, rows: entries }) => (
              <StackItem key={group}>
                <Title headingLevel="h3" size="md">
                  {group}
                </Title>
                <Table variant="compact" gridBreakPoint="" role="table" aria-label={group}>
                  <Thead>
                    <Tr>
                      <Th>Name</Th>
                      <Th>Value</Th>
                    </Tr>
                  </Thead>
                  <Tbody>
                    {entries.map(({ id, label, initial }) => (
                      <Tr key={id}>
                        <Td dataLabel="Name">{label}</Td>
                        <Td dataLabel="Value">
                          {onParameter && (
                            <TextInput
                              aria-label={label}
                              readOnlyVariant={valuesReadOnly ? 'default' : undefined}
                              value={values[id] ?? initial}
                              onChange={(_event, value) => onParameter(id, value)}
                            />
                          )}
                        </Td>
                      </Tr>
                    ))}
                  </Tbody>
                </Table>
                {entries.length === 0 && <Content component="p">No {input ? 'import' : 'export'} parameters defined.</Content>}
              </StackItem>
            ))}
          </Stack>
        </CardBody>
      )}
      <CardFooter>
        <ToggleGroup isCompact aria-label={`${side} panel`}>
          {SECTIONS.map((name) => (
            <ToggleGroupItem key={name} text={name} isSelected={section === name} onChange={() => setSection(name)} />
          ))}
        </ToggleGroup>
      </CardFooter>
    </Card>
  );
}
