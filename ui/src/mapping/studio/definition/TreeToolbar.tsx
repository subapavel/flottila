import { ToggleGroup, ToggleGroupItem, Toolbar, ToolbarContent, ToolbarGroup, ToolbarItem } from '@patternfly/react-core';
import {
  AngleDoubleDownIcon,
  AngleDoubleUpIcon,
  ColumnsIcon,
  DownloadIcon,
  FilterIcon,
  RouteIcon,
  SearchIcon,
  ToggleOffIcon,
  ToggleOnIcon,
} from '@patternfly/react-icons';
import { FILTERS, type Side } from './definition-model';
import { Tool, ToolMenu, type ToolMenuItem } from '../shared/Tool';

export type ViewMode = 'Tree' | 'Table' | 'Text';

export const VIEW_MODES: readonly ViewMode[] = ['Tree', 'Table', 'Text'];

/** Whether the tree filter is on, and which of `FILTERS` it applies. */
export interface TreeFilter {
  active: boolean;
  kind: number;
}

/** How Select All, Select None and Inverse Selection change the selected fields. */
export type SelectionChange = 'Select All' | 'Select None' | 'Inverse Selection';

interface TreeToolbarProps {
  side: Side;
  mode: ViewMode;
  onMode: (mode: ViewMode) => void;
  fullPath: boolean;
  onPath: () => void;
  /** Expand Tree, then Collapse Tree after it: what `expand` does next. */
  collapseNext: boolean;
  expand: () => void;
  filter: TreeFilter;
  chooseFilter: (kind: number) => void;
  onToggleFilter: () => void;
  searchOpen: boolean;
  onSearch: () => void;
  onDownload: () => void;
  mappingColumn: boolean;
  onMapping: () => void;
  selectionColumn: boolean;
  onSelection: (shown: boolean) => void;
  onSelectionChange: (change: SelectionChange) => void;
}

/**
 * A message pane's tools: the view switch; Expand or Collapse Tree, Show Full Path and Filter Tree with its options in
 * the tree and table views; on the target, the mapping column and the selection column with its options; and in every
 * view the search and Save Document Source.
 */
export function TreeToolbar(props: TreeToolbarProps) {
  const { side, mode, onMode, fullPath, onPath, collapseNext, expand, filter, chooseFilter, onToggleFilter, searchOpen, onSearch, onDownload } = props;
  const { mappingColumn, onMapping, selectionColumn, onSelection, onSelectionChange } = props;
  const target = side === 'target';
  const title = target ? 'Target' : 'Source';
  const filterItems: ToolMenuItem[] = [
    { label: filter.active ? 'Switch Off' : 'Switch On', run: onToggleFilter },
    ...(target ? FILTERS : FILTERS.slice(0, 2)).map((name, i) => ({ label: name, run: () => chooseFilter(i), checked: i === filter.kind })),
  ];
  const selectionItems: ToolMenuItem[] = [
    { label: selectionColumn ? 'Hide Selection Column' : 'Show Selection Column', run: () => onSelection(!selectionColumn) },
    ...(['Select All', 'Select None', 'Inverse Selection'] as const).map((change) => ({ label: change, run: () => onSelectionChange(change) })),
  ];
  return (
    <Toolbar hasNoPadding>
      <ToolbarContent>
        <ToolbarItem>
          <ToggleGroup isCompact aria-label={`${title} message view`}>
            {VIEW_MODES.map((name) => (
              <ToggleGroupItem key={name} text={name} isSelected={mode === name} onChange={() => onMode(name)} />
            ))}
          </ToggleGroup>
        </ToolbarItem>
        <ToolbarGroup variant="action-group-plain" role="toolbar" aria-label={`${side} tree tools`}>
          {mode !== 'Text' && (
            <>
              <Tool label={collapseNext ? 'Collapse Tree' : 'Expand Tree'} icon={collapseNext ? AngleDoubleUpIcon : AngleDoubleDownIcon} onClick={expand} />
              <Tool label="Show Full Path" icon={RouteIcon} pressed={fullPath} onClick={onPath} />
              <Tool label="Filter Tree" icon={FilterIcon} pressed={filter.active} onClick={onToggleFilter} />
              <ToolMenu label="Filter Tree options" items={filterItems} />
              {target && (
                <>
                  <Tool label="Show Mapping Column" icon={ColumnsIcon} pressed={mappingColumn} onClick={onMapping} />
                  <Tool
                    label="Show Selection Column"
                    icon={selectionColumn ? ToggleOnIcon : ToggleOffIcon}
                    pressed={selectionColumn}
                    onClick={() => onSelection(!selectionColumn)}
                  />
                  <ToolMenu label="Selection column options" items={selectionItems} />
                </>
              )}
            </>
          )}
          <Tool label={searchOpen ? 'Close Search' : 'Open Search'} icon={SearchIcon} pressed={searchOpen} onClick={onSearch} />
          <Tool label="Save Document Source" icon={DownloadIcon} onClick={onDownload} />
        </ToolbarGroup>
      </ToolbarContent>
    </Toolbar>
  );
}
