/// <reference types="react" />
/** A wrapper component for custom toolbar content to be passed into a Compass main header. This should also be wrapped
 * by a Compass main header content component.
 */
export interface CompassMainHeaderToolbarProps extends React.HTMLProps<HTMLDivElement> {
    /** Content of the main header toolbar. */
    children: React.ReactNode;
    /** Additional classes added to the main header toolbar. */
    className?: string;
}
export declare const CompassMainHeaderToolbar: React.FunctionComponent<CompassMainHeaderToolbarProps>;
//# sourceMappingURL=CompassMainHeaderToolbar.d.ts.map