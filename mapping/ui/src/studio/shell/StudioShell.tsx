import {
  Alert,
  AlertActionCloseButton,
  AlertGroup,
  Button,
  EmptyState,
  EmptyStateActions,
  EmptyStateBody,
  EmptyStateFooter,
  TabContent,
} from '@patternfly/react-core';
import { CubesIcon, TimesIcon } from '@patternfly/react-icons';
import { useMutation } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useStore } from 'zustand';
import { describeError, type ApiClient } from '../../api/client';
import { describeRefusal, type Refusal } from '../../model/command';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { SaveConflict } from './SaveConflict';
import { selectUnsaved, STUDIO_TABS, type StudioTab, type WorkingCopyState, type WorkingCopyStore } from '../store/working-copy';
import { DefinitionTab } from '../definition/DefinitionTab';
import { FunctionsTab } from '../functions/FunctionsTab';
import { useFunctionsPreview } from '../mock/functions-store';
import { refreshMutation } from '../shared/structure-query';
import { MappingOverview } from '../overview/MappingOverview';
import { Signature } from '../signature/Signature';
import { useTestSession } from '../shared/test-session';
import { TestTab } from '../test/TestTab';
import { CopyTextButton } from '../shared/CopyTextButton';
import { DiscardDialog } from './DiscardDialog';
import { EditorHeader, TAB_PANEL_ID, tabLabelId } from './EditorHeader';
import { Masthead } from './Masthead';

export type { StudioTab };

/** The key of the tab a mapping was last left on, kept per mapping (ADR-0028, viewer preferences). */
export const lastTabKey = (mappingId: string) => `mapping:${mappingId}:studio-tab`;

export const isStudioTab = (value: unknown): value is StudioTab => (STUDIO_TABS as readonly unknown[]).includes(value);

/** The tab a mapping opens on: the URL hash's, else the one it was last left on, else Overview. */
export function openingTab(hash: string, kept: unknown): StudioTab {
  const named = hash.slice(1);
  if (isStudioTab(named)) return named;
  return isStudioTab(kept) ? kept : 'overview';
}

const CHECK_PASSED = 'Structures assigned and graph connections complete.';

/**
 * What a check finds in the working copy (the prototype's runCheck, over the real compile): a missing source or target
 * in our words, then the failure of the latest compile, or else each of its errors and warnings but the missing
 * structures already named.
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

export interface StudioShellProps {
  /** The mapping's id, its workspace path; its last segment is the mapping's name. */
  mappingId: string;
  api: ApiClient;
  /** The open mapping's store (ADR-0013), whose UI slice holds the tab and edit mode (ADR-0028). */
  store: WorkingCopyStore;
  browserState: BrowserStateStore;
  /** Where the masthead's brand leads: the start page. */
  listHref: string;
  /** Told whether the working copy has unsaved changes, at open and at each change. */
  onUnsavedChange?: (unsaved: boolean) => void;
}

/**
 * The Mapping studio around one open mapping (ADR-0028, the prototype's main.jsx): the masthead, the tab line with the
 * mapping's name and Edit, or Cancel and Save in edit mode, and the active tab's body. The tab follows the URL hash
 * both ways. The structures are refreshed once on open (ADR-0027); Save sends `If-Match` and meets a save conflict in
 * its dialog; Cancel with unsaved changes asks before it discards them; the page asks before it unloads while the
 * working copy differs from the baseline, and `onUnsavedChange` hears of it, for leaving within the page. Check and F7
 * check the working copy; Ctrl+S and Cmd+S save it. A command the store refuses, such as a drop, a link or a remap, shows
 * why in an inline warning the author can dismiss (#174).
 */
