import { ExpandIcon, WindowMinimizeIcon, WindowRestoreIcon } from '@patternfly/react-icons';
import type { SplitView } from './SplitLayout';
import { Tool } from './Tool';

/** The prototype's PaneSizeTools: Minimize and Maximize one area of the split, or Restore split view from either. */
export function PaneSizeTools({ pane, label, upper = false, view, onChange }: { pane: string; label: string; upper?: boolean; view: SplitView; onChange: (view: SplitView) => void }) {
  const sizeButton = (verb: string, next: SplitView, icon: typeof ExpandIcon) => {
    const restore = view === next;
    return <Tool label={restore ? 'Restore split view' : `${verb} ${pane}`} icon={restore ? WindowRestoreIcon : icon} onClick={() => onChange(restore ? 'split' : next)} />;
  };
  return (
    <div className="pane-size-tools" role="group" aria-label={label}>
      {sizeButton('Minimize', upper ? 'maximized' : 'minimized', WindowMinimizeIcon)}
      {sizeButton('Maximize', upper ? 'minimized' : 'maximized', ExpandIcon)}
    </div>
  );
}
