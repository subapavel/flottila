import {
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  Content,
  DescriptionList,
  DescriptionListDescription,
  DescriptionListGroup,
  DescriptionListTerm,
  Form,
  FormGroup,
  FormSelect,
  FormSelectOption,
  Grid,
  GridItem,
  Label,
  Stack,
  StackItem,
  TextArea,
  TextInput,
  Title,
  Toolbar,
  ToolbarContent,
  ToolbarGroup,
  ToolbarItem,
} from '@patternfly/react-core';
import { CodeIcon, DownloadIcon, UploadIcon } from '@patternfly/react-icons';
import { useQuery } from '@tanstack/react-query';
import { useId, useRef, useState, type ChangeEvent } from 'react';
import { useStore } from 'zustand';
import type { ApiClient } from '../../api/client';
import type { BrowserStateStore } from '../browser-state/browser-state';
import {
  AREAS,
  ATTRIBUTES,
  builtIn,
  declaration,
  EXECUTION_KINDS,
  metadata,
  moveRow,
  newFunction,
  unusedName,
  type AreaName,
  type ExecutionKind,
  type JavaArea,
  type Udf,
} from '../mock/function-model';
import { FUNCTIONS_ISSUE, functionsPreview } from '../mock/functions-preview';
import { PreviewBadge } from '../mock/PreviewBadge';
import { downloadFile } from '../shared/download';
import { LogDialog, ProcessingLog } from '../shared/ProcessingLog';
import { Tool } from '../shared/Tool';
import type { WorkingCopyStore } from '../store/working-copy';
import { exportSource, importSource } from './function-source';
import { Dependencies, NameEditor, RowTools, Variables } from './FunctionTables';

const JDK = 'JDK 25 (Default)';
const MONO = { fontFamily: 'var(--pf-t--global--font--family--mono)' } as const;

export interface FunctionsTabProps {
  /** The mapping's id: its name names the class, its mapping package the Java package. */
  mappingId: string;
  /** The open mapping's store, read for edit mode and the messages only: the functions never enter the model. */
  store: WorkingCopyStore;
  /** Keeps the saved functions per mapping. */
  browserState: BrowserStateStore;
  /** Read for the standard functions' names, which a function may not take. */
  api: ApiClient;
}

/**
 * The Functions tab, a preview, as user-defined functions need the platform's message data model, which does not exist
 * yet: the class and package of the mapping's functions, Export and Import Source Text, the list of Java areas and
 * user-defined functions with their editor, and the import instructions and archives. The functions live in the browser
 * only (`functionsPreview`), never in the mapping file; Save keeps an edit and Discard drops it. They are edited in
 * edit mode once the mapping has both messages; otherwise they are read-only, and only Export Source Text works.
 */
