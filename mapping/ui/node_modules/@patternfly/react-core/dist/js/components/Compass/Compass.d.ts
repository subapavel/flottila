/// <reference types="react" />
import { DrawerProps } from '../Drawer';
export interface CompassProps extends React.HTMLProps<HTMLDivElement> {
    /** Additional classes added to the Compass. */
    className?: string;
    /** The horizontal masthead content (e.g. <Masthead />). This masthead will only render when dock content is passed and only at mobile viewports. */
    masthead?: React.ReactNode;
    /** Content of the docked navigation area of the layout */
    dock?: React.ReactNode;
    /** @beta Flag indicating the docked nav is expanded. Only applies when dock content is passed. */
    isDockExpanded?: boolean;
    /** @beta Flag indicating the docked nav should expand as an overlay instead of the default inline. */
    isDockOverlay?: boolean;
    /** Content placed at the top of the compass layout */
    header?: React.ReactNode;
    /** Flag indicating if the header is expanded */
    isHeaderExpanded?: boolean;
    /** Content placed at the horizontal start of the layout, before the main content */
    sidebarStart?: React.ReactNode;
    /** Flag indicating if the start sidebar is expanded */
    isSidebarStartExpanded?: boolean;
    /** Content placed at the center of the layout */
    main?: React.ReactNode;
    /** Content placed at the horizontal end of the layout, after the main content */
    sidebarEnd?: React.ReactNode;
    /** Flag indicating if the end sidebar is expanded */
    isSidebarEndExpanded?: boolean;
    /** Content placed at the bottom of the layout */
    footer?: React.ReactNode;
    /** Flag indicating if the footer is expanded */
    isFooterExpanded?: boolean;
    /** Content rendered in an optional drawer wrapping the layout */
    drawerContent?: React.ReactNode;
    /** Additional props passed to the drawer */
    drawerProps?: DrawerProps;
}
export declare const Compass: React.FunctionComponent<CompassProps>;
//# sourceMappingURL=Compass.d.ts.map