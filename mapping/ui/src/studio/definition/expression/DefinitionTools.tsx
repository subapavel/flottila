import { Button, Modal, ModalBody, ModalHeader } from '@patternfly/react-core';
import { CodeBranchIcon, CodeIcon, FolderOpenIcon, LinkIcon, ObjectGroupIcon, TrashIcon } from '@patternfly/react-icons';
import { useState, type MouseEvent as ReactMouseEvent } from 'react';
import type { BrowserStateStore } from '../../browser-state/browser-state';
import type { FieldPath, MappingModel } from '../../../generated/mapping-model';
import { localPath } from '../../../model/field-path';
import { InstanceTools, type InstanceToolsProps } from '../../mock/InstanceTools';
import { expressionText } from '../definition-model';
import { PaneSizeTools } from '../../shared/PaneSizeTools';
import { readPanelPreference, savePanelPreference } from '../../shared/panel-preferences';
import type { SplitView } from '../../shared/SplitLayout';
import { Tool } from '../../shared/Tool';
import { action, type MenuItem } from './ActionMenu';

/** Which field connections the gutter between the panes shows: none, those into `targets`, or all (`null`). */
export interface DependencyView {
  visible: boolean;
  targets: FieldPath[] | null;
}

const isDependencyView = (value: unknown): value is DependencyView =>
  typeof value === 'object' &&
  value !== null &&
  typeof (value as DependencyView).visible === 'boolean' &&
  ((value as DependencyView).targets === null || (Array.isArray((value as DependencyView).targets) && (value as DependencyView).targets!.every((p) => typeof p === 'string')));

/** The Dependencies view of a mapping, kept per mapping and user; `change` keeps a new one. */
export function useDependencies(browserState: BrowserStateStore, mappingId: string) {
  const key = `definition.${mappingId}.dependencies`;
  const [view, setView] = useState<DependencyView>(() => readPanelPreference(browserState, key, { visible: false, targets: null }, isDependencyView));
  const change = (visible: boolean, targets: FieldPath[] | null = null) => {
    const next = { visible, targets };
    setView(next);
    savePanelPreference(browserState, key, next);
  };
  /** The Dependencies menu over `targets`: Show those, Show All, Hide All. */
  const items = (targets: FieldPath[]): MenuItem[] => [
    action('Show', () => change(true, targets)),
    action('Show All', () => change(true)),
    action('Hide All', () => change(false), { disabled: !view.visible }),
  ];
  return { view, change, items };
}

type ShowMenu = (event: ReactMouseEvent, label: string, items: MenuItem[]) => void;

export interface DefinitionBarProps {
  editing: boolean;
  dataFlow: boolean;
  onDataFlow: (dataFlow: boolean) => void;
  view: SplitView;
  onView: (view: SplitView) => void;
  /** Where the messages are chosen: the Signature tab. */
  onChooseMessages: () => void;
  onClearMapping: () => void;
  /** The Dependencies tool: show the connections into the selected target field. */
  onDependencies: () => void;
  dependencyItems: MenuItem[];
  showMenu: ShowMenu;
}

/** The Definition tab's tool bar (the prototype's definition bar). */
export function DefinitionBar({ editing, dataFlow, onDataFlow, view, onView, onChooseMessages, onClearMapping, onDependencies, dependencyItems, showMenu }: DefinitionBarProps) {
  return (
    <div className="definition-bar panel" role="toolbar" aria-label="Definition actions">
      <Tool
        label="Select Message"
        icon={FolderOpenIcon}
        disabled={!editing}
        menu
        onClick={(e) => showMenu(e, 'Select Message', [action('Source Message', onChooseMessages), action('Target Message', onChooseMessages)])}
      />
      <Tool label="Map Selected Fields and Substructures if Names Are Identical" icon={ObjectGroupIcon} disabled onClick={() => undefined} />
      <Tool label="Clear Mapping" icon={TrashIcon} disabled={!editing} onClick={onClearMapping} />
      <span className="tool-divider" />
      <Tool label="Data Flow Editor" icon={CodeBranchIcon} pressed={dataFlow} onClick={() => onDataFlow(true)} />
      <Tool label="Text Preview" icon={CodeIcon} pressed={!dataFlow} onClick={() => onDataFlow(false)} />
      <span className="tool-divider" />
      <span className="tool-split">
        <Tool label="Dependencies" icon={LinkIcon} onClick={onDependencies} />
        <Tool label="Dependencies options" menu onClick={(e) => showMenu(e, 'Dependencies', dependencyItems)} />
      </span>
      {dataFlow && <PaneSizeTools upper pane="message trees" label="Message tree layout" view={view} onChange={onView} />}
    </div>
  );
}

/**
 * The Mapping expression header: its title and target field, the queue test input (the prototype's QueueInputTools: the
 * test instance tools, a preview, #170, without Generate; a file must parse), its pane size tools.
 */
export function ExpressionHeader({ targetField, view, onView, instances }: { targetField: FieldPath | null; view: SplitView; onView: (view: SplitView) => void; instances: Omit<InstanceToolsProps, 'validateFile' | 'selectLabel'> }) {
  const path = targetField === null ? '' : `/${localPath(targetField)}`;
  return (
    <header className="expression-toolbar" role="toolbar" aria-label="Mapping expression">
      <div className="expression-title">
        <h2>
          <CodeBranchIcon /> Mapping expression
        </h2>
        <code title={path}>{path}</code>
      </div>
      <div className="expression-input-tools" role="group" aria-label="Queue test input">
        <InstanceTools {...instances} selectLabel="Queue test input" validateFile />
      </div>
      <PaneSizeTools pane="mapping expression" label="Expression layout" view={view} onChange={onView} />
    </header>
  );
}

/** Text Preview: each mapped target field and its expression; a click opens it in the data flow editor. */
export function TextPreview({ model, onOpen }: { model: MappingModel; onOpen: (targetField: FieldPath) => void }) {
  const mapped = Object.entries(model.targetFieldMappings ?? {}).filter(([, mapping]) => expressionText(mapping) !== '');
  return (
    <section className="panel definition-preview" aria-label="Text Preview">
      <h2>Text Preview</h2>
      <div className="preview-lines">
        {mapped.length
          ? mapped.map(([path, mapping]) => (
              <button key={path} type="button" onClick={() => onOpen(path)}>
                <strong>/{localPath(path)}</strong> = {expressionText(mapping)}
              </button>
            ))
          : 'No mapped fields.'}
      </div>
    </section>
  );
}

/** The Clear Mapping dialog: asks before it clears the expressions of all target fields. */
export function ClearMapping({ onClear, onClose }: { onClear: () => void; onClose: () => void }) {
  return (
    <Modal isOpen variant="small" aria-label="Clear Mapping" onClose={onClose}>
      <ModalHeader title="Clear Mapping" />
      <ModalBody>
        <p>Clear the expressions for all target fields in this local mapping?</p>
        <div className="form-actions">
          <Button variant="danger" icon={<TrashIcon />} onClick={onClear}>
            Clear mapping
          </Button>
          <Button variant="link" onClick={onClose}>
            Cancel
          </Button>
        </div>
      </ModalBody>
    </Modal>
  );
}
