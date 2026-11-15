import { AngleRightIcon, CheckIcon, ToggleOffIcon, ToggleOnIcon, TrashIcon } from '@patternfly/react-icons';
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ComponentType, type KeyboardEvent, type MouseEvent as ReactMouseEvent } from 'react';
import { createPortal } from 'react-dom';

/** One item of a menu (the prototype's `action`): it runs, opens `children`, or is a radio (`checked`) or a toggle. */
export interface MenuItem {
  label: string;
  run?: (() => void) | null;
  key?: string;
  icon?: ComponentType;
  disabled?: boolean;
  checked?: boolean;
  toggle?: boolean;
  children?: MenuItem[];
}

export const action = (label: string, run: (() => void) | null, extra: Partial<MenuItem> = {}): MenuItem => ({ label, run, ...extra });
export const deleteAction = (label: string, run: () => void, extra: Partial<MenuItem> = {}): MenuItem => action(label, run, { icon: TrashIcon, ...extra });

/** The keys that open a context menu from the keyboard. */
export const isContextMenuKey = (e: KeyboardEvent) => e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10');

/** Where a menu opens and what gets the focus back when it closes. */
export interface MenuAnchor {
  x: number;
  y: number;
  left?: number;
  element: Element;
}

/** The anchor of a menu opened by `e`: at the pointer of a context menu click, else below `element`. */
export function menuAnchor(e: ReactMouseEvent | KeyboardEvent | MouseEvent, element: Element = e.currentTarget as Element): MenuAnchor {
  e.preventDefault();
  e.stopPropagation();
  const rect = element.getBoundingClientRect();
  const point = e.type === 'contextmenu' && 'clientX' in e && (e.clientX || e.clientY);
  return { x: point ? (e as MouseEvent).clientX : rect.left, y: point ? (e as MouseEvent).clientY : rect.bottom, element };
}

/** An open menu. */
export interface OpenMenu {
  label: string;
  items: MenuItem[];
  anchor: MenuAnchor;
  key: number;
}

interface MenuLevelProps {
  items: MenuItem[];
  anchor: MenuAnchor;
  label: string;
  level?: number;
  close: () => void;
  back?: () => void;
}

