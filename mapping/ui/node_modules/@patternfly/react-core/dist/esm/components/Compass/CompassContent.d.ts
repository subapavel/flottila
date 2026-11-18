/// <reference types="react" />
import { DrawerProps } from '../Drawer';
export interface CompassContentProps extends React.HTMLProps<HTMLDivElement> {
    /** Content of the main Compass area. Typically one or more `Panel` components. */
    children: React.ReactNode;
    /** Additional classes added to the CompassContent */
    className?: string;
    /** Content rendered in an optional drawer wrapping the CompassContent */
    drawerContent?: React.ReactNode;
    /** Additional props passed to the drawer */
    drawerProps?: DrawerProps;
}
export declare const CompassContent: React.FunctionComponent<CompassContentProps>;
//# sourceMappingURL=CompassContent.d.ts.map