export function StudioShell({ mappingId, api, store, browserState, listHref, onUnsavedChange }: StudioShellProps) {
  const name = mappingId.slice(mappingId.lastIndexOf('/') + 1);
  const tab = useStore(store, (s) => s.tab);
  const editing = useStore(store, (s) => s.editing);
  const unsaved = useStore(store, selectUnsaved);
  const hasMessages = useStore(store, (s) => s.workingCopy.source !== undefined && s.workingCopy.target !== undefined);
  const panelRef = useRef<HTMLElement>(null);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  // a preview's draft follows the edit session while any tab is open (ADR-0028)
  const functions = useFunctionsPreview(store, browserState, mappingId);
  // the source document and the processing log outlive the Test tab, and the log hears of every compile
  const testSession = useTestSession(store, api, mappingId, browserState);

  const keepTab = useCallback(
    (next: StudioTab) => {
      store.getState().setTab(next);
      browserState.write(lastTabKey(mappingId), next);
    },
    [store, browserState, mappingId],
  );
  const changeTab = (next: StudioTab) => {
    keepTab(next);
    // the entry's state is the page's (its place in the history), not the tab's
    history.replaceState(history.state, '', `#${next}`);
  };
  useEffect(() => {
    const navigate = () => {
      const named = window.location.hash.slice(1);
      if (isStudioTab(named)) keepTab(named);
    };
    window.addEventListener('hashchange', navigate);
    return () => window.removeEventListener('hashchange', navigate);
  }, [keepTab]);

  useEffect(() => onUnsavedChange?.(unsaved), [onUnsavedChange, unsaved]);
  useEffect(() => {
    if (!unsaved) return undefined;
    const ask = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', ask);
    return () => window.removeEventListener('beforeunload', ask);
  }, [unsaved]);

  // Refresh on open (ADR-0027). The answer is taken in mutate's callbacks, which TanStack Query calls only while the
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
      if (!store.getState().conflict) setSaved((n) => n + 1);
    } catch (error) {
      setFailure(describeError(error));
    } finally {
      setSaving(false);
    }
  };

  // Ctrl+S or Cmd+S saves and F7 checks, as Save and Check do; a key pressed in a dialog is the dialog's.
  useEffect(() => {
    const press = (event: KeyboardEvent) => {
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

  const [asking, setAsking] = useState(false);
  const leaveEditing = () => {
    setAsking(false);
    setFailure(null);
    store.getState().discard();
    requestAnimationFrame(() => editButtonRef.current?.focus());
  };

  // A refused command says why until it is dismissed or a command applies; each refusal is a new object, so the next one shows.
  const refusal = useStore(store, (s) => s.refusal);
  const [dismissed, setDismissed] = useState<Refusal | null>(null);

  const needsMessages = (tab === 'definition' || tab === 'test') && !hasMessages;

  return (
    <>
      <Masthead homeHref={listHref} />
      <main className="main">
        <EditorHeader
          title={name}
          activeTab={tab}
          onTabChange={changeTab}
          panelRef={panelRef}
          editing={editing}
          unsaved={unsaved}
          saving={saving}
          checking={checking}
          editButtonRef={editButtonRef}
          onEdit={() => store.getState().startEditing()}
          onCancel={() => (unsaved ? setAsking(true) : leaveEditing())}
          onCheck={runCheck}
          onSave={() => void save()}
        />
        {check !== null && <CheckResult issues={check} copyable={tab !== 'definition' && tab !== 'functions'} onDismiss={() => setCheck(null)} />}
        {notice !== null && (
          <div className="feedback" role="status">
            {notice}
            <button aria-label="Dismiss message" onClick={() => setNotice(null)}>
              <TimesIcon />
            </button>
          </div>
        )}
        {failure && (
          <Alert variant="danger" isInline title={failure.title} className="feedback-alert">
            <p>{failure.detail}</p>
            <ul aria-label="Save diagnostics">
              {failure.diagnostics.map(({ severity, code, message }, i) => (
                <li key={i}>
                  {severity} <code>{code}</code>: {message}
                </li>
              ))}
            </ul>
          </Alert>
        )}
        {refusal !== null && refusal !== dismissed && (
          <Alert
            variant="warning"
            isInline
            title={describeRefusal(refusal)}
            className="feedback-alert"
            actionClose={<AlertActionCloseButton aria-label="Dismiss refusal" onClose={() => setDismissed(refusal)} />}
          />
        )}
        <TabContent className="studio-tab-panel" id={TAB_PANEL_ID} ref={panelRef} aria-labelledby={tabLabelId(tab)}>
          {needsMessages ? (
            <ChooseMessages />
          ) : tab === 'overview' ? (
            <MappingOverview mappingId={mappingId} store={store} />
          ) : tab === 'definition' ? (
            <DefinitionTab mappingId={mappingId} api={api} store={store} session={testSession} browserState={browserState} onChooseMessages={() => changeTab('signature')} />
          ) : tab === 'test' ? (
            <TestTab mappingId={mappingId} store={store} session={testSession} browserState={browserState} />
          ) : tab === 'signature' ? (
            <Signature mappingId={mappingId} api={api} store={store} browserState={browserState} />
          ) : (
            <FunctionsTab mappingId={mappingId} store={store} functions={functions} api={api} />
          )}
        </TabContent>
      </main>
      <DiscardDialog isOpen={asking} name={name} then="return to view mode" onDiscard={leaveEditing} onKeepEditing={() => setAsking(false)} />
      <SaveConflict store={store} />
      <AlertGroup className="mapping-save-toast" isToast isLiveRegion aria-label="Save notifications">
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
    </>
  );
}

/** The result of a check (the prototype's check banner): the issues found or that it passed, to copy and dismiss. */
function CheckResult({ issues, copyable, onDismiss }: { issues: string[]; copyable: boolean; onDismiss: () => void }) {
  const found = issues.length > 0;
  return (
    <div className={`feedback ${found ? 'warn' : 'ok'}`} role="status">
      <div>
        <strong>{found ? 'Check found issues' : 'Check passed'}</strong>
        {found ? (
          <ul>
            {issues.map((issue, i) => (
              <li key={i}>{issue}</li>
            ))}
          </ul>
        ) : (
          <span> · {CHECK_PASSED}</span>
        )}
      </div>
      <div className="check-actions">
        {copyable && <CopyTextButton label="check result" text={found ? ['Check found issues', ...issues].join('\n') : `Check passed · ${CHECK_PASSED}`} />}
        <button aria-label="Dismiss check" onClick={onDismiss}>
          <TimesIcon />
        </button>
      </div>
    </div>
  );
}

/** The body of Definition and Test while the mapping lacks a message: what to do, and the link to Signature. */
function ChooseMessages() {
  return (
    <div className="panel">
      <EmptyState titleText="Choose the source and target messages" headingLevel="h2" icon={CubesIcon}>
        <EmptyStateBody>This tab needs both messages of the mapping.</EmptyStateBody>
        <EmptyStateFooter>
          <EmptyStateActions>
            <Button component="a" href="#signature" variant="link">
              Choose them on the Signature tab
            </Button>
          </EmptyStateActions>
        </EmptyStateFooter>
      </EmptyState>
    </div>
  );
}
