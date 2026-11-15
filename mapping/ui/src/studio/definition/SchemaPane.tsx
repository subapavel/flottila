import { Switch } from '@patternfly/react-core';
import { Table, Tbody, Th, Thead, Tr } from '@patternfly/react-table';
import { AngleDownIcon, AngleRightIcon, CheckIcon, CubeIcon, ExclamationTriangleIcon, SearchIcon } from '@patternfly/react-icons';
import { useVirtualizer } from '@tanstack/react-virtual';
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type CSSProperties,
  type HTMLAttributes,
  type KeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
} from 'react';
import type { ApiClient, FieldDto, StructureLoaded } from '../../api/client';
import { expandAllPaths, nameMatches, occurrenceText, treeRows, type TreeRow } from './rows';
import { useStructure } from '../shared/structure-query';
import { SOURCE_FIELD_DRAG } from '../store/drag';
import type { FieldPath, StructureRef, TargetFieldMappings } from '../../generated/mapping-model';
import { localPath, stepName } from '../../model/field-path';
import { variableName, VARIABLES_ISSUE } from '../mock/definition-preview';
import { PreviewBadge } from '../mock/PreviewBadge';
import { expressionText, keptRows, mappedHow, schemaLines, typeKind, xsdType, type SchemaLine, type Side } from './definition-model';
import { isContextMenuKey } from './expression/ActionMenu';
import { FieldTypeIcon } from '../shared/FieldTypeIcon';
import { TreeToolbar, type SelectionChange, type TreeFilter, type ViewMode } from './TreeToolbar';
import { ViewSwitch } from '../shared/ViewSwitch';

export const VIEW_MODES: readonly ViewMode[] = ['Tree', 'Table', 'Text'];

/** The prototype's row heights: a 28 px cell and its 1 px rule, 33 px from 1550 px wide (studio.css). */
const ROW_HEIGHT = 29;
const WIDE_ROW_HEIGHT = 34;
const WIDE = '(min-width: 1550px)';
/** An XSD line: 12 px at line height 1.7. */
const LINE_HEIGHT = 20.4;
/** Rows rendered beyond the scrolled view on each side. */
const OVERSCAN = 8;
/**
 * PatternFly's classes of a table row and cell (react-styles table.d.ts): the virtualised body renders plain elements
 * with them, since its rows are all new on each frame of a fast scroll and PatternFly's Tr and Td cost more per cell.
 */
const TR = 'pf-v6-c-table__tr';
const TD = 'pf-v6-c-table__td';

const subscribeWide = (change: () => void) => {
  const query = window.matchMedia(WIDE);
  query.addEventListener('change', change);
  return () => query.removeEventListener('change', change);
};
const rowHeight = () => (window.matchMedia(WIDE).matches ? WIDE_ROW_HEIGHT : ROW_HEIGHT);

export interface SchemaPaneProps {
  side: Side;
  mappingId: string;
  api: ApiClient;
  /** The side's structure; the studio shows the Definition tab only once both are chosen. */
  structureRef: StructureRef;
  mode: ViewMode;
  onMode: (mode: ViewMode) => void;
  editing: boolean;
  /** The fields marked as connected: the source fields the mapping reads, or the mapped target fields. */
  connected: ReadonlySet<FieldPath>;
  /** The target fields whose mapping reads a source field the source structure lacks: marked orphaned, not connected. */
  orphaned?: ReadonlySet<FieldPath>;
  /** The working copy's target field mappings: the mapping column and the direct and indirect filters read them. */
  mappings: TargetFieldMappings;
  selected: FieldPath | null;
  onPick: (path: FieldPath) => void;
  /** A double click on a source row in edit mode. */
  onAdd?: (path: FieldPath) => void;
  /** A source row dropped on a target row in edit mode. */
  onDirect?: (source: FieldPath, target: FieldPath) => void;
  /** The message format button in edit mode: where the messages are chosen. */
  onAssign: () => void;
  /** Opens a field's context menu, by a right click or the context menu key on its row. */
  onFieldMenu: (event: ReactMouseEvent | KeyboardEvent, path: FieldPath) => void;
  /** The target tree's mapping variables (#163, a preview): each shows first under its open parent. */
  variables?: readonly FieldPath[];
  /** Shown below the loaded structure's rows in every view: the target pane's orphaned target field mappings (#174). */
  footer?: ReactNode;
}

const NO_VARIABLES: readonly FieldPath[] = [];
const NO_ORPHANS: ReadonlySet<FieldPath> = new Set();

