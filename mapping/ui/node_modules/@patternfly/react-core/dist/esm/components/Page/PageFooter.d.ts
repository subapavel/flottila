/// <reference types="react" />
export interface PageFooterProps extends React.HTMLProps<HTMLElement> {
    /** Content rendered inside the footer */
    children?: React.ReactNode;
    /** Additional classes added to the footer */
    className?: string;
    /** Sets the base component to render. Defaults to footer */
    component?: keyof React.JSX.IntrinsicElements;
}
export declare const PageFooter: React.FunctionComponent<PageFooterProps>;
//# sourceMappingURL=PageFooter.d.ts.map