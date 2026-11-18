/// <reference types="react" />
export interface PanelProps extends React.HTMLProps<HTMLDivElement> {
    /** Content rendered inside the panel */
    children?: React.ReactNode;
    /** Class to add to outer div */
    className?: string;
    /** Adds panel variant styles */
    variant?: 'raised' | 'bordered' | 'secondary';
    /** Flag to add scrollable styling to the panel */
    isScrollable?: boolean;
    /** @beta When used with a scrollable panel, sets the panel to auto height */
    isAutoHeight?: boolean;
    /** @beta Flag to remove the panel's border */
    hasNoBorder?: boolean;
    /** @beta Flag to make the panel fill the available height of its container */
    isFullHeight?: boolean;
    /** @beta Modifies the panel to use glass styling when the glass theme is enabled */
    isGlass?: boolean;
    /** @beta Uses pill (fully rounded) border radius for the panel */
    isPill?: boolean;
    /** @hide Forwarded ref */
    innerRef?: React.Ref<any>;
}
export declare const Panel: import("react").ForwardRefExoticComponent<Omit<PanelProps, "ref"> & import("react").RefAttributes<any>>;
//# sourceMappingURL=Panel.d.ts.map