/** A variable's row under its parent's: no type, no children, at most once (the prototype's `0..1`). */
const variableRow = (path: FieldPath, depth: number): TreeRow => ({
  path,
  depth,
  field: { kind: 'element', step: path.slice(path.lastIndexOf('/') + 1), namespace: '', localName: variableName(path), min: 0, max: 1, nillable: false },
  expandable: false,
  expanded: false,
});

/**
 * One message pane of the Definition tab (the prototype's SchemaPane): the message's name, file and format, its Tree,
 * Table and Text views and their tools. The tree and the table are virtualised (ADR-0012, GATE 7); the text is the XSD
 * generated from the loaded structure, also virtualised. A search shows the fields whose name matches and their
 * ancestors; the filter the connected, unconnected, directly or indirectly mapped or selected fields and their ancestors;
 * both show every branch open. A click on a row picks its field; in edit mode a source row drags onto a target row, and a
 * double click adds it to the selected target field mapping.
 */
export function SchemaPane(props: SchemaPaneProps) {
  const { side, mappingId, api, structureRef, editing, onAssign } = props;
  const query = useStructure(mappingId, api, structureRef);
  const title = side === 'source' ? 'Source' : 'Target';
  const fileName = structureRef.bundle[0].slice(structureRef.bundle[0].lastIndexOf('/') + 1);
  const format = 'json' in structureRef.root ? 'JSON' : 'XML';
  const structure = query.data?.kind === 'loaded' ? query.data : undefined;

  let body;
  if (query.isPending) body = <p className="empty-message">Loading the {side} structure…</p>;
  else if (query.isError)
    body = (
      <p role="alert" className="empty-message">
        {query.error.message}
      </p>
    );
  else if (query.data.kind === 'refused')
    body = (
      <ul aria-label={`${title} message diagnostics`} className="empty-message">
        {query.data.diagnostics.map((d, i) => (
          <li key={i}>
            {d.severity} <code>{d.code}</code>: {d.message}
          </li>
        ))}
      </ul>
    );
  else body = <LoadedPane {...props} structure={query.data} format={format} fileName={fileName} />;

  return (
    <section id={`${side}-message-pane`} className={`schema panel ${side}`} aria-label={`${side} message`}>
      <header className="pane-head">
        <div className={`role-tile ${side}`}>
          <CubeIcon />
        </div>
        <div>
          <div className="eyeline">{title} message</div>
          <h2>{structure ? stepName(structure.root.step) : fileName}</h2>
        </div>
        <div className="schema-format">
          <code className="schema-id">{fileName}</code>
          <button disabled={!editing} aria-label={`Change ${side} message`} title={`Choose ${side} message format`} onClick={onAssign}>
            {format}
            <AngleDownIcon />
          </button>
        </div>
      </header>
      {body}
    </section>
  );
}

