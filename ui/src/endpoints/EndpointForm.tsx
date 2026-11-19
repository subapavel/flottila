import {
  Alert,
  AlertActionCloseButton,
  AlertGroup,
  Bullseye,
  Button,
  Content,
  Flex,
  FlexItem,
  Form,
  Label,
  LabelGroup,
  List,
  ListItem,
  Modal,
  ModalBody,
  ModalFooter,
  ModalHeader,
  PageSection,
  Spinner,
  Stack,
  StackItem,
  Tab,
  TabContentBody,
  Tabs,
  TabTitleText,
  Title,
} from '@patternfly/react-core';
import { CheckCircleIcon, EditIcon, SaveIcon } from '@patternfly/react-icons';
import { useEffect, useId, useState, type ReactNode } from 'react';
import { DiscardDialog } from '../DiscardDialog';
import type { EndpointsApi, StoredEndpoint } from './api';
import { FieldControl } from './FieldControl';
import { inboundAddress, optionsIn, type NodeIngress } from './inbound';
import { RawOptions } from './RawOptions';
import { sameValue } from './same-value';
import { checkOptions, shownFields, type Options, type Problem, type SettingsDescription, type SettingsField } from './settings';

/** Asked before the endpoint's tab closes while it has unsaved changes: it calls `close` once the author agrees. */
export type CloseGuard = (close: () => void) => void;

export interface EndpointFormProps {
  api: EndpointsApi;
  /** The endpoint file's path in the working folder. */
  path: string;
  /** Given the question to ask before the tab closes while it has unsaved changes, and null when it has none. */
  guardClose?: (guard: CloseGuard | null) => void;
}

/**
 * The component's description, while it is read, once read with its HTTP prefix and where the node takes requests, or
 * why it could not be.
 */
type Described =
  | { kind: 'reading' }
  | { kind: 'read'; settings: SettingsDescription; httpPrefix: string | null; ingress: NodeIngress }
  | { kind: 'missing'; why: string };

/**
 * Under the address of an in endpoint: a preview of the URL senders call, or of the path on the node's HTTP port when
 * the node names no origin, built from the options shown with the node environment's overlay laid over them.
 */
function ArrivalNote({ url, ingress, overlaid }: { url: string; ingress: NodeIngress; overlaid: boolean }) {
  const where = ingress.httpOrigin === null ? (
    <>
      The path <strong>{url}</strong> on the node&apos;s HTTP port; the node names no origin for it (RAIL8_HTTP_ORIGIN).
    </>
  ) : (
    <>
      Senders call <strong>{url}</strong>.
    </>
  );
  return (
    <>
      <Label isCompact>Preview</Label> {where} Built from these settings
      {overlaid ? `, with the address environment ${ingress.environment} sets` : ` in environment ${ingress.environment}`}, not from what is deployed.
    </>
  );
}

type Discarding = { then: 'return to view mode' } | { then: 'close its tab'; close: () => void };

/** The options as they are saved: keys whose value is undefined are left out. */
function present(options: Options): Options {
  return Object.fromEntries(Object.entries(options).filter(([, v]) => v !== undefined));
}

/** The option a problem's JSON pointer names, or null for a rule over several. */
function optionOf(path: string): string | null {
  return path === '' ? null : path.slice(1).replaceAll('~1', '/').replaceAll('~0', '~');
}

/** A key of the file that no field describes, drawn as a field of no known type. */
function undescribed(name: string): SettingsField {
  return { name, type: 'raw', help: "No such setting in the component's description.", tab: 'general', required: false, directions: ['in', 'out'] };
}

/**
 * An endpoint's settings form, drawn from its component's settings description: the endpoint's id, scheme, direction
 * and file; the environments that override options; then the description's tabs, each with the fields it holds that
 * serve the endpoint's direction and are shown, and the problems the check finds in them. It opens read-only; Edit, when
 * the file may be edited and the description could be read, then Cancel, Check and Save. Save writes only `config`,
 * sending the version it read, and a file changed on disk since opens a dialog. Cancel, or closing the tab, with unsaved
 * changes asks first, and the page asks before it unloads. Without the description the file's options are shown as
 * JSON, read-only, with the reason.
 */
