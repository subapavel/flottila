import {
  Button,
  Card,
  CardBody,
  CardFooter,
  CardHeader,
  CardTitle,
  ClipboardCopy,
  Content,
  Flex,
  FlexItem,
  Icon,
  List,
  ListItem,
  SearchInput,
  Spinner,
  Switch,
  Title,
} from '@patternfly/react-core';
import { AngleDownIcon, AngleRightIcon, CheckIcon, CubeIcon, ExclamationTriangleIcon } from '@patternfly/react-icons';
import { Table, Tbody, Td, Th, Thead, Tr } from '@patternfly/react-table';
import { useVirtualizer } from '@tanstack/react-virtual';
import { useEffect, useMemo, useRef, useState, type HTMLAttributes, type KeyboardEvent, type MouseEvent as ReactMouseEvent, type ReactNode } from 'react';
import type { ApiClient, FieldDto, StructureLoaded } from '../../api/client';
import type { FieldPath, StructureRef, TargetFieldMappings } from '../../generated/mapping-model';
import { localPath, stepName } from '../../model/field-path';
import { variableName, VARIABLES_ISSUE } from '../mock/definition-preview';
import { PreviewBadge } from '../mock/PreviewBadge';
import { FieldTypeIcon } from '../shared/FieldTypeIcon';
import { useStructure } from '../shared/structure-query';
import { SOURCE_FIELD_DRAG } from '../store/drag';
import { expressionText, keptRows, mappedHow, schemaLines, typeKind, xsdType, type SchemaLine, type Side } from './definition-model';
import { expandAllPaths, nameMatches, occurrenceText, treeRows, type TreeRow } from './rows';
import { TreeToolbar, type SelectionChange, type TreeFilter, type ViewMode } from './TreeToolbar';

/** The first guess of a row's height, before it is measured. */
const ROW_HEIGHT = 37;
const LINE_HEIGHT = 21;
/** Rows rendered beyond the scrolled view on each side. */
const OVERSCAN = 8;

/** The scrolled area of a pane: it takes the height the pane leaves, and only it scrolls. */
const SCROLL_AREA = { flex: '1 1 0', minHeight: 0, overflow: 'auto' } as const;

/** Whether a key opens a context menu: the context menu key, or Shift+F10. */
export const isContextMenuKey = (e: KeyboardEvent) => e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10');

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
  /** Opens a field's menu, by a right click or the context menu key on its field. */
  onFieldMenu: (anchor: HTMLElement, path: FieldPath) => void;
  /** The target tree's mapping variables (a preview): each shows first under its open parent. */
  variables?: readonly FieldPath[];
  /** Shown below the loaded structure's rows in every view: the target pane's orphaned target field mappings. */
  footer?: ReactNode;
}

const NO_VARIABLES: readonly FieldPath[] = [];
const NO_ORPHANS: ReadonlySet<FieldPath> = new Set();

/** A variable's row under its parent's: no type, no children, at most once (`0..1`). */
const variableRow = (path: FieldPath, depth: number): TreeRow => ({
  path,
  depth,
  field: { kind: 'element', step: path.slice(path.lastIndexOf('/') + 1), namespace: '', localName: variableName(path), min: 0, max: 1, nillable: false },
  expandable: false,
  expanded: false,
});

/**
 * One message pane of the Definition tab, a card: the message's name, file and format, its Tree, Table and Text views
 * and their tools. The tree and the table are virtualised; the text is the XSD generated from the loaded structure, also
 * virtualised. A search shows the fields whose name matches and their ancestors; the filter the connected, unconnected,
 * directly or indirectly mapped or selected fields and their ancestors; both show every branch open. A click on a field
 * picks it; in edit mode a source row drags onto a target row, and a double click adds it to the selected target field
 * mapping.
 */
