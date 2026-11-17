import { Stack, StackItem } from '@patternfly/react-core';
import { useMemo, useState } from 'react';
import { useStore } from 'zustand';
import type { ApiClient, StructureLoaded } from '../../api/client';
import type { FieldPath } from '../../generated/mapping-model';
import { localPath, stepName } from '../../model/field-path';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { addVariable, definitionPreview } from '../mock/definition-preview';
import { readPanelPreference, savePanelPreference } from '../shared/panel-preferences';
import { useSplitLayout, VerticalSplitLayout } from '../shared/SplitLayout';
import { useStructure } from '../shared/structure-query';
import type { WorkingCopyStore } from '../store/working-copy';
import { AddVariable } from './AddVariable';
import { ClearMapping, DefinitionBar, ExpressionHeader, FieldMapping, TextPreview, useDependencies } from './DefinitionBar';
import { connectedFields, keptRows, orphanedMappings, orphans, typeKind, type Side } from './definition-model';
import { FieldMenu, useFieldMenu, type FieldMenuGroup } from './FieldMenu';
import { MessageOverview } from './MessageOverview';
import { OrphanedMappings } from './OrphanedMappings';
import { reachedSourceFields, refusalAt } from './rows';
import { SchemaPane } from './SchemaPane';
import { VIEW_MODES, type ViewMode } from './TreeToolbar';

export interface DefinitionTabProps {
  mappingId: string;
  api: ApiClient;
  store: WorkingCopyStore;
  browserState: BrowserStateStore;
  /** Where the messages are chosen: the Signature tab. */
  onChooseMessages: () => void;
}

const viewKey = (side: Side) => `definition.${side}.view`;
const isViewMode = (value: unknown): value is ViewMode => VIEW_MODES.includes(value as ViewMode);
const loaded = (data: unknown) => data as StructureLoaded;
/** The last step of a variable's path (the Add Variable dialog's names). */
const VARIABLE_STEP = /\/\$[A-Za-z_][A-Za-z0-9_]*$/;
/** The height of the tab's split, the message panes above the mapping expression. */
const SPLIT_HEIGHT = '42rem';

/**
 * The Definition tab: the source and target message panes above the mapping expression of the selected target field,
 * the height between them resizable and kept per user, as are each pane's view mode and their widths. A target field
 * selects its target field mapping (the store's selected target), whose expression shows below the panes; a source
 * field is selected in its pane only. In edit mode a source row dropped on a target row, or a source field
 * double-clicked while a target field is selected, adds a source-field box to that target field mapping (the link
 * command), which the fields' refusals refuse. The target pane lists the orphaned target field mappings in a group of
 * their own, to open or, in edit mode, delete. A field's menu finds where a source field is used, adds a variable under a
 * target structure field (a preview) and shows the field connections.
 */
