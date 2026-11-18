/// <reference types="react" />
/** A wrapper component to be passed as custom content for the Compass main header. This should also be wrapped
 * in a `Panel` with `PanelMain` and `PanelMainBody`.
 */
export interface CompassMainHeaderContentProps extends React.HTMLProps<HTMLDivElement> {
    /** Content of the main header content. */
    children: React.ReactNode;
    /** Additional classes added to the main header content. */
    className?: string;
}
export declare const CompassMainHeaderContent: React.FunctionComponent<CompassMainHeaderContentProps>;
//# sourceMappingURL=CompassMainHeaderContent.d.ts.map