export function SchemaPane(props: SchemaPaneProps) {
  const { side, mappingId, api, structureRef, editing, onAssign } = props;
  const query = useStructure(mappingId, api, structureRef);
  const title = side === 'source' ? 'Source' : 'Target';
  const fileName = structureRef.bundle[0].slice(structureRef.bundle[0].lastIndexOf('/') + 1);
  const format = 'json' in structureRef.root ? 'JSON' : 'XML';
  const structure = query.data?.kind === 'loaded' ? query.data : undefined;

  let body;
  if (query.isPending)
    body = (
      <CardBody>
        <Spinner size="md" aria-hidden /> Loading the {side} structure…
      </CardBody>
    );
  else if (query.isError)
    body = (
      <CardBody role="alert">
        <Content component="p">{query.error.message}</Content>
      </CardBody>
    );
  else if (query.data.kind === 'refused')
    body = (
      <CardBody>
        <List aria-label={`${title} message diagnostics`}>
          {query.data.diagnostics.map((d, i) => (
            <ListItem key={i}>
              {d.severity} <code>{d.code}</code>: {d.message}
            </ListItem>
          ))}
        </List>
      </CardBody>
    );
  else body = <LoadedPane {...props} structure={query.data} format={format} fileName={fileName} />;

  return (
    <Card isFullHeight isCompact component="section" id={`${side}-message-pane`} aria-label={`${side} message`} data-message-pane style={{ minHeight: 0 }}>
      <CardHeader
        actions={{
          actions: (
            <>
              <Content component="small">{fileName}</Content>
              <Button size="sm" variant="secondary" isDisabled={!editing} aria-label={`Change ${side} message`} title={`Choose ${side} message format`} onClick={onAssign}>
                {format}
              </Button>
            </>
          ),
          hasNoOffset: true,
        }}
      >
        <Flex alignItems={{ default: 'alignItemsCenter' }} flexWrap={{ default: 'nowrap' }}>
          <FlexItem>
            <Icon size="lg" status={side === 'source' ? 'info' : 'custom'}>
              <CubeIcon />
            </Icon>
          </FlexItem>
          <FlexItem>
            <Content component="small">{title} message</Content>
            <CardTitle>
              <Title headingLevel="h2" size="md">
                {structure ? stepName(structure.root.step) : fileName}
              </Title>
            </CardTitle>
          </FlexItem>
        </Flex>
      </CardHeader>
      {body}
    </Card>
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

  /** What a field's button offers: a click picks it; in edit mode a double click on a source field adds it. */
  const fieldActions = (path: FieldPath): FieldActionProps => ({
    onClick: () => onPick(path),
    onDoubleClick: source && editing ? () => onAdd!(path) : undefined,
    onContextMenu: (e: ReactMouseEvent<HTMLElement>) => {
      e.preventDefault();
      onFieldMenu(e.currentTarget, path);
    },
    onKeyDown: (e: KeyboardEvent<HTMLElement>) => {
      if (!isContextMenuKey(e)) return;
      e.preventDefault();
      onFieldMenu(e.currentTarget, path);
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
      <CardBody isFilled={false}>
        <TreeToolbar
          side={side}
          mode={mode}
          onMode={onMode}
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
        {searchOpen && (
          <SearchInput
            ref={searchRef}
            aria-label={`Filter ${side} fields`}
            placeholder={mode === 'Text' ? 'Find in XSD' : 'Find a field'}
            value={search}
            onKeyDown={searchKeys}
            onChange={(_event, value) => setSearch(value)}
            onClear={() => setSearch('')}
          />
        )}
      </CardBody>
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
        <CardFooter>
          <ClipboardCopy isReadOnly isCode variant="inline-compact" hoverTip="Copy field path" clickTip="Copied">
            {`/${selected === null ? '' : localPath(selected)}`}
          </ClipboardCopy>
        </CardFooter>
      )}
      {footer}
    </>
  );
}

type FieldActionProps = Pick<HTMLAttributes<HTMLElement>, 'onClick' | 'onDoubleClick' | 'onContextMenu' | 'onKeyDown'> & { 'aria-pressed': boolean };
type FieldActions = (path: FieldPath) => FieldActionProps;
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
  // Same-step siblings share a field path: the second and later rows of a path get `#n` as their key.
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
    estimateSize: () => ROW_HEIGHT,
    getItemKey: (index) => keys[index]!,
    overscan: OVERSCAN,
  });
  const items = virtualizer.getVirtualItems();
  const top = items[0]?.start ?? 0;
  const bottom = virtualizer.getTotalSize() - (items.at(-1)?.end ?? 0);
  const columns = 1 + (selection ? 1 : 0) + (mapping ? 1 : 0) + (table ? 3 : 0);
  const spacer = (px: number) =>
    px > 0 && (
      <Tr aria-hidden="true">
        <Td colSpan={columns} style={{ height: px, padding: 0 }} />
      </Tr>
    );

  return (
    <div ref={scrollRef} data-fields-scroll style={SCROLL_AREA}>
      <Table variant="compact" gridBreakPoint="" isStickyHeader role="table" aria-label={`${side} fields`}>
        <Thead>
          <Tr>
            <Th>Structure</Th>
            {selection && <Th>Selection</Th>}
            {mapping && <Th>Mapping</Th>}
            {table && (
              <>
                <Th>Occurrences</Th>
                <Th>Type</Th>
                <Th>Description</Th>
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
              <Tr key={item.key} ref={virtualizer.measureElement} data-index={item.index} data-field-path={path} isRowSelected={selected === path} {...rowActions(path)}>
                <Td dataLabel="Structure">
                  <Flex
                    spaceItems={{ default: 'spaceItemsSm' }}
                    alignItems={{ default: 'alignItemsCenter' }}
                    flexWrap={{ default: 'nowrap' }}
                    style={{ paddingInlineStart: `calc(${depth} * var(--pf-t--global--spacer--md))` }}
                  >
                    {expandable && toggleable ? (
                      <Button
                        variant="plain"
                        size="sm"
                        hasNoPadding
                        aria-label={`${expanded ? 'Collapse' : 'Expand'} ${localPath(path)}`}
                        aria-expanded={expanded}
                        icon={expanded ? <AngleDownIcon /> : <AngleRightIcon />}
                        onClick={() => onToggle(path)}
                      />
                    ) : (
                      <Icon size="md" aria-hidden="true">
                        {expandable && <AngleDownIcon />}
                      </Icon>
                    )}
                    <Button
                      variant="link"
                      isInline
                      icon={<FieldTypeIcon type={type} />}
                      aria-label={variable ? `Variable ${name}` : undefined}
                      title={`${localPath(path)} · ${variable ? 'Variable' : type} · ${occurrenceText(field)}`}
                      {...fieldActions(path)}
                    >
                      {name}
                    </Button>
                    {variable && <PreviewBadge feature="Variables" issue={VARIABLES_ISSUE} />}
                    {/* a row shows its most urgent field status: orphaned before connected */}
                    {orphaned.has(path) ? (
                      <Icon
                        status="warning"
                        role="img"
                        title="Orphaned reference: this mapping reads a source field the source structure no longer has. Drop a source field on its box, or remap it from the node menu."
                        aria-label="Orphaned reference"
                      >
                        <ExclamationTriangleIcon />
                      </Icon>
                    ) : (
                      connected.has(path) && (
                        <Icon status="success" role="img" title="Connected" aria-label="Connected">
                          <CheckIcon />
                        </Icon>
                      )
                    )}
                  </Flex>
                </Td>
                {selection && (
                  <Td dataLabel="Selection">
                    <Switch
                      aria-label={`Select /${localPath(path)}`}
                      isChecked={selection.marked.includes(path)}
                      onChange={(_event, checked) => selection.setMarked(checked ? [...selection.marked, path] : selection.marked.filter((p) => p !== path))}
                    />
                  </Td>
                )}
                {mapping && (
                  <Td dataLabel="Mapping">
                    <code>{expressionText(mapping[path]) || `No mapping for /${localPath(path)}`}</code>
                  </Td>
                )}
                {table && (
                  <>
                    <Td dataLabel="Occurrences">{occurrenceText(field)}</Td>
                    {/* a structure field has a value type only with simple content, as a leaf with attributes */}
                    <Td dataLabel="Type">{typeLabel}</Td>
                    <Td dataLabel="Description" />
                  </>
                )}
              </Tr>
            );
          })}
          {spacer(bottom)}
        </Tbody>
      </Table>
      {rows.length === 0 && (
        <CardBody>
          <Content component="p">No matching fields.</Content>
        </CardBody>
      )}
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
    <div ref={scrollRef} style={SCROLL_AREA}>
      <CardBody
        aria-label={`${side === 'source' ? 'Source' : 'Target'} XSD source`}
        style={{ fontFamily: 'var(--pf-t--global--font--family--mono)', fontSize: 'var(--pf-t--global--font--size--body--sm)', whiteSpace: 'pre' }}
      >
        <div style={{ height: top }} />
        {items.map((item) => {
          const { content, path } = lines[item.index]!;
          const text = content.trimStart();
          const indent = content.slice(0, content.length - text.length);
          const line =
            path === undefined ? (
              text
            ) : (
              <Button variant="link" isInline isClicked={path === selected} title={`/${localPath(path)}`} style={{ fontFamily: 'inherit', fontSize: 'inherit' }} {...fieldActions(path)}>
                {text}
              </Button>
            );
          return (
            <div key={item.index} ref={virtualizer.measureElement} data-index={item.index} {...(path === undefined ? {} : rowActions(path))}>
              {indent}
              {matches(content) ? <mark>{line}</mark> : line}
            </div>
          );
        })}
        <div style={{ height: bottom }} />
      </CardBody>
    </div>
  );
}
