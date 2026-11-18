/// <reference types="react" />
export interface PageGroupProps extends React.HTMLProps<HTMLDivElement> {
    /** Additional classes to apply to the PageGroup */
    className?: string;
    /** Content rendered inside of the PageGroup */
    children?: React.ReactNode;
    /** Modifier indicating if the PageBreadcrumb is sticky to the top or bottom at various breakpoints */
    stickyOnBreakpoint?: {
        default?: 'top' | 'bottom';
        sm?: 'top' | 'bottom';
        md?: 'top' | 'bottom';
        lg?: 'top' | 'bottom';
        xl?: 'top' | 'bottom';
        '2xl'?: 'top' | 'bottom';
    };
    /** @beta Applies the base sticky positioning to the top or bottom of the scroll parent container. */
    stickyBase?: 'top' | 'bottom';
    /** @beta Flag indicating if the group has stuck styling, applied when the group is not at the edge of the scroll parent container. */
    isStickyStuck?: boolean;
    /** Enables the page group to fill the available vertical space if true, or disable filling if false. */
    isFilled?: boolean;
    /** Modifier indicating if PageGroup should have a shadow at the top */
    hasShadowTop?: boolean;
    /** Modifier indicating if PageGroup should have a shadow at the bottom */
    hasShadowBottom?: boolean;
    /** Flag indicating if the PageGroup has a scrolling overflow */
    hasOverflowScroll?: boolean;
    /** Adds an accessible name to the page group when the hasOverflowScroll prop is set to true. */
    'aria-label'?: string;
    /** Adds plain styling to the page group. */
    isPlain?: boolean;
    /** @beta Prevents the page group from automatically applying plain styling when glass theme is enabled. */
    isNoPlainOnGlass?: boolean;
}
export declare const PageGroup: {
    ({ className, children, stickyOnBreakpoint, stickyBase, isStickyStuck, isFilled, hasShadowTop, hasShadowBottom, hasOverflowScroll, "aria-label": ariaLabel, isPlain, isNoPlainOnGlass, ...props }: PageGroupProps): import("react/jsx-runtime").JSX.Element;
    displayName: string;
};
//# sourceMappingURL=PageGroup.d.ts.map