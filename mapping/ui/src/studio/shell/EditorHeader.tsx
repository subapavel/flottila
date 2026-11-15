import { Button, Tab, Tabs, TabTitleText } from '@patternfly/react-core';
import { CheckCircleIcon, CodeIcon, EditIcon, ExchangeAltIcon, FlaskIcon, ListAltIcon, ProjectDiagramIcon, SaveIcon } from '@patternfly/react-icons';
import type { KeyboardEvent, Ref, RefObject } from 'react';
import { STUDIO_TABS, type StudioTab } from '../store/working-copy';

/** The tab panel every tab title controls; the active tab's body renders in it. */
export const TAB_PANEL_ID = 'studio-tab-panel';

/** The id of a tab's title text, which names the tab panel while the tab is active. */
export const tabLabelId = (tab: StudioTab) => `view-label-${tab}`;

const TAB_TITLES: Record<StudioTab, [string, typeof ListAltIcon]> = {
  overview: ['Overview', ListAltIcon],
  definition: ['Definition', ProjectDiagramIcon],
  test: ['Test', FlaskIcon],
  signature: ['Signature', ExchangeAltIcon],
  functions: ['Functions', CodeIcon],
};

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

/**
 * Arrow keys, Home and End move the focus to another tab and choose it (the prototype's EditorHeader): only the active
 * tab is in the tab order.
 */
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

export interface EditorHeaderProps {
  /** The mapping's name, the header's title. */
  title: string;
  activeTab: StudioTab;
  onTabChange: (tab: StudioTab) => void;
  panelRef: RefObject<HTMLElement | null>;
  editing: boolean;
  unsaved: boolean;
  saving: boolean;
  checking: boolean;
  editButtonRef: Ref<HTMLButtonElement>;
  onEdit: () => void;
  onCancel: () => void;
  onCheck: () => void;
  onSave: () => void;
}

/**
 * The tab line of the studio (the prototype's EditorHeader): the tabs Overview to Functions, then the mapping's title
 * and, in view mode, Edit; in edit mode, Cancel, Check and Save, the last two enabled while the working copy has
 * unsaved changes and Check is not under way, or Save not sent.
 */
export function EditorHeader(props: EditorHeaderProps) {
  const { title, activeTab, onTabChange, panelRef, editing, unsaved, saving, checking, editButtonRef, onEdit, onCancel, onCheck, onSave } = props;
  return (
    <header className="tab-line">
      <Tabs
        activeKey={activeTab}
        onSelect={(_, key) => onTabChange(key as StudioTab)}
        tabListAriaLabel="Editor views"
        onKeyDown={navigateTabs}
        unmountOnExit
      >
        {STUDIO_TABS.map((key) => {
          const [label, Icon] = TAB_TITLES[key];
          return (
            <Tab
              key={key}
              eventKey={key}
              title={
                <TabTitleText id={tabLabelId(key)}>
                  <Icon aria-hidden /> {label}
                </TabTitleText>
              }
              tabIndex={activeTab === key ? 0 : -1}
              tabContentRef={panelRef}
              tabContentId={TAB_PANEL_ID}
            />
          );
        })}
      </Tabs>
      <div className="mapping-summary">
        <h1 title={title}>{title}</h1>
      </div>
      <div className="mapping-actions">
        {editing ? (
          <>
            <Button size="sm" variant="link" onClick={onCancel}>
              Cancel
            </Button>
            <Button size="sm" variant="secondary" icon={<CheckCircleIcon />} isDisabled={!unsaved || checking} onClick={onCheck}>
              {checking ? 'Checking…' : 'Check'}
            </Button>
            <Button size="sm" variant="primary" icon={<SaveIcon />} isDisabled={!unsaved || saving} onClick={onSave}>
              Save
            </Button>
          </>
        ) : (
          <Button ref={editButtonRef} size="sm" variant="primary" icon={<EditIcon />} onClick={onEdit}>
            Edit
          </Button>
        )}
      </div>
    </header>
  );
}
