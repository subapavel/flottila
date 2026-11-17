import { Button, Dropdown, DropdownItem, DropdownList, MenuToggle } from '@patternfly/react-core';
import { useState, type ComponentType } from 'react';

interface ToolProps {
  label: string;
  icon: ComponentType;
  /** A toggle tool: whether it is on. */
  pressed?: boolean;
  onClick: () => void;
  disabled?: boolean;
}

/** An icon button of a toolbar, named by its label. */
export function Tool({ label, icon: Icon, pressed, onClick, disabled }: ToolProps) {
  return <Button variant="plain" size="sm" aria-label={label} title={label} aria-pressed={pressed} isClicked={pressed} isDisabled={disabled} icon={<Icon />} onClick={onClick} />;
}

/** One option of a `ToolMenu`: it runs; `checked` marks the option in force. */
export interface ToolMenuItem {
  label: string;
  run: () => void;
  checked?: boolean;
  disabled?: boolean;
}

/** A tool's options as a menu below its caret; `icon` shows on the caret when the menu is a tool of its own. */
export function ToolMenu({ label, items, icon: Icon, disabled }: { label: string; items: ToolMenuItem[]; icon?: ComponentType; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <Dropdown
      isOpen={open}
      onOpenChange={setOpen}
      onSelect={() => setOpen(false)}
      toggle={(ref) => (
        <MenuToggle ref={ref} variant="plain" size="sm" aria-label={label} title={label} isDisabled={disabled} isExpanded={open} icon={Icon && <Icon />} onClick={() => setOpen(!open)} />
      )}
      popperProps={{ position: 'start' }}
    >
      <DropdownList>
        {items.map(({ label: name, run, checked, disabled: off }) => (
          <DropdownItem key={name} isSelected={checked} isDisabled={off} onClick={run}>
            {name}
          </DropdownItem>
        ))}
      </DropdownList>
    </Dropdown>
  );
}
