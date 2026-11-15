import { useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import type { BrowserStateStore } from '../browser-state/browser-state';
import { isShare, readPanelPreference, savePanelPreference } from './panel-preferences';

const clampShare = (share: number) => Math.max(0, Math.min(1, share));

/** How the split shows: both areas, the lower one maximised (the upper hidden), or the lower one minimised (hidden). */
export type SplitView = 'split' | 'minimized' | 'maximized';
const SPLIT_VIEWS: readonly unknown[] = ['split', 'minimized', 'maximized'];

/** The Definition tab's split between the message panes and the mapping expression, as browser state keeps it. */
export interface SplitPreference {
  view: SplitView;
  share: number;
}

const isSplitPreference = (value: unknown): value is SplitPreference =>
  typeof value === 'object' && value !== null && SPLIT_VIEWS.includes((value as SplitPreference).view) && isShare((value as SplitPreference).share);

/** The split kept under `key`, else `{view: 'split', share: initialShare}`; `setView` and `setShare` keep a change. */
export function useSplitLayout(browserState: BrowserStateStore, key: string, initialShare: number) {
  const [layout, setLayout] = useState<SplitPreference>(() =>
    readPanelPreference(browserState, key, { view: 'split', share: initialShare }, isSplitPreference),
  );
  const update = (changes: Partial<SplitPreference>) => {
    const next = { ...layout, ...changes };
    setLayout(next);
    savePanelPreference(browserState, key, next);
  };
  return { ...layout, setView: (view: SplitView) => update({ view }), setShare: (share: number) => update({ share }) };
}

/** A splitter only redistributes space beyond each pane's minimum size. */
function flexibleSize(element: Element, dimension: 'width' | 'height', minimum: 'minWidth' | 'minHeight') {
  return element.getBoundingClientRect()[dimension] - parseFloat(getComputedStyle(element)[minimum]);
}

interface SplitDividerProps {
  vertical?: boolean;
  label: string;
  controls: string;
  share: number;
  onChange: (share: number) => void;
  onCommit: (share: number) => void;
  /** How far the divider can travel, in pixels. */
  getTravel: (divider: HTMLElement) => number;
}

/**
 * The prototype's divider: dragged with the primary pointer, or moved by 5 % with the arrow keys, Home and End. The share
 * changes while it moves and is kept when it stops.
 */
function SplitDivider({ vertical = false, label, controls, share, onChange, onCommit, getTravel }: SplitDividerProps) {
  const drag = useRef<{ id: number; position: number; start: number; share: number; travel: number } | null>(null);
  const position = (event: PointerEvent) => (vertical ? event.clientX : -event.clientY);

  const startDrag = (event: PointerEvent<HTMLDivElement>) => {
    if (!event.isPrimary || event.button !== 0) return;
    const travel = getTravel(event.currentTarget);
    if (travel <= 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { id: event.pointerId, position: position(event), start: share, share, travel };
  };
  const moveDrag = (event: PointerEvent) => {
    const current = drag.current;
    if (current?.id !== event.pointerId) return;
    current.share = clampShare(current.start + (position(event) - current.position) / current.travel);
    onChange(current.share);
  };
  const finishDrag = (event: PointerEvent) => {
    if (drag.current?.id !== event.pointerId) return;
    onCommit(drag.current.share);
    drag.current = null;
  };
  const resizeWithKeys = (event: KeyboardEvent) => {
    const changes: Record<string, number> = {
      [vertical ? 'ArrowRight' : 'ArrowUp']: share + 0.05,
      [vertical ? 'ArrowLeft' : 'ArrowDown']: share - 0.05,
      Home: 0,
      End: 1,
    };
    if (!Object.hasOwn(changes, event.key)) return;
    event.preventDefault();
    const next = clampShare(changes[event.key]!);
    onChange(next);
    onCommit(next);
  };

  return (
    <div
      className="split-divider"
      role="separator"
      tabIndex={0}
      aria-label={label}
      aria-orientation={vertical ? 'vertical' : 'horizontal'}
      aria-controls={controls}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(share * 100)}
      title={label}
      onKeyDown={resizeWithKeys}
      onPointerDown={startDrag}
      onPointerMove={moveDrag}
      onLostPointerCapture={finishDrag}
    />
  );
}

interface VerticalSplitLayoutProps {
  className: string;
  label: string;
  lowerId: string;
  upper: ReactNode;
  /** Shown above the lower area, in every view (the Mapping expression header). */
  toolbar?: ReactNode;
  children: ReactNode;
  initialShare: number;
  onCommit: (share: number) => void;
  view?: SplitView;
}

/**
 * The upper and lower area with a divider between them; the lower area's share of the free height is `initialShare`.
 * Maximised hides the upper area, minimised the lower one, and either hides the divider.
 */
export function VerticalSplitLayout({ className, label, lowerId, upper, toolbar, children, initialShare, onCommit, view = 'split' }: VerticalSplitLayoutProps) {
  const [lowerShare, setLowerShare] = useState(initialShare);
  const upperRef = useRef<HTMLDivElement>(null);
  const lowerRef = useRef<HTMLDivElement>(null);
  const style = { '--upper-share': 1 - lowerShare, '--lower-share': lowerShare } as CSSProperties;
  return (
    <div className={`vertical-split ${className}`} data-view={view} style={style}>
      <div className="split-upper" ref={upperRef} hidden={view === 'maximized'}>
        {upper}
      </div>
      {view === 'split' && (
        <SplitDivider
          label={label}
          controls={lowerId}
          share={lowerShare}
          onChange={setLowerShare}
          onCommit={onCommit}
          getTravel={() => flexibleSize(upperRef.current!, 'height', 'minHeight') + flexibleSize(lowerRef.current!, 'height', 'minHeight')}
        />
      )}
      {toolbar}
      <div className="split-lower" id={lowerId} ref={lowerRef} hidden={view === 'minimized'}>
        {children}
      </div>
    </div>
  );
}

interface SideBySideProps {
  className: string;
  label: string;
  leftId: string;
  browserState: BrowserStateStore;
  /** The key of the left pane's share in browser state. */
  widthKey: string;
  /** The left pane's share while browser state keeps none. */
  initialShare?: number;
  /** Whether the divider shows: not while the left pane is hidden. */
  showDivider?: boolean;
  /** Shown between the panes instead of the divider (the field connections). */
  gutter?: ReactNode;
  children: [ReactNode, ReactNode];
}

/**
 * Two panes side by side with a divider between them, their widths shared as kept under `widthKey`; while a gutter is
 * given it stands between them instead and the panes share the width equally.
 */
export function SideBySide({ className, label, leftId, browserState, widthKey, initialShare = 0.5, showDivider = true, gutter, children }: SideBySideProps) {
  const [leftShare, setLeftShare] = useState(() => readPanelPreference(browserState, widthKey, initialShare, isShare));
  const [left, right] = children;
  const availableWidth = (divider: HTMLElement) =>
    flexibleSize(divider.previousElementSibling!, 'width', 'minWidth') + flexibleSize(divider.nextElementSibling!, 'width', 'minWidth');
  const style = { '--left-share': leftShare, '--right-share': 1 - leftShare } as CSSProperties;
  return (
    <div className={gutter ? className : `${className} resizable-columns`} style={gutter ? undefined : style}>
      {left}
      {gutter ??
        (showDivider && (
          <SplitDivider
            vertical
            label={label}
            controls={leftId}
            share={leftShare}
            onChange={setLeftShare}
            onCommit={(share) => savePanelPreference(browserState, widthKey, share)}
            getTravel={availableWidth}
          />
        ))}
      {right}
    </div>
  );
}