function LoadedPane({
  side,
  structure,
  format,
  fileName,
  mode,
  onMode,
  editing,
  connected,
  orphaned = NO_ORPHANS,
  mappings,
  selected,
  onPick,
  onAdd,
  onDirect,
  onFieldMenu,
  variables = NO_VARIABLES,
  footer,
}: SchemaPaneProps & { structure: StructureLoaded; format: string; fileName: string }) {
  const source = side === 'source';
  const title = source ? 'Source' : 'Target';
  const [filter, setFilter] = useState<TreeFilter>({ active: false, kind: 0 });
  const [fullPath, setFullPath] = useState(false);
  const [collapseNext, setCollapseNext] = useState(false);
  const [mappingColumn, setMappingColumn] = useState(false);
  const [selectionColumn, setSelectionColumn] = useState(false);
  const [marked, setMarked] = useState<readonly FieldPath[]>([]);
  const [searchOpen, setSearchOpen] = useState(false);
  const [search, setSearch] = useState('');
  // the expanded structure fields; null while every one is, as a pane opens
  const [expanded, setExpanded] = useState<ReadonlySet<FieldPath> | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const term = search.trim().toLowerCase();
  const all = useMemo(() => new Set(expandAllPaths(structure)), [structure]);
  const lines = useMemo(() => schemaLines(structure), [structure]);
  const keep = useMemo(() => {
    if (!term && !filter.active) return undefined;
    const accepts = (path: FieldPath) => {
      if (filter.kind === 0) return connected.has(path);
      if (filter.kind === 1) return !connected.has(path);
      if (filter.kind === 4) return marked.includes(path);
      return mappedHow(mappings[path]) === (filter.kind === 2 ? 'direct' : 'indirect');
    };
    return (path: FieldPath, field: FieldDto) => (!term || nameMatches(field, term)) && (!filter.active || accepts(path));
  }, [term, filter, connected, marked, mappings]);
  const rows = useMemo(() => {
    if (keep) return keptRows(structure, keep);
    // a search or a filter shows the fields it accepts; the open tree shows each parent's variables first among its children
    return treeRows(structure, expanded ?? all, side).flatMap((row) =>
      row.expanded ? [row, ...variables.filter((v) => v.slice(0, v.lastIndexOf('/')) === row.path).map((v) => variableRow(v, row.depth + 1))] : [row],
    );
  }, [structure, keep, expanded, all, side, variables]);
  const variableSet = useMemo(() => new Set(variables), [variables]);

  const toggleSearch = () => {
    setSearchOpen(!searchOpen);
    if (searchOpen) setSearch('');
    else requestAnimationFrame(() => searchRef.current?.focus());
  };
  const toggle = (path: FieldPath) => {
    setCollapseNext(false);
    setExpanded((old) => {
      const next = new Set(old ?? all);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };
  const download = () => {
    const url = URL.createObjectURL(new Blob([lines.map((line) => line.content).join('\n') + '\n'], { type: 'application/xml' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${fileName.replace(/\.[^.]*$/, '')}.xsd`;
    try {
      link.click();
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  };
  const changeSelection = (change: SelectionChange) => {
    setSelectionColumn(true);
    onMode('Table');
    const visible = rows.map((row) => row.path);
    if (change === 'Select All') setMarked([...new Set([...marked, ...visible])]);
    else if (change === 'Select None') setMarked(marked.filter((path) => !visible.includes(path)));
    else setMarked([...marked.filter((path) => !visible.includes(path)), ...visible.filter((path) => !marked.includes(path))]);
  };
  const searchKeys = (event: KeyboardEvent) => {
    if (event.key === 'Escape') toggleSearch();
    if (event.key === 'Enter') {
      // the first field the search and the filter accept, not an ancestor shown for it; without either, the first row
      const first = rows.find((row) => keep?.(row.path, row.field) ?? true);
      if (first) onPick(first.path);
    }
  };

  /** What a row offers for its field: a click picks it; in edit mode a source row drags and a double click adds it. */
  const fieldActions = (path: FieldPath) => ({
    onClick: () => onPick(path),
    onDoubleClick: source && editing ? () => onAdd!(path) : undefined,
    onContextMenu: (e: ReactMouseEvent) => onFieldMenu(e, path),
    onKeyDown: (e: KeyboardEvent) => {
      if (isContextMenuKey(e)) onFieldMenu(e, path);
    },
    'aria-pressed': selected === path,
  });
  /** A row's drag and drop: in edit mode a source row drags its field, a target row takes a source field. */
  const rowActions = (path: FieldPath): HTMLAttributes<HTMLElement> => {
    if (!editing) return {};
    if (source) {
      return {
        draggable: true,
        onDragStart: (e) => {
          e.dataTransfer.setData(SOURCE_FIELD_DRAG, path);
          e.dataTransfer.effectAllowed = 'copy';
        },
      };
    }
    return {
      onDragOver: (e) => {
        if (e.dataTransfer.types.includes(SOURCE_FIELD_DRAG)) e.preventDefault();
      },
      onDrop: (e) => {
        e.preventDefault();
        onDirect!(e.dataTransfer.getData(SOURCE_FIELD_DRAG), path);
      },
    };
  };

  return (
    <>
      <div className="schema-tools">
        <ViewSwitch modes={VIEW_MODES} label={`${title} message view`} value={mode} onChange={onMode} />
        <TreeToolbar
          side={side}
          mode={mode}
          fullPath={fullPath}
          onPath={() => setFullPath(!fullPath)}
          collapseNext={collapseNext}
          expand={() => {
            setExpanded(collapseNext ? new Set() : null);
            setCollapseNext(!collapseNext);
          }}
          filter={filter}
          chooseFilter={(kind) => {
            setFilter({ active: true, kind });
            setExpanded(null);
          }}
          onToggleFilter={() => {
            setFilter({ ...filter, active: !filter.active });
            setExpanded(null);
          }}
          searchOpen={searchOpen}
          onSearch={toggleSearch}
          onDownload={download}
          mappingColumn={mappingColumn}
          onMapping={() => {
            setMappingColumn(!mappingColumn);
            onMode('Table');
          }}
          selectionColumn={selectionColumn}
          onSelection={(shown) => {
            setSelectionColumn(shown);
            onMode('Table');
          }}
          onSelectionChange={changeSelection}
        />
      </div>
      {searchOpen && (
        <label className="field-search tree-search">
          <SearchIcon />
          <input
            ref={searchRef}
            aria-label={`Filter ${side} fields`}
            placeholder={mode === 'Text' ? 'Find in XSD' : 'Find a field'}
            value={search}
            onKeyDown={searchKeys}
            onChange={(e) => setSearch(e.target.value)}
          />
        </label>
      )}
      {mode === 'Text' ? (
        <SchemaText side={side} lines={lines} term={term} selected={selected} fieldActions={fieldActions} rowActions={rowActions} />
      ) : (
        <FieldRows
          key={mode}
          side={side}
          rows={rows}
          table={mode === 'Table'}
          toggleable={keep === undefined}
          onToggle={toggle}
          format={format}
          connected={connected}
          orphaned={orphaned}
          selected={selected}
          mapping={mode === 'Table' && !source && mappingColumn ? mappings : undefined}
          selection={mode === 'Table' && !source && selectionColumn ? { marked, setMarked } : undefined}
          variables={variableSet}
          fieldActions={fieldActions}
          rowActions={rowActions}
        />
      )}
      {fullPath && mode !== 'Text' && (
        <div className="path-display">
          <code>/{selected === null ? '' : localPath(selected)}</code>
        </div>
      )}
      {footer}
    </>
  );
}

type FieldActions = (path: FieldPath) => HTMLAttributes<HTMLButtonElement> & { 'aria-pressed': boolean };
type RowActions = (path: FieldPath) => HTMLAttributes<HTMLElement>;

interface FieldRowsProps {
  side: Side;
  rows: TreeRow[];
  /** The table view: Occurrences, Type and Description follow Structure. */
  table: boolean;
  /** Whether a row's caret expands and collapses it; under a search or a filter every branch shows open. */
  toggleable: boolean;
  onToggle: (path: FieldPath) => void;
  format: string;
  connected: ReadonlySet<FieldPath>;
  orphaned: ReadonlySet<FieldPath>;
  selected: FieldPath | null;
  /** The mapping column's target field mappings, when it is shown. */
  mapping?: TargetFieldMappings;
  /** The selection column's selected fields, when it is shown. */
  selection?: { marked: readonly FieldPath[]; setMarked: (marked: readonly FieldPath[]) => void };
  /** The rows that are mapping variables, a preview. */
  variables: ReadonlySet<FieldPath>;
  fieldActions: FieldActions;
  rowActions: RowActions;
}

/** The tree or the table of a pane, one PatternFly table row per visible row, only those near the scrolled view rendered. */
function FieldRows({ side, rows, table, toggleable, onToggle, format, connected, orphaned, selected, mapping, selection, variables, fieldActions, rowActions }: FieldRowsProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const height = useSyncExternalStore(subscribeWide, rowHeight);
  // Same-step siblings share a field path (ADR-0008): the second and later rows of a path get `#n` as their key.
  const keys = useMemo(() => {
    const seen = new Map<string, number>();
    return rows.map(({ path }) => {
      const n = (seen.get(path) ?? 0) + 1;
      seen.set(path, n);
      return n === 1 ? path : `${path}#${n}`;
    });
  }, [rows]);
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => height,
    getItemKey: (index) => keys[index]!,
    overscan: OVERSCAN,
  });
  const items = virtualizer.getVirtualItems();
  const top = items[0]?.start ?? 0;
  const bottom = virtualizer.getTotalSize() - (items.at(-1)?.end ?? 0);
  const columns = 1 + (selection ? 1 : 0) + (mapping ? 1 : 0) + (table ? 3 : 0);
  const spacer = (px: number) =>
    px > 0 && (
      <tr aria-hidden="true">
        <td colSpan={columns} style={{ height: px }} />
      </tr>
    );

  return (
    <div className="fields-scroll" ref={scrollRef}>
      <Table variant="compact" gridBreakPoint="" role="table" aria-label={`${side} fields`} className="field-table">
        <Thead>
          <Tr>
            <Th>Structure</Th>
            {selection && <Th>Selection</Th>}
            {mapping && <Th>Mapping</Th>}
            {table && (
              <>
                <Th>Occurrences</Th>
                <Th className="field-type-column">Type</Th>
                <Th className="description-column">Description</Th>
              </>
            )}
          </Tr>
        </Thead>
        <Tbody>
          {spacer(top)}
          {items.map((item) => {
            const { path, depth, field, expandable, expanded } = rows[item.index]!;
            const variable = variables.has(path);
            const type = variable ? 'variable' : typeKind(field);
            const typeLabel = format === 'XML' ? xsdType(field.valueType ?? '') : (field.valueType ?? '');
            const name = variable ? variableName(path) : stepName(path);
            return (
              <tr key={item.key} className={selected === path ? `${TR} active-field` : TR} style={{ height }} data-field-path={path} {...rowActions(path)}>
                <td className={TD}>
                  <div className="field-cell" style={{ '--level': depth } as CSSProperties}>
                    {expandable && toggleable ? (
                      <button className="caret" aria-label={`${expanded ? 'Collapse' : 'Expand'} ${localPath(path)}`} aria-expanded={expanded} onClick={() => onToggle(path)}>
                        {expanded ? <AngleDownIcon /> : <AngleRightIcon />}
                      </button>
                    ) : (
                      <span className="caret-spacer" aria-hidden="true">
                        {expandable && <AngleDownIcon />}
                      </span>
                    )}
                    <button
                      className={`field-name${expandable ? ' branch' : ''}`}
                      aria-label={variable ? `Variable ${name}` : undefined}
                      title={`${localPath(path)} · ${variable ? 'Variable' : type} · ${occurrenceText(field)}`}
                      {...fieldActions(path)}
                    >
                      <FieldTypeIcon type={type} />
                      {name}
                    </button>
                    {variable && <PreviewBadge feature="Variables" issue={VARIABLES_ISSUE} />}
                    {/* a row shows its most urgent field status: orphaned before connected */}
                    {orphaned.has(path) ? (
                      <span
                        className="orphaned-mark"
                        role="img"
                        title="Orphaned reference: this mapping reads a source field the source structure no longer has. Drop a source field on its box, or remap it from the node menu."
                        aria-label="Orphaned reference"
                      >
                        <ExclamationTriangleIcon />
                      </span>
                    ) : (
                      connected.has(path) && (
                        <span className="wired-mark" role="img" title="Connected" aria-label="Connected">
                          <CheckIcon />
                        </span>
                      )
                    )}
                  </div>
                </td>
                {selection && (
                  <td className={TD}>
                    <Switch
                      aria-label={`Select /${localPath(path)}`}
                      isChecked={selection.marked.includes(path)}
                      onChange={(_event, checked) => selection.setMarked(checked ? [...selection.marked, path] : selection.marked.filter((p) => p !== path))}
                    />
                  </td>
                )}
                {mapping && (
                  <td className={TD}>
                    <code className="mapping-code">{expressionText(mapping[path]) || `No mapping for /${localPath(path)}`}</code>
                  </td>
                )}
                {table && (
                  <>
                    <td className={`${TD} occurs`}>{occurrenceText(field)}</td>
                    {/* a structure field has a value type only with simple content, as a leaf with attributes */}
                    <td className={`${TD} field-type-column`}>{typeLabel}</td>
                    <td className={`${TD} description-column`} />
                  </>
                )}
              </tr>
            );
          })}
          {spacer(bottom)}
        </Tbody>
      </Table>
      {rows.length === 0 && <p className="empty-message">No matching fields.</p>}
    </div>
  );
}

interface SchemaTextProps {
  side: Side;
  lines: SchemaLine[];
  term: string;
  selected: FieldPath | null;
  fieldActions: FieldActions;
  rowActions: RowActions;
}

/** The XSD view of a pane: a line per declaration, the lines of a search marked and the first of them scrolled to. */
function SchemaText({ side, lines, term, selected, fieldActions, rowActions }: SchemaTextProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: lines.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => LINE_HEIGHT,
    overscan: OVERSCAN,
  });
  const matches = (content: string) => term !== '' && content.toLowerCase().includes(term);
  useEffect(() => {
    if (term === '') return;
    const first = lines.findIndex(({ content }) => content.toLowerCase().includes(term));
    if (first >= 0) virtualizer.scrollToIndex(first, { align: 'start' });
  }, [term, lines, virtualizer]);
  const items = virtualizer.getVirtualItems();
  const top = items[0]?.start ?? 0;
  const bottom = virtualizer.getTotalSize() - (items.at(-1)?.end ?? 0);
  return (
    <div className="fields-scroll" ref={scrollRef}>
      <div className="schema-xml" aria-label={`${side === 'source' ? 'Source' : 'Target'} XSD source`}>
        <div style={{ height: top }} />
        {items.map((item) => {
          const { content, path } = lines[item.index]!;
          return (
            <div
              key={item.index}
              className={`xml-line${path === selected ? ' active-field' : ''}${matches(content) ? ' find-match' : ''}`}
              style={{ height: LINE_HEIGHT }}
              {...(path === undefined ? {} : rowActions(path))}
            >
              {path === undefined ? (
                content
              ) : (
                <button title={`/${localPath(path)}`} {...fieldActions(path)}>
                  {content}
                </button>
              )}
            </div>
          );
        })}
        <div style={{ height: bottom }} />
      </div>
    </div>
  );
}
