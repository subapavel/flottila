import { Divider, Menu, MenuContent, MenuGroup, MenuItem, MenuList, Popper } from '@patternfly/react-core';
import { TrashIcon } from '@patternfly/react-icons';
import { Fragment, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { createPortal } from 'react-dom';

/**
 * One item of an action menu: it runs, or opens its `children` in a flyout, or is shown disabled. A child with
 * `checked` set is a choice: the flyout of such children is a single-select list with the one in force marked.
 * `danger` marks a destructive item.
 */
export interface ActionMenuItem {
  label: string;
  run?: () => void;
  disabled?: boolean;
  danger?: boolean;
  /** Tells apart items of one menu that share a label. */
  key?: string;
  checked?: boolean;
  children?: ActionMenuItem[];
}

/** A group of an action menu, under its label when it has one. */
export interface ActionMenuGroup {
  label?: string;
  items: ActionMenuItem[];
}

/** An open action menu: what it is named, its groups, the element it belongs to, and the point it opens at, if any. */
export interface OpenActionMenu {
  label: string;
  groups: ActionMenuGroup[];
  /** The element the menu opens below, unless `at` is given, and that takes the focus back. */
  anchor: HTMLElement;
  /** The viewport point a context menu click opened it at. */
  at?: { x: number; y: number };
}

/** The keys that open a context menu from the keyboard. */
export const isContextMenuKey = (e: KeyboardEvent) => e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10');

function Items({ items, label, close, choice }: { items: ActionMenuItem[]; label: string; close: (focus: boolean) => void; choice?: boolean }) {
  return (
    <MenuList aria-label={label}>
      {items.map((item) => (
        <MenuItem
          key={item.key ?? item.label}
          itemId={item.key ?? item.label}
          isDisabled={item.disabled}
          isDanger={item.danger}
          icon={item.danger ? <TrashIcon aria-hidden /> : undefined}
          isSelected={choice ? item.checked === true : undefined}
          flyoutMenu={item.children && <Flyout label={item.label} items={item.children} close={close} />}
          onClick={
            item.children
              ? undefined
              : () => {
                  close(true);
                  item.run?.();
                }
          }
        >
          {item.label}
        </MenuItem>
      ))}
    </MenuList>
  );
}

/** The flyout of an item with children: a list of choices when they carry `checked`. */
function Flyout({ label, items, close }: { label: string; items: ActionMenuItem[]; close: (focus: boolean) => void }) {
  const choice = items.some((item) => item.checked !== undefined);
  return (
    <Menu aria-label={label} containsFlyout role={choice ? 'listbox' : undefined}>
      <MenuContent>
        <Items items={items} label={label} close={close} choice={choice} />
      </MenuContent>
    </Menu>
  );
}

/**
 * An action menu, a PatternFly menu below its anchor or at the point it was opened at: it takes the focus as it
 * opens; a choice, Escape, a pointer down outside it or a resize closes it, and a choice or Escape gives the focus back
 * to its anchor.
 * An item with children opens them in a flyout, by the pointer or the Right arrow.
 */
export function ActionMenu({ menu, onClose }: { menu: OpenActionMenu; onClose: () => void }) {
  const menuRef = useRef<HTMLDivElement>(null);
  const pointRef = useRef<HTMLSpanElement>(null);
  const close = (focus: boolean) => {
    onClose();
    if (focus && menu.anchor.isConnected) menu.anchor.focus({ preventScroll: true });
  };
  useEffect(() => {
    requestAnimationFrame(() => menuRef.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus({ preventScroll: true }));
  }, [menu]);
  // a right click fires no click: a pointer down outside closes the menu, as another menu opens
  useEffect(() => {
    const outside = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) onClose();
    };
    const resize = () => onClose();
    document.addEventListener('pointerdown', outside, true);
    window.addEventListener('resize', resize);
    return () => {
      document.removeEventListener('pointerdown', outside, true);
      window.removeEventListener('resize', resize);
    };
  }, [onClose]);
  const flyouts = menu.groups.some((group) => group.items.some((item) => item.children));
  return (
    <>
      {menu.at && createPortal(<span ref={pointRef} aria-hidden style={{ position: 'fixed', left: menu.at.x, top: menu.at.y }} />, document.body)}
      <Popper
        triggerRef={() => pointRef.current ?? menu.anchor}
        isVisible
        placement="bottom-start"
        appendTo={() => document.body}
        onDocumentKeyDown={(event) => {
          if (event?.key === 'Escape') close(true);
        }}
        popper={
          <Menu ref={menuRef} aria-label={menu.label} containsFlyout={flyouts}>
            <MenuContent>
              {menu.groups.map(({ label, items }, i) => (
                <Fragment key={label ?? i}>
                  {i > 0 && <Divider />}
                  <MenuGroup label={label}>
                    <Items items={items} label={label ?? menu.label} close={close} />
                  </MenuGroup>
                </Fragment>
              ))}
            </MenuContent>
          </Menu>
        }
      />
    </>
  );
}

/**
 * One action menu at a time: `showMenu` opens one, `menu` is the open one, `closeMenu` closes it and `element`
 * renders it.
 */
export function useActionMenu() {
  const [open, setOpen] = useState<{ menu: OpenActionMenu; serial: number } | null>(null);
  const serial = useRef(0);
  const showMenu = (menu: OpenActionMenu) => setOpen({ menu, serial: ++serial.current });
  // a menu opened while another is open replaces it
  const element = open && <ActionMenu key={open.serial} menu={open.menu} onClose={() => setOpen(null)} />;
  return { menu: open?.menu ?? null, showMenu, closeMenu: () => setOpen(null), element };
}

/** Where a context menu click happened, for `OpenActionMenu.at`; none for a key or a click without a point. */
export function pointOf(event: { type: string; clientX?: number; clientY?: number }): OpenActionMenu['at'] {
  return event.type === 'contextmenu' && (event.clientX || event.clientY) ? { x: event.clientX!, y: event.clientY! } : undefined;
}