export function FunctionsTab({ mappingId, store, browserState, api }: FunctionsTabProps) {
  const functions = functionsPreview(store, browserState, mappingId);
  const state = useStore(functions, (s) => s.value);
  const edit = useStore(functions, (s) => s.edit);
  const editing = useStore(store, (s) => s.editing);
  const hasMessages = useStore(store, (s) => s.workingCopy.source !== undefined && s.workingCopy.target !== undefined);
  const catalogue = useQuery({ queryKey: ['catalogue'], queryFn: () => api.getCatalogue() });
  const [selected, select] = useState<string>(ATTRIBUTES);
  const [log, setLog] = useState('');
  const [showLog, setShowLog] = useState(false);
  const file = useRef<HTMLInputElement>(null);
  const ids = useId();

  const list = state.functions;
  const index = list.findIndex((fn) => fn.id === selected);
  const isCustom = index >= 0;
  const custom: Udf | undefined = list[index];
  const active: Udf | (JavaArea & { name: AreaName; id?: undefined }) = custom ?? builtIn(state, selected as AreaName);
  const attributes = active.name === ATTRIBUTES;
  const lifecycle = !isCustom && !attributes;
  const ready = editing && hasMessages;
  const meta = metadata(mappingId);

  function change(changes: Partial<Udf>) {
    if (isCustom) edit((s) => ({ ...s, functions: s.functions.map((fn) => (fn.id === active.id ? { ...fn, ...changes } : fn)) }));
    else {
      const name = active.name as AreaName;
      edit((s) => {
        const { body, description, variables } = { ...builtIn(s, name), ...changes };
        return { ...s, javaAreas: { ...s.javaAreas, [name]: { body, description, variables } } };
      });
    }
  }
  function add() {
    const fn = newFunction(unusedName('calculate', list.map((f) => f.name)));
    const at = index + 1;
    edit((s) => ({ ...s, functions: [...s.functions.slice(0, at), fn, ...s.functions.slice(at)] }));
    select(fn.id);
  }
  function remove() {
    edit((s) => ({ ...s, functions: s.functions.filter((fn) => fn.id !== active.id) }));
    select(list[index - 1]?.id ?? list[index + 1]?.id ?? ATTRIBUTES);
  }
  function download() {
    const filename = `${meta.className}.java`;
    downloadFile(filename, new Blob([exportSource(mappingId, state)], { type: 'text/plain;charset=utf-8' }));
    setLog(`Exported ${filename}`);
    setShowLog(true);
  }
  async function readFile(event: ChangeEvent<HTMLInputElement>) {
    const chosen = event.target.files![0]!;
    event.target.value = '';
    const text = await chosen.text();
    try {
      edit((s) => importSource(mappingId, s, text));
      setLog(`Imported source text from ${chosen.name}`);
    } catch (error) {
      setLog(`Import failed. ${(error as Error).message}`);
    }
    setShowLog(true);
  }

  const taken = [...AREAS, ...Object.keys(catalogue.data ?? {})];
  const field = (name: string) => `${ids}-${name}`;
  return (
    <Stack hasGutter>
      <StackItem>
        <Card component="section" aria-label="Function class">
          <CardBody>
            <Toolbar>
              <ToolbarContent>
                <ToolbarItem>
                  <DescriptionList isHorizontal isCompact columnModifier={{ default: '2Col' }}>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Class Name</DescriptionListTerm>
                      <DescriptionListDescription>
                        <code>{meta.className}</code>
                      </DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                      <DescriptionListTerm>Package Name</DescriptionListTerm>
                      <DescriptionListDescription>
                        <code>{meta.packageName || '(default package)'}</code>
                      </DescriptionListDescription>
                    </DescriptionListGroup>
                  </DescriptionList>
                </ToolbarItem>
                <ToolbarItem>
                  <PreviewBadge feature="User-defined functions" issue={FUNCTIONS_ISSUE} />
                </ToolbarItem>
                <ToolbarItem variant="separator" />
                <ToolbarGroup role="toolbar" aria-label="Java source actions">
                  <ToolbarItem>
                    <Tool label="Export Source Text" icon={DownloadIcon} onClick={download} />
                  </ToolbarItem>
                  <ToolbarItem>
                    <Tool label="Import Source Text" icon={UploadIcon} disabled={!ready} onClick={() => file.current!.click()} />
                  </ToolbarItem>
                </ToolbarGroup>
                <ToolbarItem variant="separator" />
                <ToolbarGroup alignItems="center">
                  <ToolbarItem>
                    <Content component="small" aria-hidden="true">
                      JDK Compliance
                    </Content>
                  </ToolbarItem>
                  <ToolbarItem>
                    <FormSelect aria-label="JDK Compliance" value={JDK} isDisabled={!ready} onChange={() => undefined}>
                      <FormSelectOption value={JDK} label={JDK} />
                    </FormSelect>
                  </ToolbarItem>
                </ToolbarGroup>
              </ToolbarContent>
            </Toolbar>
            <input type="file" disabled={!ready} hidden accept=".java,text/plain" aria-label="Java source file" ref={file} onChange={(e) => void readFile(e)} />
          </CardBody>
        </Card>
      </StackItem>
      <StackItem>
        <Grid hasGutter>
          <GridItem lg={4}>
            <Stack hasGutter>
              <StackItem>
                <Card component="section" aria-label="Functions and Java Areas">
                  <CardTitle>
                    <Title headingLevel="h2" size="lg">
                      Functions and Java Areas
                    </Title>
                  </CardTitle>
                  <CardBody>
                    <RowTools
                      label="Function actions"
                      disabled={!ready}
                      onAdd={add}
                      canRemove={isCustom}
                      canUp={index > 0}
                      canDown={isCustom && index < list.length - 1}
                      onRemove={remove}
                      onMove={(delta) => edit((s) => ({ ...s, functions: [...moveRow(s.functions, index, delta)] }))}
                    />
                    <Stack role="list" aria-label="Functions">
                      {AREAS.map((name) => (
                        <StackItem key={name} role="listitem">
                          <Entry label={name} pressed={active.name === name} onClick={() => select(name)} />
                        </StackItem>
                      ))}
                      {list.map((fn) => (
                        <StackItem key={fn.id} role="listitem">
                          <Entry label={fn.name} name={`Select function ${fn.name}`} pressed={active.id === fn.id} onClick={() => select(fn.id)} />
                          {ready && fn.id === active.id && (
                            <FormGroup label="Method name" fieldId={field('method')}>
                              <NameEditor
                                id={field('method')}
                                label="Method name"
                                value={fn.name}
                                taken={[...taken, ...list.filter((f) => f.id !== fn.id).map((f) => f.name)]}
                                onCommit={(name) => change({ name })}
                              />
                            </FormGroup>
                          )}
                        </StackItem>
                      ))}
                    </Stack>
                  </CardBody>
                </Card>
              </StackItem>
              <StackItem>
                <Dependencies state={state} edit={edit} disabled={!ready} />
              </StackItem>
            </Stack>
          </GridItem>
          <GridItem lg={8}>
            <Card component="section" aria-label="Function editor" isFullHeight>
              <CardHeader
                actions={{
                  actions: (
                    <Label isCompact variant="outline">
                      Java
                    </Label>
                  ),
                  hasNoOffset: true,
                }}
              >
                <CardTitle>
                  <Title headingLevel="h2" size="lg">
                    <CodeIcon /> {active.name}
                  </Title>
                </CardTitle>
              </CardHeader>
              <CardBody>
                <Stack hasGutter>
                  {(isCustom || active.name === 'init') && (
                    <StackItem>
                      <Form isHorizontal key={`properties-${selected}`}>
                        {custom && (
                          <FormGroup label="Title" fieldId={field('title')}>
                            <TextInput id={field('title')} value={custom.title} isDisabled={!ready} onChange={(_, title) => change({ title })} />
                          </FormGroup>
                        )}
                        <FormGroup label="Description" fieldId={field('description')}>
                          <TextInput id={field('description')} value={active.description} isDisabled={!ready} onChange={(_, description) => change({ description })} />
                        </FormGroup>
                        {custom && (
                          <>
                            <FormGroup label="Execution Kind" fieldId={field('execution')}>
                              <FormSelect id={field('execution')} value={custom.execution} isDisabled={!ready} onChange={(_, execution) => change({ execution: execution as ExecutionKind })}>
                                {EXECUTION_KINDS.map((m) => (
                                  <FormSelectOption key={m} value={m} label={m} />
                                ))}
                              </FormSelect>
                            </FormGroup>
                            <FormGroup label="Category" fieldId={field('category')}>
                              <TextInput id={field('category')} value={custom.category} isDisabled={!ready} onChange={(_, category) => change({ category })} />
                            </FormGroup>
                          </>
                        )}
                      </Form>
                    </StackItem>
                  )}
                  {(isCustom || active.name === 'init') && (
                    <StackItem>
                      <Variables disabled={!ready} key={`variables-${selected}`} fn={active} lifecycle={lifecycle} onChange={(variables) => change({ variables })} />
                    </StackItem>
                  )}
                  <StackItem>
                    {attributes ? (
                      <Content component="p">Class attributes and shared methods are available to all functions in this mapping.</Content>
                    ) : (
                      <>
                        {lifecycle && <Content component="p">{active.name === 'init' ? 'Called before mapping.' : 'Called after mapping.'}</Content>}
                        <Content component="pre">{declaration(active)}</Content>
                      </>
                    )}
                  </StackItem>
                  <StackItem>
                    <TextArea
                      aria-label={`Source code for ${active.name}`}
                      readOnlyVariant={ready ? undefined : 'default'}
                      spellCheck={false}
                      rows={16}
                      resizeOrientation="vertical"
                      value={active.body}
                      onChange={(_, body) => change({ body })}
                      style={MONO}
                    />
                  </StackItem>
                  {!attributes && (
                    <StackItem>
                      <Content component="pre">{'}'}</Content>
                    </StackItem>
                  )}
                </Stack>
              </CardBody>
            </Card>
          </GridItem>
        </Grid>
      </StackItem>
      {log !== '' && (
        <StackItem>
          <ProcessingLog log={log} />
        </StackItem>
      )}
      {showLog && <LogDialog log={log} onClose={() => setShowLog(false)} />}
    </Stack>
  );
}

/** An entry of the list of Java areas and functions: a button that selects it, pressed while it is the one shown. */
function Entry({ label, name, pressed, onClick }: { label: string; name?: string; pressed: boolean; onClick: () => void }) {
  return (
    <Button variant="plain" isClicked={pressed} aria-pressed={pressed} aria-label={name} icon={<CodeIcon />} onClick={onClick}>
      {label}
    </Button>
  );
}
