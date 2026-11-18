/// <reference types="react" />
export interface PageHeaderProps extends React.HTMLProps<HTMLElement> {
    /** Content rendered inside the page header. This should be custom header content, rather than the PatternFly Masthead. */
    children?: React.ReactNode;
    /** Additional classes added to the page header */
    className?: string;
    /** Sets the base component to render. Defaults to header */
    component?: keyof React.JSX.IntrinsicElements;
}
export declare const PageHeader: React.FunctionComponent<PageHeaderProps>;
//# sourceMappingURL=PageHeader.d.ts.map