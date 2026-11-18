/// <reference types="react" />
import { ButtonProps } from '../Button';
export interface DrawerCloseButtonProps extends React.HTMLProps<HTMLDivElement> {
    /** Additional classes added to the drawer close button outer <div>. */
    className?: string;
    /** A callback for when the close button is clicked  */
    onClose?: () => void;
    /** Accessible label for the drawer close button */
    'aria-label'?: string;
    /** Additional properties spread to the close button */
    buttonProps?: Omit<ButtonProps, 'onClick'>;
}
export declare const DrawerCloseButton: React.FunctionComponent<DrawerCloseButtonProps>;
//# sourceMappingURL=DrawerCloseButton.d.ts.map