export function EndpointForm({ api, path, guardClose }: EndpointFormProps) {
  const [stored, setStored] = useState<StoredEndpoint | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [described, setDescribed] = useState<Described>({ kind: 'reading' });
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<Options>({});
  // a new generation remounts the controls, so the text a raw editor held goes with the draft it belonged to
  const [generation, setGeneration] = useState(0);
  const [invalid, setInvalid] = useState<ReadonlySet<string>>(new Set());
  const [tab, setTab] = useState('general');
  const [check, setCheck] = useState<Problem[] | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveFailure, setSaveFailure] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [saved, setSaved] = useState(0);
  const [discarding, setDiscarding] = useState<Discarding | null>(null);
  const tabsId = useId();

  const startFrom = (next: StoredEndpoint) => {
    setStored(next);
    setDraft(next.file.config);
    setInvalid(new Set());
    setGeneration((g) => g + 1);
  };

  useEffect(() => {
    let current = true;
    api.read(path).then(
      (read) => {
        if (!current) return;
        setStored(read);
        setDraft(read.file.config);
        setGeneration((g) => g + 1);
      },
      (error: unknown) => current && setFailed(error instanceof Error ? error.message : String(error)),
    );
    return () => {
      current = false;
    };
  }, [api, path]);

  const scheme = stored?.file.scheme;
  useEffect(() => {
    if (scheme === undefined) return undefined;
    let current = true;
    api.components().then(
      (components) => {
        if (!current) return;
        const found = components.items.find((c) => c.scheme === scheme);
        const ingress = { environment: components.environment, httpOrigin: components.httpOrigin };
        setDescribed(
          found
            ? { kind: 'read', settings: found.settings, httpPrefix: found.httpPrefix, ingress }
            : { kind: 'missing', why: `The node serves no component of the scheme ${scheme}.` },
        );
      },
      (error: unknown) => current && setDescribed({ kind: 'missing', why: `The node's components could not be read: ${error instanceof Error ? error.message : String(error)}` }),
    );
    return () => {
      current = false;
    };
  }, [api, scheme]);

  const unsaved = editing && stored !== null && !sameValue(present(draft), stored.file.config);

  useEffect(() => {
    if (!unsaved) return undefined;
    const ask = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', ask);
    return () => window.removeEventListener('beforeunload', ask);
  }, [unsaved]);

  useEffect(() => {
    if (!unsaved) return undefined;
    guardClose?.((close) => setDiscarding({ then: 'close its tab', close }));
    return () => guardClose?.(null);
  }, [guardClose, unsaved]);

  if (failed !== null) {
    return (
      <PageSection>
        <Alert variant="danger" isInline title={`${path} could not be read`}>
          {failed}
        </Alert>
      </PageSection>
    );
  }
  if (stored === null) {
    return (
      <PageSection>
        <Bullseye>
          <Spinner aria-label={`Reading ${path}`} />
        </Bullseye>
      </PageSection>
    );
  }

  const { file } = stored;
  const settings = described.kind === 'read' ? described.settings : null;
  const options = editing ? draft : file.config;
  const problems = settings === null ? [] : checkOptions(settings, present(options), file.direction);
  const problemOf = new Map<string, string>();
  for (const p of problems) {
    const option = optionOf(p.path);
    if (option !== null && !problemOf.has(option)) problemOf.set(option, p.message);
  }

  const change = (name: string, value: unknown) => {
    setCheck(null);
    setDraft((d) => ({ ...d, [name]: value }));
  };
  const markInvalid = (name: string, bad: boolean) =>
    setInvalid((set) => {
      if (set.has(name) === bad) return set;
      const next = new Set(set);
      if (bad) next.add(name);
      else next.delete(name);
      return next;
    });

  const leaveEditing = () => {
    setDiscarding(null);
    setSaveFailure(null);
    setCheck(null);
    setEditing(false);
    startFrom(stored);
  };
  const discard = () => {
    if (discarding?.then === 'close its tab') {
      setDiscarding(null);
      discarding.close();
    } else leaveEditing();
  };

  const save = async () => {
    setSaving(true);
    setSaveFailure(null);
    try {
      startFrom(await api.save(path, present(draft), stored.etag));
      setSaved((n) => n + 1);
    } catch (error) {
      if (error instanceof Error && 'status' in error && error.status === 412) setConflict(true);
      else setSaveFailure(error instanceof Error ? error.message : String(error));
    } finally {
      setSaving(false);
    }
  };

  const reload = async () => {
    setConflict(false);
    setEditing(false);
    setCheck(null);
    try {
      startFrom(await api.read(path));
    } catch (error) {
      setFailed(error instanceof Error ? error.message : String(error));
    }
  };

  const shown = settings === null ? new Set<string>() : shownFields(settings, present(options));
  const fieldsOn = (tabId: string): SettingsField[] => {
    if (settings === null) return [];
    const fields = settings.fields.filter((f) => f.tab === tabId && f.directions.includes(file.direction) && shown.has(f.name));
    if (tabId !== 'general') return fields;
    // on an in endpoint the node sets flow and maxBodyBytes itself: no field describes them, and they are no stray key
    const named = new Set([...settings.fields.map((f) => f.name), ...(file.direction === 'in' ? ['flow', 'maxBodyBytes'] : [])]);
    return [...fields, ...Object.keys(options).filter((k) => !named.has(k) && options[k] !== undefined).map(undescribed)];
  };

  const canEdit = file.editable && settings !== null;
  let arrival: ReactNode;
  if (described.kind === 'read' && described.httpPrefix !== null && file.direction === 'in') {
    const { ingress } = described;
    const overlay = file.environments.find((env) => env.name === ingress.environment);
    const url = inboundAddress(described.httpPrefix, optionsIn(present(options), file.environments, ingress.environment).address, ingress.httpOrigin);
    if (url !== null) arrival = <ArrivalNote url={url} ingress={ingress} overlaid={overlay !== undefined && Object.hasOwn(overlay.config, 'address')} />;
  }
  const tabs = settings?.tabs ?? [];
  const activeTab = tabs.some((t) => t.id === tab) ? tab : (tabs[0]?.id ?? 'general');

  return (
    <PageSection>
      <Stack hasGutter>
        <StackItem>
          <Flex alignItems={{ default: 'alignItemsCenter' }}>
            <FlexItem>
              <Title headingLevel="h1" size="xl">
                {file.id}
              </Title>
            </FlexItem>
            <FlexItem>
              <LabelGroup aria-label="Endpoint">
                <Label color="blue">{file.scheme}</Label>
                <Label>{file.direction}</Label>
                {!file.editable && <Label variant="outline">Read-only</Label>}
              </LabelGroup>
            </FlexItem>
            <FlexItem align={{ default: 'alignRight' }}>
              {editing ? (
                <>
                  <Button size="sm" variant="link" onClick={() => (unsaved ? setDiscarding({ then: 'return to view mode' }) : leaveEditing())}>
                    Cancel
                  </Button>{' '}
                  <Button size="sm" variant="secondary" icon={<CheckCircleIcon />} onClick={() => setCheck(problems)}>
                    Check
                  </Button>{' '}
                  <Button size="sm" variant="primary" icon={<SaveIcon />} isDisabled={!unsaved || saving || invalid.size > 0} onClick={() => void save()}>
                    Save
                  </Button>
                </>
              ) : (
                canEdit && (
                  <Button size="sm" variant="primary" icon={<EditIcon />} onClick={() => setEditing(true)}>
                    Edit
                  </Button>
                )
              )}
            </FlexItem>
          </Flex>
          <Content component="small">{file.path}</Content>
          {file.description !== null && <Content component="p">{file.description}</Content>}
        </StackItem>
        {described.kind === 'missing' && (
          <StackItem>
            <Alert variant="warning" isInline title="The settings form needs the component's description">
              <p>{described.why}</p>
              <p>The file's settings are shown as they are; Edit needs the description.</p>
            </Alert>
          </StackItem>
        )}
        {file.environments.length > 0 && (
          <StackItem>
            <Alert variant="info" isInline title="Environments override some of these settings">
              <List aria-label="Overrides">
                {file.environments.map(({ name, options: overridden }) => (
                  <ListItem key={name}>
                    <strong>{name}</strong>: {overridden.length > 0 ? overridden.join(', ') : 'nothing'}
                  </ListItem>
                ))}
              </List>
            </Alert>
          </StackItem>
        )}
        {check !== null && (
          <StackItem>
            <Alert
              variant={check.length > 0 ? 'warning' : 'success'}
              isInline
              role="status"
              title={check.length > 0 ? 'Check found issues' : 'Check passed'}
              actionClose={<AlertActionCloseButton aria-label="Dismiss check" onClose={() => setCheck(null)} />}
            >
              {check.length > 0 && (
                <List aria-label="Issues">
                  {check.map((p, i) => (
                    <ListItem key={i}>{p.path === '' ? p.message : `${optionOf(p.path)} ${p.message}`}</ListItem>
                  ))}
                </List>
              )}
              <p>The node checks the file again when the package is deployed.</p>
            </Alert>
          </StackItem>
        )}
        {saveFailure !== null && (
          <StackItem>
            <Alert variant="danger" isInline title="The file was not saved">
              {saveFailure}
            </Alert>
          </StackItem>
        )}
        <StackItem isFilled>
          {described.kind === 'reading' ? (
            <Bullseye>
              <Spinner aria-label="Reading the component's description" />
            </Bullseye>
          ) : settings === null || !settings.described ? (
            <Form onSubmit={(e) => e.preventDefault()}>
              <RawOptions
                key={generation}
                options={options}
                readOnly={!editing}
                onChange={(next) => {
                  setCheck(null);
                  setDraft(next);
                }}
                onInvalid={(bad) => markInvalid('', bad)}
              />
            </Form>
          ) : (
            <Tabs activeKey={activeTab} onSelect={(_, key) => setTab(String(key))} aria-label="Settings tabs" id={tabsId}>
              {tabs.map((t) => {
                const fields = fieldsOn(t.id);
                return (
                  <Tab key={t.id} eventKey={t.id} title={<TabTitleText>{t.label}</TabTitleText>}>
                    <TabContentBody hasPadding>
                      {fields.length === 0 ? (
                        <Content component="p">No settings on this tab for an {file.direction} endpoint.</Content>
                      ) : (
                        <Form onSubmit={(e) => e.preventDefault()}>
                          {fields.map((field) => (
                            <FieldControl
                              key={`${generation}-${field.name}`}
                              field={field}
                              value={options[field.name]}
                              readOnly={!editing}
                              onChange={(value) => change(field.name, value)}
                              onInvalid={(bad) => markInvalid(field.name, bad)}
                              problem={problemOf.get(field.name)}
                              {...(field.name === 'address' && arrival !== undefined ? { note: arrival } : {})}
                            />
                          ))}
                        </Form>
                      )}
                    </TabContentBody>
                  </Tab>
                );
              })}
            </Tabs>
          )}
        </StackItem>
      </Stack>
      <DiscardDialog isOpen={discarding !== null} name={file.id} then={discarding?.then ?? ''} onDiscard={discard} onKeepEditing={() => setDiscarding(null)} />
      <Modal variant="small" isOpen={conflict} onClose={() => setConflict(false)} aria-labelledby={`${tabsId}-conflict`}>
        <ModalHeader title="The file changed on disk" labelId={`${tabsId}-conflict`} />
        <ModalBody>
          <p>
            <strong>{file.path}</strong> changed since it was opened, and nothing was saved. Reload shows the file as it is now and discards your changes.
          </p>
        </ModalBody>
        <ModalFooter>
          <Button variant="primary" onClick={() => void reload()}>
            Reload
          </Button>
          <Button variant="link" onClick={() => setConflict(false)}>
            Keep editing
          </Button>
        </ModalFooter>
      </Modal>
      <AlertGroup isToast isLiveRegion aria-label="Save notifications">
        {saved > 0 && (
          <Alert
            key={saved}
            variant="success"
            title={`Endpoint ${file.id} saved`}
            timeout={8000}
            actionClose={<AlertActionCloseButton aria-label="Dismiss save notification" onClose={() => setSaved(0)} />}
          />
        )}
      </AlertGroup>
    </PageSection>
  );
}