function MenuLevel({ items, anchor, label, level = 0, close, back }: MenuLevelProps) {
  const box = useRef<HTMLDivElement>(null);
  const [sub, setSub] = useState<{ item: MenuItem; anchor: MenuAnchor } | null>(null);
  const [pos, setPos] = useState({ left: anchor.x, top: anchor.y });
  useLayoutEffect(() => {
    const rect = box.current!.getBoundingClientRect();
    const left = level && anchor.x + rect.width > window.innerWidth ? anchor.left! - rect.width : anchor.x;
    setPos({
      left: Math.max(5, Math.min(left, window.innerWidth - rect.width - 5)),
      top: Math.max(5, Math.min(anchor.y, window.innerHeight - rect.height - 5)),
    });
    box.current!.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus({ preventScroll: true });
  }, [anchor, level]);
  const openChild = (item: MenuItem, el: HTMLElement, focus = false) => {
    if (!item.children) {
      setSub(null);
      return;
    }
    if (sub?.item === item) {
      if (focus) box.current!.querySelector<HTMLButtonElement>('[role=menu] button:not(:disabled)')?.focus();
      return;
    }
    const r = el.getBoundingClientRect();
    setSub({ item, anchor: { x: r.right - 2, left: r.left, y: r.top - 4, element: el } });
  };
  const activate = (item: MenuItem, el: HTMLElement) => {
    if (item.children) {
      openChild(item, el, true);
      return;
    }
    close();
    item.run?.();
  };
  const keys = (e: KeyboardEvent) => {
    e.stopPropagation();
    const buttons = [...box.current!.querySelectorAll<HTMLButtonElement>(':scope > button:not(:disabled)')];
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
    if (e.key === 'Escape' || (level && e.key === 'ArrowLeft')) {
      e.preventDefault();
      if (level) back!();
      else close();
      return;
    }
    if (e.key === 'ArrowRight' && index >= 0) {
      e.preventDefault();
      if (buttons[index]!.getAttribute('aria-haspopup') === 'menu') buttons[index]!.click();
      return;
    }
    const move: Record<string, number> = {
      ArrowDown: (index + 1) % buttons.length,
      ArrowUp: (index - 1 + buttons.length) % buttons.length,
      Home: 0,
      End: buttons.length - 1,
    };
    if (Object.hasOwn(move, e.key)) {
      e.preventDefault();
      buttons[move[e.key]!]?.focus();
    }
    if (e.key === 'Tab') close();
  };
  return (
    <div className="def-menu" role="menu" aria-label={label} ref={box} style={pos} onKeyDown={keys}>
      {items.map((item) => {
        const Mark = item.toggle ? (item.checked ? ToggleOnIcon : ToggleOffIcon) : item.checked ? CheckIcon : item.icon;
        return (
          <button
            role={item.checked === undefined ? 'menuitem' : item.toggle ? 'menuitemcheckbox' : 'menuitemradio'}
            aria-checked={item.checked}
            type="button"
            key={item.key ?? item.label}
            disabled={item.disabled}
            aria-haspopup={item.children ? 'menu' : undefined}
            aria-expanded={item.children ? sub?.item === item : undefined}
            onPointerEnter={(e) => {
              if (!item.disabled) openChild(item, e.currentTarget);
            }}
            onClick={(e) => activate(item, e.currentTarget)}
          >
            <span className="menu-mark" aria-hidden="true">
              {Mark && <Mark />}
            </span>
            <span>{item.label}</span>
            {item.children && <AngleRightIcon />}
          </button>
        );
      })}
      {sub && (
        <MenuLevel
          key={sub.item.label}
          label={sub.item.label}
          items={sub.item.children!}
          anchor={sub.anchor}
          level={level + 1}
          close={close}
          back={() => {
            (sub.anchor.element as HTMLElement).focus();
            setSub(null);
          }}
        />
      )}
    </div>
  );
}

/**
 * The prototype's context menu, in a portal: arrow keys move, Right and Left open and close a submenu, Escape closes; a
 * pointer down outside it or a resize closes it, and closing by a choice or Escape gives the focus back to its anchor.
 */
export function ActionMenu({ menu, onClose }: { menu: OpenMenu | null; onClose: (focus: boolean) => void }) {
  useEffect(() => {
    if (!menu) return;
    const outside = (e: PointerEvent) => {
      if (!(e.target as Element).closest('.def-menu')) onClose(false);
    };
    const resize = () => onClose(false);
    document.addEventListener('pointerdown', outside, true);
    window.addEventListener('resize', resize);
    return () => {
      document.removeEventListener('pointerdown', outside, true);
      window.removeEventListener('resize', resize);
    };
  }, [menu, onClose]);
  return menu ? createPortal(<MenuLevel key={menu.key} items={menu.items} label={menu.label} anchor={menu.anchor} close={() => onClose(true)} />, document.body) : null;
}

/** One open menu at a time: `showMenu` opens one at the event's anchor, `closeMenu` closes it. */
export function useActionMenu() {
  const [menu, setMenu] = useState<OpenMenu | null>(null);
  const serial = useRef(0);
  const open = useRef<OpenMenu | null>(null);
  const showMenu = useCallback((e: ReactMouseEvent | KeyboardEvent | MouseEvent, label: string, items: MenuItem[], element?: Element | null) => {
    const anchor = menuAnchor(e, element ?? undefined);
    open.current = { label, items, anchor, key: ++serial.current };
    setMenu(open.current);
  }, []);
  const closeMenu = useCallback((focus: boolean) => {
    const element = open.current?.anchor.element as HTMLElement | undefined;
    if (focus && element?.isConnected) element.focus({ preventScroll: true });
    open.current = null;
    setMenu(null);
  }, []);
  return { menu, showMenu, closeMenu };
}
