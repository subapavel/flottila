import { AngleDownIcon } from '@patternfly/react-icons';
import type { ComponentType, MouseEvent, Ref } from 'react';

interface ToolProps {
  label: string;
  icon?: ComponentType;
  pressed?: boolean;
  onClick: (event: MouseEvent<HTMLButtonElement>) => void;
  disabled?: boolean;
  /** The tool opens a menu: it shows a caret. */
  menu?: boolean;
  ref?: Ref<HTMLButtonElement>;
}

/** An icon button of a toolbar, named by its label (the prototype's Tool). */
export function Tool({ label, icon: Icon, pressed, onClick, disabled, menu = false, ref }: ToolProps) {
  return (
    <button
      ref={ref}
      type="button"
      className="def-tool"
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      disabled={disabled}
      aria-haspopup={menu ? 'menu' : undefined}
      onClick={onClick}
    >
      {Icon && <Icon />}
      {menu && <AngleDownIcon className="tool-caret" />}
    </button>
  );
}
