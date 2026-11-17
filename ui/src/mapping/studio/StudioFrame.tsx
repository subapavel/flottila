import {
  Alert,
  AlertActionCloseButton,
  AlertGroup,
  Button,
  Flex,
  FlexItem,
  List,
  ListItem,
  PageSection,
  Stack,
  StackItem,
  Tab,
  TabContent,
  Tabs,
  TabTitleIcon,
  TabTitleText,
  Title,
} from '@patternfly/react-core';
import { CheckCircleIcon, EditIcon, ExchangeAltIcon, ListAltIcon, SaveIcon } from '@patternfly/react-icons';
import { useMutation } from '@tanstack/react-query';
import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useStore } from 'zustand';
import { describeError, type ApiClient } from '../api/client';
import { describeRefusal, type Refusal } from '../model/command';
import { AboutStudio } from './AboutStudio';
import type { BrowserStateStore } from './browser-state/browser-state';
import { DiscardDialog } from './DiscardDialog';
import { MappingOverview } from './overview/MappingOverview';
import { SaveConflict } from './SaveConflict';
import { CopyTextButton } from './shared/CopyTextButton';
import { Signature } from './signature/Signature';
import { refreshMutation } from './shared/structure-query';
import { selectUnsaved, type StudioTab, type WorkingCopyState, type WorkingCopyStore } from './store/working-copy';

/** The tabs the studio shows, in their order, each with its title and icon. */
const VIEWS = [
  ['overview', 'Overview', ListAltIcon],
  ['signature', 'Signature', ExchangeAltIcon],
] as const satisfies readonly (readonly [StudioTab, string, typeof ListAltIcon])[];

/** The key of the tab a mapping was last left on, kept per mapping as a viewer preference. */
export const lastTabKey = (mappingId: string) => `mapping:${mappingId}:studio-tab`;

/** The tab a mapping opens on: the one it was last left on when the studio shows it, else Overview. */
export function openingTab(kept: unknown): StudioTab {
  return VIEWS.find(([tab]) => tab === kept)?.[0] ?? 'overview';
}

/** The question asked before a mapping's tab closes: it calls `close` when the tab may close. */
export type CloseGuard = (close: () => void) => void;

const CHECK_PASSED = 'Structures assigned and graph connections complete.';

/**
 * What a check finds in the working copy, over the real compile: a missing source or target in our words, then the
 * failure of the latest compile, or else each of its errors and warnings but the missing structures already named.
 */
function checkIssues({ workingCopy, compileFailure, diagnostics }: WorkingCopyState): string[] {
  const issues: string[] = [];
  if (workingCopy.source === undefined) issues.push('Choose a source structure.');
  if (workingCopy.target === undefined) issues.push('Choose a target structure.');
  if (compileFailure !== null) return [...issues, `The mapping could not be compiled: ${compileFailure}`];
  for (const { code, severity, message } of diagnostics) {
    if (code !== 'missing-structure') issues.push(`${severity === 'error' ? 'Error' : 'Warning'}: ${message}`);
  }
  return issues;
}

/** The keys that move between the tabs, to the index they move to from `index` of `count`. */
function movedTo(key: string, index: number, count: number): number | undefined {
  switch (key) {
    case 'Home':
      return 0;
    case 'End':
      return count - 1;
    case 'ArrowLeft':
      return (index + count - 1) % count;
    case 'ArrowRight':
      return (index + 1) % count;
    default:
      return undefined;
  }
}

/** Arrow keys, Home and End move the focus to another tab and choose it: only the active tab is in the tab order. */
function navigateTabs(event: KeyboardEvent<HTMLElement>) {
  const target = event.target as HTMLElement;
  if (target.getAttribute('role') !== 'tab') return;
  const tabs = [...event.currentTarget.querySelectorAll<HTMLElement>('[role="tab"]')];
  const next = movedTo(event.key, tabs.indexOf(target), tabs.length);
  if (next === undefined) return;
  event.preventDefault();
  tabs[next]!.focus();
  tabs[next]!.click();
}

export interface StudioFrameProps {
  /** The mapping's id, its workspace path; its last segment is the mapping's name. */
  mappingId: string;
  api: ApiClient;
  /** The open mapping's store, whose UI slice holds the tab and edit mode. */
  store: WorkingCopyStore;
  /** The port for viewer preferences: the tab chosen is kept there per mapping, and the Signature tab's previews. */
  browserState: BrowserStateStore;
  /** Told after each save the service accepted. */
  onSaved?: () => void;
  /** Given the question to ask before the mapping's tab closes while it has unsaved changes, and null when it has none. */
  guardClose?: (guard: CloseGuard | null) => void;
}

/** What the Discard changes? dialog is asked for: Cancel's return to view mode, or closing the tab. */
type Discarding = { then: 'return to view mode' } | { then: 'close its tab'; close: () => void };

