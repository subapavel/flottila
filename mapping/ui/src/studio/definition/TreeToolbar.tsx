import { Dropdown, DropdownItem, DropdownList } from '@patternfly/react-core';
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
import { useState } from 'react';
import { FILTERS, type Side } from './definition-model';
import { Tool } from '../shared/Tool';

export type ViewMode = 'Tree' | 'Table' | 'Text';

/** Whether the tree filter is on, and which of `FILTERS` it applies. */
export interface TreeFilter {
  active: boolean;
  kind: number;
}

/** How Select All, Select None and Inverse Selection change the selected fields. */
export type SelectionChange = 'Select All' | 'Select None' | 'Inverse Selection';

interface MenuItem {
  label: string;
  run: () => void;
  checked?: boolean;
}

/** A tool's options as a menu below its caret. */
function ToolMenu({ label, items }: { label: string; items: MenuItem[] }) {
  const [open, setOpen] = useState(false);
  return (
    <Dropdown
      isOpen={open}
      onOpenChange={setOpen}
      onSelect={() => setOpen(false)}
      toggle={(ref) => <Tool ref={ref} label={label} menu onClick={() => setOpen(!open)} />}
      popperProps={{ position: 'start' }}
    >
      <DropdownList>
        {items.map(({ label: name, run, checked }) => (
          <DropdownItem key={name} isSelected={checked} onClick={run}>
            {name}
          </DropdownItem>
        ))}
      </DropdownList>
    </Dropdown>
  );
}

interface TreeToolbarProps {
  side: Side;
  mode: ViewMode;
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
 * A message pane's tools (the prototype's TreeToolbar): Expand or Collapse Tree, Show Full Path and Filter Tree with its
 * options in the tree and table views; on the target, the mapping column and the selection column with its options; and
 * in every view the search and Save Document Source.
 */
export function TreeToolbar(props: TreeToolbarProps) {
  const { side, mode, fullPath, onPath, collapseNext, expand, filter, chooseFilter, onToggleFilter, searchOpen, onSearch, onDownload } = props;
  const { mappingColumn, onMapping, selectionColumn, onSelection, onSelectionChange } = props;
  const target = side === 'target';
  const filterItems: MenuItem[] = [
    { label: filter.active ? 'Switch Off' : 'Switch On', run: onToggleFilter },
    ...(target ? FILTERS : FILTERS.slice(0, 2)).map((name, i) => ({ label: name, run: () => chooseFilter(i), checked: i === filter.kind })),
  ];
  const selectionItems: MenuItem[] = [
    { label: selectionColumn ? 'Hide Selection Column' : 'Show Selection Column', run: () => onSelection(!selectionColumn) },
    ...(['Select All', 'Select None', 'Inverse Selection'] as const).map((change) => ({ label: change, run: () => onSelectionChange(change) })),
  ];
  return (
    <div className="tree-tool-buttons" role="toolbar" aria-label={`${side} tree tools`}>
      {mode !== 'Text' && (
        <>
          <Tool label={collapseNext ? 'Collapse Tree' : 'Expand Tree'} icon={collapseNext ? AngleDoubleUpIcon : AngleDoubleDownIcon} onClick={expand} />
          <Tool label="Show Full Path" icon={RouteIcon} pressed={fullPath} onClick={onPath} />
          <span className="tool-split">
            <Tool label="Filter Tree" icon={FilterIcon} pressed={filter.active} onClick={onToggleFilter} />
            <ToolMenu label="Filter Tree options" items={filterItems} />
          </span>
          {target && (
            <>
              <Tool label="Show Mapping Column" icon={ColumnsIcon} pressed={mappingColumn} onClick={onMapping} />
              <span className="tool-split">
                <Tool
                  label="Show Selection Column"
                  icon={selectionColumn ? ToggleOnIcon : ToggleOffIcon}
                  pressed={selectionColumn}
                  onClick={() => onSelection(!selectionColumn)}
                />
                <ToolMenu label="Selection column options" items={selectionItems} />
              </span>
            </>
          )}
        </>
      )}
      <Tool label={searchOpen ? 'Close Search' : 'Open Search'} icon={SearchIcon} pressed={searchOpen} onClick={onSearch} />
      <Tool label="Save Document Source" icon={DownloadIcon} onClick={onDownload} />
    </div>
  );
}