export function DefinitionTab({ mappingId, api, store, browserState, onChooseMessages }: DefinitionTabProps) {
  const model = useStore(store, (s) => s.workingCopy);
  const editing = useStore(store, (s) => s.editing);
  const previewStore = definitionPreview(store, browserState, mappingId);
  const { variables } = useStore(previewStore, (s) => s.value);
  const picked = useStore(store, (s) => s.selectedTarget);
  const [variableParent, setVariableParent] = useState<FieldPath | null>(null);
  const [selectedSource, setSelectedSource] = useState<FieldPath | null>(null);
  const [views, setViews] = useState<Record<Side, ViewMode>>(() => ({
    source: readPanelPreference(browserState, viewKey('source'), 'Table', isViewMode),
    target: readPanelPreference(browserState, viewKey('target'), 'Table', isViewMode),
  }));
  const layout = useSplitLayout(browserState, 'definition.layout', 0.4);
  // the studio shows this tab only once both messages are chosen
  const sourceRef = model.source!;
  const targetRef = model.target!;
  // the panes' own queries: a row to drop or add exists only once its structure has loaded
  const sourceQuery = useStructure(mappingId, api, sourceRef);
  const targetQuery = useStructure(mappingId, api, targetRef);
  const connected = useMemo(() => connectedFields(model), [model]);
  const orphaned = useMemo(() => orphans(model, sourceQuery.data?.kind === 'loaded' ? sourceQuery.data : undefined), [model, sourceQuery.data]);
  const orphanedTargets = useMemo(() => orphanedMappings(model, targetQuery.data?.kind === 'loaded' ? targetQuery.data : undefined), [model, targetQuery.data]);
  const mappings = model.targetFieldMappings ?? {};
  const [dataFlow, setDataFlow] = useState(true);
  const [clearing, setClearing] = useState(false);
  const dependencies = useDependencies(browserState, mappingId);
  const { menu, showMenu, closeMenu } = useFieldMenu();
  const targetTypes = useMemo(
    () => new Map(targetQuery.data?.kind === 'loaded' ? keptRows(targetQuery.data, () => true).map((row) => [row.path, typeKind(row.field)] as const) : []),
    [targetQuery.data],
  );
  // a variable a discard took away leaves nothing selected; a JSON property may be named like a variable
  const selectedTarget = picked !== null && VARIABLE_STEP.test(picked) && !variables.includes(picked) && !targetTypes.has(picked) ? null : picked;

  const setView = (side: Side, mode: ViewMode) => {
    setViews((previous) => ({ ...previous, [side]: mode }));
    savePanelPreference(browserState, viewKey(side), mode);
  };
  const link = (source: FieldPath, target: FieldPath) => {
    // a variable's expression is a preview, drawn on the canvas only
    if (variables.includes(target)) return;
    store.getState().link(source, target, {
      source: refusalAt(loaded(sourceQuery.data), source, 'source'),
      target: refusalAt(loaded(targetQuery.data), target, 'target'),
    });
    store.getState().selectTarget(target);
  };
  /** Deletes an orphaned target field mapping; the expression it showed has nothing left to show. */
  const deleteOrphan = (path: FieldPath) => {
    store.getState().deleteTargetFieldMapping(path);
    if (store.getState().selectedTarget === path) store.getState().selectTarget(null);
  };
  const usesOf = (path: FieldPath) => Object.keys(mappings).filter((target) => reachedSourceFields(mappings[target]).includes(path));
  /** A field's menu: Where Used for a source field, Add Variable for a target field, and Dependencies. */
  const fieldMenu = (side: Side, anchor: HTMLElement, path: FieldPath) => {
    const matches = side === 'source' ? usesOf(path) : [path];
    const groups: FieldMenuGroup[] =
      side === 'source'
        ? [
            {
              label: 'Where Used',
              items: matches.length
                ? matches.map((target) => ({ label: `/${localPath(target)}`, run: () => store.getState().selectTarget(target) }))
                : [{ label: 'No target field reads it', disabled: true }],
            },
          ]
        : [{ items: [{ label: 'Add Variable', run: () => setVariableParent(path), disabled: !editing || targetTypes.get(path) !== 'structure' }] }];
    showMenu({ label: `Field actions for ${stepName(path)}`, anchor, groups: [...groups, { label: 'Dependencies', items: dependencies.items(matches) }] });
  };

  const pane = (side: Side) => (
    <SchemaPane
      side={side}
      mappingId={mappingId}
      api={api}
      structureRef={side === 'source' ? sourceRef : targetRef}
      mode={views[side]}
      onMode={(mode) => setView(side, mode)}
      editing={editing}
      connected={connected[side]}
      orphaned={side === 'target' ? orphaned.targets : undefined}
      mappings={mappings}
      selected={side === 'source' ? selectedSource : selectedTarget}
      onPick={side === 'source' ? setSelectedSource : (path) => store.getState().selectTarget(path)}
      onAdd={(path) => {
        if (selectedTarget !== null) link(path, selectedTarget);
      }}
      onDirect={link}
      onAssign={onChooseMessages}
      onFieldMenu={(anchor, path) => fieldMenu(side, anchor, path)}
      variables={side === 'target' ? variables : undefined}
      footer={
        side === 'target' &&
        orphanedTargets.length > 0 && (
          <OrphanedMappings paths={orphanedTargets} selected={selectedTarget} editing={editing} onOpen={(path) => store.getState().selectTarget(path)} onDelete={deleteOrphan} />
        )
      }
    />
  );

  const view = dataFlow ? layout.view : 'split';
  const selectedDependencies = selectedTarget !== null && mappings[selectedTarget] !== undefined ? [selectedTarget] : [];

  return (
    <>
      <Stack
        component="section"
        aria-label="Mapping definition"
        onKeyDown={(e) => {
          if (e.key !== 'Escape' || e.defaultPrevented || view === 'split' || menu || clearing) return;
          e.preventDefault();
          layout.setView('split');
        }}
      >
        <StackItem>
          <DefinitionBar
            editing={editing}
            dataFlow={dataFlow}
            onDataFlow={setDataFlow}
            view={view}
            onView={layout.setView}
            onChooseMessages={onChooseMessages}
            onClearMapping={() => setClearing(true)}
            onDependencies={() => dependencies.change(true, selectedDependencies)}
            dependencyItems={dependencies.items(selectedDependencies)}
          />
        </StackItem>
        <StackItem style={{ display: 'flex', flexDirection: 'column', height: SPLIT_HEIGHT }}>
          <VerticalSplitLayout
            label="Resize mapping area"
            lowerId={`${mappingId}-mapping-area`}
            upper={
              <MessageOverview
                browserState={browserState}
                model={model}
                dependencies={dependencies.view}
                selectedTarget={selectedTarget}
                onSelect={(target) => store.getState().selectTarget(target)}
              >
                {pane('source')}
                {pane('target')}
              </MessageOverview>
            }
            initialShare={layout.share}
            onCommit={layout.setShare}
            view={view}
            toolbar={dataFlow && <ExpressionHeader targetField={selectedTarget} view={view} onView={layout.setView} />}
          >
            {dataFlow ? (
              <FieldMapping model={model} targetField={selectedTarget} />
            ) : (
              <TextPreview
                model={model}
                onOpen={(target) => {
                  store.getState().selectTarget(target);
                  setDataFlow(true);
                }}
              />
            )}
          </VerticalSplitLayout>
        </StackItem>
      </Stack>
      {clearing && (
        <ClearMapping
          onClose={() => setClearing(false)}
          onClear={() => {
            store.getState().deleteBoxes(Object.values(mappings).flatMap((mapping) => Object.keys(mapping.boxes)));
            setClearing(false);
          }}
        />
      )}
      {variableParent !== null && (
        <AddVariable
          parent={variableParent}
          taken={(path) => variables.includes(path) || targetTypes.has(path)}
          close={() => setVariableParent(null)}
          create={(name) => {
            previewStore.getState().edit((d) => addVariable(d, variableParent, name));
            setVariableParent(null);
          }}
        />
      )}
      {menu && <FieldMenu menu={menu} onClose={closeMenu} />}
    </>
  );
}
