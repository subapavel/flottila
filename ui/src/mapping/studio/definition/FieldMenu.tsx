import { Divider, Menu, MenuContent, MenuGroup, MenuItem, MenuList, Popper } from '@patternfly/react-core';
import { Fragment, useEffect, useRef, useState } from 'react';

/** One item of a field menu: it runs, or it is shown disabled. */
export interface FieldMenuItem {
  label: string;
  run?: () => void;
  disabled?: boolean;
}

/** A group of a field menu, under its label when it has one. */
export interface FieldMenuGroup {
  label?: string;
  items: FieldMenuItem[];
}

/** An open field menu: what it is named, its groups, and the element it opens below. */
export interface OpenFieldMenu {
  label: string;
  groups: FieldMenuGroup[];
  anchor: HTMLElement;
}

/**
 * A field's context menu, a PatternFly menu below its field: it takes the focus as it opens; a choice, Escape or a
 * click outside it closes it, and a choice or Escape gives the focus back to its field.
 */
export function FieldMenu({ menu, onClose }: { menu: OpenFieldMenu; onClose: () => void }) {
  const menuRef = useRef<HTMLDivElement>(null);
  const close = (focus: boolean) => {
    onClose();
    if (focus && menu.anchor.isConnected) menu.anchor.focus({ preventScroll: true });
  };
  useEffect(() => {
    requestAnimationFrame(() => menuRef.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus({ preventScroll: true }));
  }, [menu]);
  return (
    <Popper
      triggerRef={() => menu.anchor}
      popperRef={menuRef}
      isVisible
      placement="bottom-start"
      appendTo={() => document.body}
      onDocumentClick={(event) => {
        if (!menuRef.current?.contains(event?.target as Node)) close(false);
      }}
      onDocumentKeyDown={(event) => {
        if (event?.key === 'Escape') close(true);
      }}
      popper={
        <Menu ref={menuRef} aria-label={menu.label} onSelect={() => close(true)}>
          <MenuContent>
            {menu.groups.map(({ label, items }, i) => (
              <Fragment key={label ?? i}>
                {i > 0 && <Divider />}
                <MenuGroup label={label}>
                  <MenuList aria-label={label ?? menu.label}>
                    {items.map(({ label: name, run, disabled }) => (
                      <MenuItem key={name} isDisabled={disabled} onClick={run}>
                        {name}
                      </MenuItem>
                    ))}
                  </MenuList>
                </MenuGroup>
              </Fragment>
            ))}
          </MenuContent>
        </Menu>
      }
    />
  );
}

/** One field menu at a time: `showMenu` opens one, `menu` is the open one, `closeMenu` closes it. */
export function useFieldMenu() {
  const [menu, setMenu] = useState<OpenFieldMenu | null>(null);
  return { menu, showMenu: setMenu, closeMenu: () => setMenu(null) };
}
