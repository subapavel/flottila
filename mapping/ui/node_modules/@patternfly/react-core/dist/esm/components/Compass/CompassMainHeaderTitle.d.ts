/// <reference types="react" />
/** A wrapper component for custom title content to be passed into a Compass main header. This should also be wrapped
 * by a Compass main header content component.
 */
export interface CompassMainHeaderTitleProps extends React.HTMLProps<HTMLDivElement> {
    /** Content of the main header title. */
    children: React.ReactNode;
    /** Additional classes added to the main header title. */
    className?: string;
}
export declare const CompassMainHeaderTitle: React.FunctionComponent<CompassMainHeaderTitleProps>;
//# sourceMappingURL=CompassMainHeaderTitle.d.ts.map