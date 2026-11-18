/// <reference types="react" />
import { DrawerColorVariant } from './Drawer';
export interface DrawerSectionProps extends React.HTMLProps<HTMLDivElement> {
    /** Additional classes added to the drawer section. */
    className?: string;
    /** Content to be rendered in the drawer section. */
    children?: React.ReactNode;
    /**
     * Color variant of the background of the drawer section.
     * The `no-background` value is deprecated; use the `isPlain` prop instead.
     */
    colorVariant?: DrawerColorVariant | 'no-background' | 'default' | 'secondary';
    /** @beta Flag indicating that the drawer section should use plain styles. */
    isPlain?: boolean;
}
export declare const DrawerSection: React.FunctionComponent<DrawerSectionProps>;
//# sourceMappingURL=DrawerSection.d.ts.map