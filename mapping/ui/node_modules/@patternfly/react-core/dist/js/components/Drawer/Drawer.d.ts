/// <reference types="react" />
import { OUIAProps } from '../../helpers';
export declare enum DrawerColorVariant {
    default = "default",
    secondary = "secondary",
    /**
     * @deprecated `DrawerColorVariant.noBackground` is deprecated. Use the `isPlain` prop on `DrawerPanelContent` and the `DrawerSection`instead.
     */
    noBackground = "no-background"
}
export interface DrawerProps extends React.HTMLProps<HTMLDivElement>, OUIAProps {
    /** Additional classes added to the Drawer. */
    className?: string;
    /** Content rendered in the drawer panel */
    children?: React.ReactNode;
    /** Indicates if the drawer is expanded */
    isExpanded?: boolean;
    /** Indicates if the content element and panel element are displayed side by side. */
    isInline?: boolean;
    /** @beta Indicates if the drawer will have pill styles */
    isPill?: boolean;
    /** @beta Positions the drawer as fixed to fill the viewport. Place the drawer after Page as a sibling. */
    isViewport?: boolean;
    /** Indicates if the drawer will always show both content and panel. */
    isStatic?: boolean;
    /** Position of the drawer panel. left and right are deprecated, use start and end instead. */
    position?: 'start' | 'end' | 'bottom' | 'left' | 'right';
    /** Callback when drawer panel is expanded after waiting 250ms for animation to complete. */
    onExpand?: (event: KeyboardEvent | React.MouseEvent | React.TransitionEvent) => void;
    /** Value to overwrite the randomly generated data-ouia-component-id.*/
    ouiaId?: number | string;
    /** Set the value of data-ouia-safe. Only set to true when the component is in a static state, i.e. no animations are occurring. At all other times, this value must be false. */
    ouiaSafe?: boolean;
}
export interface DrawerContextProps {
    isExpanded: boolean;
    isStatic: boolean;
    onExpand?: (event: KeyboardEvent | React.MouseEvent | React.TransitionEvent) => void;
    position?: string;
    drawerRef?: React.RefObject<HTMLDivElement | null>;
    drawerContentRef?: React.RefObject<HTMLDivElement | null>;
    isInline: boolean;
    isViewport: boolean;
}
export declare const DrawerContext: import("react").Context<Partial<DrawerContextProps>>;
export declare const Drawer: React.FunctionComponent<DrawerProps>;
//# sourceMappingURL=Drawer.d.ts.map