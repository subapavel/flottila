import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { AuthoringId, FieldPath, MappingModel, TargetFieldMapping } from '../../generated/mapping-model';
import { stepName } from '../../model/field-path';
import { SideBySide } from '../shared/SplitLayout';
import type { DependencyView } from './DefinitionBar';

/** A source field a target field mapping reads, and the functions on the way */
interface FieldLink {
  source: FieldPath;
  target: FieldPath;
  functions: string[];
}

function sourceLinks(target: FieldPath, mapping: TargetFieldMapping): FieldLink[] {
  const links: FieldLink[] = [];
  const visit = (id: AuthoringId | null, functions: string[], seen: ReadonlySet<AuthoringId>) => {
    const box = id === null || seen.has(id) ? undefined : mapping.boxes[id];
    if (!box) return;
    if (box.kind === 'source') {
      links.push({ source: box.field, target, functions });
      return;
    }
    const name = box.kind === 'function' ? box.fn : box.kind === 'udf' ? box.udf : box.name;
    for (const input of box.inputs ?? []) visit(input, [name, ...functions], new Set(seen).add(id!));
  };
  visit(mapping.result, [], new Set());
  return links;
}

/** The centre of each field row the pane shows, by field path, from `offset` down. */
function visibleRows(panel: Element | undefined, offset: number) {
  const rows = new Map<FieldPath, number>();
  const scroll = panel?.querySelector('[data-fields-scroll]');
  if (!scroll) return rows;
  const bounds = scroll.getBoundingClientRect();
  for (const row of panel!.querySelectorAll<HTMLElement>('[data-field-path]')) {
    const rect = row.getBoundingClientRect();
    const center = rect.top + rect.height / 2;
    if (center >= bounds.top && center <= bounds.bottom) rows.set(row.dataset.fieldPath!, center - offset);
  }
  return rows;
}

interface FieldConnectionsProps {
  model: MappingModel;
  /** The target fields whose connections show, or all (`null`). */
  targets: FieldPath[] | null;
  selectedTarget: FieldPath | null;
  onSelect: (target: FieldPath) => void;
}

/** The colours of a connection, PatternFly tokens: the selected target field's in the brand colour. */
const STROKE = 'var(--pf-t--global--border--color--default)';
const CURRENT = 'var(--pf-t--global--border--color--clicked)';
const MARK_FILL = 'var(--pf-t--global--background--color--primary--default)';
const MARK_TEXT = 'var(--pf-t--global--text--color--regular)';

/**
 * The field connections: a curve from each source field row to each target field row whose mapping reads it, both in
 * view, with a function mark when functions lie between; a curve selects its target field.
 */
function FieldConnections({ model, targets, selectedTarget, onSelect }: FieldConnectionsProps) {
  const gutter = useRef<HTMLDivElement>(null);
  const links = useMemo(
    () =>
      Object.entries(model.targetFieldMappings ?? {})
        .filter(([target]) => targets === null || targets.includes(target))
        .flatMap(([target, mapping]) => sourceLinks(target, mapping)),
    [model, targets],
  );
  const [lines, setLines] = useState<(FieldLink & { width: number; y1: number; y2: number })[]>([]);
  useLayoutEffect(() => {
    const owner = gutter.current!.parentElement!;
    const panels = [...owner.querySelectorAll('[data-message-pane]')];
    const measure = () => {
      const { top, width } = gutter.current!.getBoundingClientRect();
      const left = visibleRows(panels[0], top);
      const right = visibleRows(panels[1], top);
      setLines(links.filter((l) => left.has(l.source) && right.has(l.target)).map((l) => ({ ...l, width, y1: left.get(l.source)!, y2: right.get(l.target)! })));
    };
    let frame = 0;
    const schedule = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(measure);
    };
    const resize = new ResizeObserver(schedule);
    resize.observe(owner);
    const changes = new MutationObserver(schedule);
    for (const panel of panels) {
      resize.observe(panel);
      changes.observe(panel, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'open'] });
    }
    owner.addEventListener('scroll', schedule, true);
    measure();
    return () => {
      cancelAnimationFrame(frame);
      resize.disconnect();
      changes.disconnect();
      owner.removeEventListener('scroll', schedule, true);
    };
  }, [links]);

  return (
    <div ref={gutter} style={{ flex: '0 0 var(--pf-t--global--spacer--3xl)', position: 'relative' }}>
      <svg role="group" aria-label="Field connections" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', overflow: 'visible' }}>
        {lines.map(({ source, target, functions, width, y1, y2 }, i) => {
          const curve = `M0 ${y1} C${width * 0.43} ${y1} ${width * 0.57} ${y2} ${width} ${y2}`;
          const label = [stepName(source), ...functions, stepName(target)].join(' → ');
          const current = target === selectedTarget;
          return (
            <g
              key={`${source}|${target}|${i}`}
              role="button"
              tabIndex={0}
              aria-label={label}
              aria-pressed={current}
              style={{ cursor: 'pointer' }}
              onClick={() => onSelect(target)}
              onKeyDown={(e) => {
                if (e.key !== 'Enter' && e.key !== ' ') return;
                e.preventDefault();
                onSelect(target);
              }}
            >
              <title>{label}</title>
              {/* a wide invisible stroke that takes the clicks around the thin one */}
              <path d={curve} style={{ fill: 'none', stroke: STROKE, strokeOpacity: 0, strokeWidth: 12 }} />
              <path d={curve} style={{ fill: 'none', stroke: current ? CURRENT : STROKE, strokeWidth: current ? 2 : 1 }} />
              {functions.length > 0 && (
                <g transform={`translate(${width / 2} ${(y1 + y2) / 2})`}>
                  <rect x="-11" y="-9" width="22" height="18" rx="5" style={{ fill: MARK_FILL, stroke: current ? CURRENT : STROKE }} />
                  <text y="4" textAnchor="middle" style={{ fill: MARK_TEXT, fontSize: 'var(--pf-t--global--font--size--body--sm)' }}>
                    ƒ
                  </text>
                </g>
              )}
            </g>
          );
        })}
      </svg>
    </div>
  );
}

export interface MessageOverviewProps {
  browserState: BrowserStateStore;
  model: MappingModel;
  dependencies: DependencyView;
  selectedTarget: FieldPath | null;
  onSelect: (target: FieldPath) => void;
  children: [ReactNode, ReactNode];
}

/**
 * The source and target message panes side by side, the width between them resizable and kept per user; while the
 * Dependencies tool shows them, the field connections stand between the panes instead.
 */
export function MessageOverview({ browserState, model, dependencies, selectedTarget, onSelect, children }: MessageOverviewProps) {
  return (
    <SideBySide
      label="Resize source and target panels"
      leftId="source-message-pane"
      browserState={browserState}
      widthKey="definition.width"
      gutter={dependencies.visible ? <FieldConnections model={model} targets={dependencies.targets} selectedTarget={selectedTarget} onSelect={onSelect} /> : undefined}
    >
      {children}
    </SideBySide>
  );
}
