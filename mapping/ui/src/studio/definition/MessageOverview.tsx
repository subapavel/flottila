import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { BrowserStateStore } from '../browser-state/browser-state';
import type { AuthoringId, FieldPath, MappingModel, TargetFieldMapping } from '../../generated/mapping-model';
import { stepName } from '../../model/field-path';
import type { DependencyView } from './expression/DefinitionTools';
import { SideBySide } from '../shared/SplitLayout';

/** A source field a target field mapping reads, and the functions on the way (the prototype's `sourceLinks`). */
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
  const scroll = panel?.querySelector('.fields-scroll');
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

/**
 * The prototype's field connections: a curve from each source field row to each target field row whose mapping reads it,
 * both in view, with a function mark when functions lie between; a curve selects its target field.
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
    const panels = [...owner.querySelectorAll('.schema')];
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
    <div className="relation-gutter" ref={gutter}>
      <svg role="group" aria-label="Field connections">
        {lines.map(({ source, target, functions, width, y1, y2 }, i) => {
          const curve = `M0 ${y1} C${width * 0.43} ${y1} ${width * 0.57} ${y2} ${width} ${y2}`;
          const label = [stepName(source), ...functions, stepName(target)].join(' → ');
          return (
            <g
              key={`${source}|${target}|${i}`}
              className={`field-link${target === selectedTarget ? ' current' : ''}`}
              role="button"
              tabIndex={0}
              aria-label={label}
              aria-pressed={target === selectedTarget}
              onClick={() => onSelect(target)}
              onKeyDown={(e) => {
                if (e.key !== 'Enter' && e.key !== ' ') return;
                e.preventDefault();
                onSelect(target);
              }}
            >
              <title>{label}</title>
              <path className="link-hit" d={curve} />
              <path className="link-stroke" d={curve} />
              {functions.length > 0 && (
                <g className="link-function" transform={`translate(${width / 2} ${(y1 + y2) / 2})`}>
                  <rect x="-11" y="-9" width="22" height="18" rx="5" />
                  <text y="4" textAnchor="middle">
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
 * The source and target message panes side by side (the prototype's MessageOverview), the width between them resizable
 * and kept per user; while the Dependencies tool shows them, the field connections stand between the panes instead.
 */
export function MessageOverview({ browserState, model, dependencies, selectedTarget, onSelect, children }: MessageOverviewProps) {
  return (
    <SideBySide
      className="message-overview"
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