/**
 * The Mapping studio around one open mapping: a line with the studio's tabs, the mapping's name, and Edit, or Cancel,
 * Check and Save in edit mode, then the active tab's body. Arrow keys, Home and End move between the tabs; the tab
 * chosen is kept in the browser state for the mapping's next open. The structures are refreshed once on open; Save sends
 * `If-Match` and meets a save conflict in its dialog; Cancel, or closing the mapping's tab, with unsaved changes asks
 * before it discards them; the page asks before it unloads while the working copy differs from the baseline. While
 * the studio is shown, Check and F7 check the working copy, and Ctrl+S and Cmd+S save it. A command the store
 * refuses shows why in an inline warning the author can dismiss.
 */
export function StudioFrame({ mappingId, api, store, browserState, onSaved, guardClose }: StudioFrameProps) {
  const name = mappingId.slice(mappingId.lastIndexOf('/') + 1);
  const tab = useStore(store, (s) => s.tab);
  const editing = useStore(store, (s) => s.editing);
  const unsaved = useStore(store, selectUnsaved);
  const panelRef = useRef<HTMLElement>(null);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();
  const labelId = (view: StudioTab) => `${panelId}-${view}`;

  const changeTab = useCallback(
    (next: StudioTab) => {
      store.getState().setTab(next);
      browserState.write(lastTabKey(mappingId), next);
    },
    [store, browserState, mappingId],
  );

  useEffect(() => {
    if (!unsaved) return undefined;
    const ask = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', ask);
    return () => window.removeEventListener('beforeunload', ask);
  }, [unsaved]);

  const [discarding, setDiscarding] = useState<Discarding | null>(null);
  useEffect(() => {
    if (!unsaved) return undefined;
    guardClose?.((close) => setDiscarding({ then: 'close its tab', close }));
    return () => guardClose?.(null);
  }, [guardClose, unsaved]);

  // Refresh on open. The answer is taken in mutate's callbacks, which TanStack Query calls only while the
  // studio is open: an answer that comes after it closed leaves the store alone.
  const [notice, setNotice] = useState<string | null>(null);
  const { mutate: refresh } = useMutation(refreshMutation(api, mappingId, store));
  useEffect(
    () =>
      refresh(undefined, {
        onSuccess: ({ sent, answer }) => {
          const orphaned = store.getState().applyRefresh(sent, answer);
          if (orphaned !== null) setNotice(`The structures changed. Orphaned references: ${orphaned}`);
        },
        onError: (error) => setNotice(`The structures could not be refreshed: ${describeError(error).title}`),
      }),
    [refresh, store],
  );

  // A compile the store scheduled after an edit must not answer into a closed studio.
  useEffect(() => () => store.getState().cancelCompile(), [store]);

  // Check: a compile of the working copy as it is now; its answer is the result, an edit before it ends the check.
  const [check, setCheck] = useState<string[] | null>(null);
  const [checking, setChecking] = useState(false);
  useEffect(
    () =>
      store.subscribe((state, previous) => {
        if (state.workingCopy === previous.workingCopy) return;
        setCheck(null);
        setChecking(false);
      }),
    [store],
  );
  useEffect(() => {
    if (!checking) return undefined;
    const answered = store.getState().compiles;
    const unsubscribe = store.subscribe((state) => {
      if (state.compiles === answered) return;
      setCheck(checkIssues(state));
      setChecking(false);
    });
    store.getState().scheduleCompile();
    return unsubscribe;
  }, [checking, store]);
  const runCheck = () => {
    if (!editing || !unsaved) return;
    setNotice(null);
    setCheck(null);
    setChecking(true);
  };

  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<ReturnType<typeof describeError> | null>(null);
  const [saved, setSaved] = useState(0);
  const save = async () => {
    if (!editing || !unsaved || saving) return;
    setSaving(true);
    setFailure(null);
    try {
      await store.getState().save();
      // a save conflict resolves too: its dialog asks, and nothing was saved
      if (!store.getState().conflict) {
        setSaved((n) => n + 1);
        onSaved?.();
      }
    } catch (error) {
      setFailure(describeError(error));
    } finally {
      setSaving(false);
    }
  };

  // Ctrl+S or Cmd+S saves and F7 checks, as Save and Check do, in the studio shown; a key pressed in a dialog is the
  // dialog's. The studios of the other open mappings sit in hidden tabs.
  useEffect(() => {
    const press = (event: globalThis.KeyboardEvent) => {
      if (!panelRef.current?.checkVisibility()) return;
      if (event.target instanceof Element && event.target.closest('[role="dialog"]')) return;
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        void save();
      } else if (event.key === 'F7') {
        event.preventDefault();
        runCheck();
      }
    };
    window.addEventListener('keydown', press);
    return () => window.removeEventListener('keydown', press);
  });

  const leaveEditing = () => {
    setDiscarding(null);
    setFailure(null);
    store.getState().discard();
    requestAnimationFrame(() => editButtonRef.current?.focus());
  };
  const discard = () => {
    if (discarding?.then === 'close its tab') {
      setDiscarding(null);
      discarding.close();
    } else leaveEditing();
  };

  // A refused command says why until it is dismissed or a command applies; each refusal is a new object, so the next one shows.
  const refusal = useStore(store, (s) => s.refusal);
  const [dismissed, setDismissed] = useState<Refusal | null>(null);

  return (
    <PageSection>
      <Stack hasGutter>
        <StackItem>
          <Flex alignItems={{ default: 'alignItemsCenter' }} flexWrap={{ default: 'nowrap' }}>
            <FlexItem grow={{ default: 'grow' }}>
              <Tabs
                isSubtab
                activeKey={tab}
                onSelect={(_, key) => changeTab(key as StudioTab)}
                tabListAriaLabel="Editor views"
                onKeyDown={navigateTabs}
              >
                {VIEWS.map(([key, label, Icon]) => (
                  <Tab
                    key={key}
                    eventKey={key}
                    title={
                      <>
                        <TabTitleIcon>
                          <Icon />
                        </TabTitleIcon>
                        <TabTitleText id={labelId(key)}>{label}</TabTitleText>
                      </>
                    }
                    tabIndex={tab === key ? 0 : -1}
                    tabContentRef={panelRef}
                    tabContentId={panelId}
                  />
                ))}
              </Tabs>
            </FlexItem>
            <FlexItem>
              <Title headingLevel="h1" size="lg">
                {name}
              </Title>
            </FlexItem>
            <FlexItem>
              {editing ? (
                <>
                  <Button size="sm" variant="link" onClick={() => (unsaved ? setDiscarding({ then: 'return to view mode' }) : leaveEditing())}>
                    Cancel
                  </Button>{' '}
                  <Button size="sm" variant="secondary" icon={<CheckCircleIcon />} isDisabled={!unsaved || checking} onClick={runCheck}>
                    {checking ? 'Checking…' : 'Check'}
                  </Button>{' '}
                  <Button size="sm" variant="primary" icon={<SaveIcon />} isDisabled={!unsaved || saving} onClick={() => void save()}>
                    Save
                  </Button>
                </>
              ) : (
                <Button ref={editButtonRef} size="sm" variant="primary" icon={<EditIcon />} onClick={() => store.getState().startEditing()}>
                  Edit
                </Button>
              )}
              <AboutStudio />
            </FlexItem>
          </Flex>
        </StackItem>
        {check !== null && (
          <StackItem>
            <CheckResult issues={check} onDismiss={() => setCheck(null)} />
          </StackItem>
        )}
        {notice !== null && (
          <StackItem>
            <Alert variant="info" isInline role="status" title={notice} actionClose={<AlertActionCloseButton aria-label="Dismiss message" onClose={() => setNotice(null)} />} />
          </StackItem>
        )}
        {failure && (
          <StackItem>
            <Alert variant="danger" isInline title={failure.title}>
              <p>{failure.detail}</p>
              <List aria-label="Save diagnostics">
                {failure.diagnostics.map(({ severity, code, message }, i) => (
                  <ListItem key={i}>
                    {severity} <code>{code}</code>: {message}
                  </ListItem>
                ))}
              </List>
            </Alert>
          </StackItem>
        )}
        {refusal !== null && refusal !== dismissed && (
          <StackItem>
            <Alert
              variant="warning"
              isInline
              title={describeRefusal(refusal)}
              actionClose={<AlertActionCloseButton aria-label="Dismiss refusal" onClose={() => setDismissed(refusal)} />}
            />
          </StackItem>
        )}
        <StackItem isFilled>
          <TabContent id={panelId} ref={panelRef} aria-labelledby={labelId(tab)}>
            {tab === 'overview' && <MappingOverview mappingId={mappingId} store={store} />}
            {tab === 'signature' && <Signature mappingId={mappingId} api={api} store={store} browserState={browserState} />}
          </TabContent>
        </StackItem>
      </Stack>
      <DiscardDialog isOpen={discarding !== null} name={name} then={discarding?.then ?? ''} onDiscard={discard} onKeepEditing={() => setDiscarding(null)} />
      <SaveConflict store={store} />
      <AlertGroup isToast isLiveRegion aria-label="Save notifications">
        {saved > 0 && (
          <Alert
            key={saved}
            variant="success"
            title={`Mapping ${name} saved`}
            // a timed-out alert renders nothing by itself; the next save's key mounts a new one
            timeout={8000}
            actionClose={<AlertActionCloseButton aria-label="Dismiss save notification" onClose={() => setSaved(0)} />}
          />
        )}
      </AlertGroup>
    </PageSection>
  );
}

/** The result of a check: the issues found or that it passed, to copy and dismiss. */
function CheckResult({ issues, onDismiss }: { issues: string[]; onDismiss: () => void }) {
  const found = issues.length > 0;
  const text = found ? ['Check found issues', ...issues].join('\n') : `Check passed · ${CHECK_PASSED}`;
  return (
    <Alert
      variant={found ? 'warning' : 'success'}
      isInline
      role="status"
      title={found ? 'Check found issues' : 'Check passed'}
      actionClose={
        <>
          <CopyTextButton label="check result" text={text} />
          <AlertActionCloseButton aria-label="Dismiss check" onClose={onDismiss} />
        </>
      }
    >
      {found ? (
        <List>
          {issues.map((issue, i) => (
            <ListItem key={i}>{issue}</ListItem>
          ))}
        </List>
      ) : (
        CHECK_PASSED
      )}
    </Alert>
  );
}
