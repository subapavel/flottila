import { Tooltip } from '@patternfly/react-core';
import { useEffect, useLayoutEffect, useState, type RefObject } from 'react';

/** The element a hint is about: the nearest one with a `data-mapping-hint`, or a node's card. */
const find = (target: EventTarget | null): Element | null =>
  target instanceof Element ? (target.closest('[data-mapping-hint]') ?? (target.matches('.react-flow__node') ? target.querySelector('.map-node') : null)) : null;

/**
 * The canvas's one tooltip (the prototype's MappingHint): it shows the `data-mapping-hint` of what the pointer is over or
 * the focus is on, ports and parameters taking precedence over their node; a pointer down, a context menu or Escape hides
 * it.
 */
export function MappingHint({ host }: { host: RefObject<HTMLElement | null> }) {
  const [anchor, setAnchor] = useState<Element | null>(null);
  const [content, setContent] = useState('');
  useLayoutEffect(() => {
    const canvas = host.current;
    if (!anchor || !canvas) return;
    const syncHint = () => {
      if (!canvas.contains(anchor)) setAnchor(null);
      else setContent((anchor as HTMLElement).dataset.mappingHint ?? '');
    };
    syncHint();
    const observer = new MutationObserver(syncHint);
    observer.observe(anchor, { attributes: true, attributeFilter: ['data-mapping-hint'] });
    observer.observe(canvas, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, [anchor, host]);
  useEffect(() => {
    const el = host.current!;
    const enter = (e: Event) => setAnchor((!(e as PointerEvent).buttons && find(e.target)) || null);
    const leave = (e: Event) =>
      setAnchor((current) => (current === find(e.target) && current !== find((e as FocusEvent | PointerEvent).relatedTarget) ? null : current));
    const clear = () => setAnchor(null);
    const escape = (e: Event) => {
      if ((e as KeyboardEvent).key === 'Escape') clear();
    };
    const events: Record<string, (e: Event) => void> = { pointerover: enter, pointerout: leave, focusin: enter, focusout: leave, pointerdown: clear, contextmenu: clear, keydown: escape };
    for (const [event, handler] of Object.entries(events)) el.addEventListener(event, handler, true);
    return () => {
      for (const [event, handler] of Object.entries(events)) el.removeEventListener(event, handler, true);
    };
  }, [host]);
  if (!anchor?.isConnected) return null;
  return <Tooltip className="node-description-tooltip" triggerRef={() => anchor as HTMLElement} trigger="manual" isVisible content={content} animationDuration={0} />;
}
